package nz.co.mixport.customsvision.camera

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

class LiveTrackCountEngine(
    private val tuning: InspectionTuningProfile = InspectionTuningProfile.default(),
) {
    private val minStableFrames: Int = tuning.tracking.minStableFrames
    private val maxTrackGapMs: Long = tuning.tracking.maxTrackGapMs
    private val countedEvidenceTtlMs: Long = tuning.tracking.countedEvidenceTtlMs

    private data class TrackMemory(
        val key: String,
        var trackingId: Int?,
        var lastSeenAt: Long,
        var stableFrameCount: Int,
        var lastLabel: String,
        var lastCategory: String,
        var lastCenterX: Float,
        var lastCenterY: Float,
        var lastWidth: Float,
        var lastHeight: Float,
        var isPalletCandidate: Boolean,
        var lastPalletScore: Float,
        val labelVotes: MutableMap<String, Int> = linkedMapOf(),
    ) {
        fun bestKnownLabel(): String {
            val voted = labelVotes.entries
                .filterNot { (label, _) -> label in GENERIC_LABELS }
                .maxWithOrNull(
                    compareBy<Map.Entry<String, Int>> { it.value }
                        .thenByDescending { labelQualityScore(it.key) },
                )
                ?.key
            return voted
                ?: lastLabel.takeIf { it !in GENERIC_LABELS }
                ?: lastCategory.takeIf { it !in GENERIC_LABELS }
                ?: lastLabel
        }
    }

    private data class CountedEvidence(
        val label: String,
        val centerX: Float,
        val centerY: Float,
        val width: Float,
        val height: Float,
        val countedAt: Long,
    )

    private val tracksByKey = linkedMapOf<String, TrackMemory>()
    private val trackIdToKey = mutableMapOf<Int, String>()
    private val countedTrackKeys = linkedSetOf<String>()
    private val countedEvidence = mutableListOf<CountedEvidence>()
    private var nextTrackNumber = 1

    fun resetSession() {
        tracksByKey.clear()
        trackIdToKey.clear()
        countedTrackKeys.clear()
        countedEvidence.clear()
        nextTrackNumber = 1
    }

    fun resetForNextPallet() {
        tracksByKey.clear()
        trackIdToKey.clear()
        countedTrackKeys.clear()
        countedEvidence.clear()
    }

    fun observeDetections(
        detections: List<LiveRecognition>,
        analyzedAt: Long,
    ): List<LiveRecognition> {
        pruneStaleState(analyzedAt)
        val matchedKeys = mutableSetOf<String>()
        val trackedDetections = detections.map { detection ->
            val track = findOrCreateTrack(detection, analyzedAt, matchedKeys)
            matchedKeys += track.key
            updateTrack(track, detection, analyzedAt)
            detection to track
        }
        val palletTrack = selectPrimaryPalletTrack(analyzedAt)
        return trackedDetections.map { (detection, track) ->
            decorateDetection(detection, track, palletTrack, analyzedAt)
        }
    }

    fun observeRecognition(snapshot: UniversalRecognitionSnapshot): UniversalRecognitionSnapshot {
        pruneStaleState(snapshot.analyzedAt)
        val palletTrack = selectPrimaryPalletTrack(snapshot.analyzedAt)
        val decoratedItems = snapshot.items.map { recognition ->
            val track = resolveTrack(recognition.trackKey, recognition.trackingId)
            if (track == null) {
                recognition
            } else {
                absorbRecognition(track, recognition)
                decorateRecognition(recognition, track, palletTrack, snapshot.analyzedAt)
            }
        }
        return snapshot.copy(items = decoratedItems)
    }

    fun decorateDetections(
        detections: List<LiveRecognition>,
        analyzedAt: Long,
    ): List<LiveRecognition> {
        pruneStaleState(analyzedAt)
        val palletTrack = selectPrimaryPalletTrack(analyzedAt)
        return detections.map { detection ->
            val track = resolveTrack(detection.trackKey, detection.trackingId)
            if (track == null) detection else decorateDetection(detection, track, palletTrack, analyzedAt)
        }
    }

    fun decorateRecognitionSnapshot(snapshot: UniversalRecognitionSnapshot): UniversalRecognitionSnapshot {
        pruneStaleState(snapshot.analyzedAt)
        val palletTrack = selectPrimaryPalletTrack(snapshot.analyzedAt)
        return snapshot.copy(
            items = snapshot.items.map { recognition ->
                val track = resolveTrack(recognition.trackKey, recognition.trackingId)
                if (track == null) recognition else decorateRecognition(recognition, track, palletTrack, snapshot.analyzedAt)
            },
        )
    }

    fun markCounted(
        detections: List<LiveRecognition>,
        countedAt: Long,
    ) {
        pruneStaleState(countedAt)
        detections.forEach { detection ->
            if (detection.trackKey.isBlank()) {
                return@forEach
            }
            countedTrackKeys += detection.trackKey
            countedEvidence += CountedEvidence(
                label = detection.label,
                centerX = detection.centerX,
                centerY = detection.centerY,
                width = detection.width,
                height = detection.height,
                countedAt = countedAt,
            )
        }
    }

    private fun findOrCreateTrack(
        detection: LiveRecognition,
        analyzedAt: Long,
        matchedKeys: Set<String>,
    ): TrackMemory {
        val directTrack = detection.trackingId
            ?.let(trackIdToKey::get)
            ?.let(tracksByKey::get)
            ?.takeIf { analyzedAt - it.lastSeenAt <= maxTrackGapMs && it.key !in matchedKeys }
        if (directTrack != null) {
            return directTrack
        }

        val spatialTrack = tracksByKey.values
            .asSequence()
            .filter { it.key !in matchedKeys }
            .filter { analyzedAt - it.lastSeenAt <= maxTrackGapMs }
            .filter { it.isPalletCandidate == detection.isPalletCandidate }
            .mapNotNull { track ->
                scoreSpatialMatch(track, detection)?.let { score -> track to score }
            }
            .minByOrNull { (_, score) -> score }
            ?.first
        if (spatialTrack != null) {
            detection.trackingId?.let { trackingId ->
                spatialTrack.trackingId = trackingId
                trackIdToKey[trackingId] = spatialTrack.key
            }
            return spatialTrack
        }

        val newTrack = TrackMemory(
            key = "track-${nextTrackNumber++}",
            trackingId = detection.trackingId,
            lastSeenAt = analyzedAt,
            stableFrameCount = 0,
            lastLabel = detection.label,
            lastCategory = detection.category,
            lastCenterX = detection.centerX,
            lastCenterY = detection.centerY,
            lastWidth = detection.width,
            lastHeight = detection.height,
            isPalletCandidate = detection.isPalletCandidate,
            lastPalletScore = detection.palletScore ?: 0f,
        )
        addLabelVote(newTrack, detection.label, weight = 2)
        addLabelVote(newTrack, detection.category, weight = 1)
        tracksByKey[newTrack.key] = newTrack
        detection.trackingId?.let { trackingId ->
            trackIdToKey[trackingId] = newTrack.key
        }
        return newTrack
    }

    private fun updateTrack(
        track: TrackMemory,
        detection: LiveRecognition,
        analyzedAt: Long,
    ) {
        track.lastSeenAt = analyzedAt
        track.stableFrameCount += 1
        track.lastLabel = detection.label
        track.lastCategory = detection.category
        track.lastCenterX = detection.centerX
        track.lastCenterY = detection.centerY
        track.lastWidth = detection.width
        track.lastHeight = detection.height
        track.isPalletCandidate = detection.isPalletCandidate
        track.lastPalletScore = detection.palletScore ?: track.lastPalletScore
        if (detection.trackingId != null) {
            track.trackingId = detection.trackingId
            trackIdToKey[detection.trackingId] = track.key
        }
        addLabelVote(track, detection.label, weight = 2)
        addLabelVote(track, detection.category, weight = 1)
        if (detection.isPalletCandidate) {
            addLabelVote(track, "Wood pallet base", weight = 2)
        }
    }

    private fun absorbRecognition(
        track: TrackMemory,
        recognition: UniversalRecognition,
    ) {
        addLabelVote(track, recognition.bestLabel, weight = 4)
        recognition.labelHints.forEach { hint ->
            addLabelVote(track, hint, weight = 1)
        }
        if (recognition.isPalletLike) {
            track.isPalletCandidate = true
            track.lastPalletScore = max(track.lastPalletScore, recognition.palletScore ?: 0f)
            addLabelVote(track, "Wood pallet base", weight = 4)
        }
    }

    private fun decorateDetection(
        detection: LiveRecognition,
        track: TrackMemory,
        palletTrack: TrackMemory?,
        analyzedAt: Long,
    ): LiveRecognition {
        val preferredLabel = when {
            detection.isPalletCandidate -> "Pallet candidate"
            else -> track.bestKnownLabel()
        }
        val inPalletZone = isInPalletZone(track, palletTrack)
        val alreadyCounted = isAlreadyCounted(track, detection, analyzedAt)
        return detection.copy(
            label = preferredLabel,
            trackKey = track.key,
            stableFrameCount = track.stableFrameCount,
            isCountReady = !detection.isPalletCandidate &&
                inPalletZone &&
                track.stableFrameCount >= minStableFrames &&
                !alreadyCounted,
            isCounted = alreadyCounted,
            isInPalletZone = inPalletZone,
        )
    }

    private fun decorateRecognition(
        recognition: UniversalRecognition,
        track: TrackMemory,
        palletTrack: TrackMemory?,
        analyzedAt: Long,
    ): UniversalRecognition {
        val fusedLabel = if (recognition.isPalletLike) {
            "Wood pallet base"
        } else {
            track.bestKnownLabel()
        }
        val inPalletZone = isInPalletZone(track, palletTrack)
        val alreadyCounted = countedTrackKeys.contains(track.key) ||
            countedEvidence.any { evidence ->
                evidence.matches(
                    label = fusedLabel,
                    centerX = track.lastCenterX,
                    centerY = track.lastCenterY,
                    width = track.lastWidth,
                    height = track.lastHeight,
                    observedAt = analyzedAt,
                    ttlMs = countedEvidenceTtlMs,
                )
            }
        return recognition.copy(
            bestLabel = fusedLabel,
            trackKey = track.key,
            stableFrameCount = track.stableFrameCount,
            isCountReady = !recognition.isPalletLike &&
                inPalletZone &&
                track.stableFrameCount >= minStableFrames &&
                !alreadyCounted,
            isCounted = alreadyCounted,
            isInPalletZone = inPalletZone,
        )
    }

    private fun resolveTrack(
        trackKey: String,
        trackingId: Int?,
    ): TrackMemory? {
        if (trackKey.isNotBlank()) {
            tracksByKey[trackKey]?.let { return it }
        }
        return trackingId
            ?.let(trackIdToKey::get)
            ?.let(tracksByKey::get)
    }

    private fun selectPrimaryPalletTrack(now: Long): TrackMemory? {
        return tracksByKey.values
            .asSequence()
            .filter { it.isPalletCandidate }
            .filter { now - it.lastSeenAt <= maxTrackGapMs }
            .maxWithOrNull(
                compareBy<TrackMemory> { it.lastPalletScore }
                    .thenBy { it.lastWidth * it.lastHeight }
                    .thenBy { it.lastSeenAt },
            )
    }

    private fun isInPalletZone(
        track: TrackMemory,
        palletTrack: TrackMemory?,
    ): Boolean {
        if (palletTrack == null || track.key == palletTrack.key || track.isPalletCandidate) {
            return false
        }

        val palletLeft = palletTrack.lastCenterX - palletTrack.lastWidth / 2f
        val palletTop = palletTrack.lastCenterY - palletTrack.lastHeight / 2f
        val palletRight = palletTrack.lastCenterX + palletTrack.lastWidth / 2f
        val palletBottom = palletTrack.lastCenterY + palletTrack.lastHeight / 2f

        val cargoLeft = track.lastCenterX - track.lastWidth / 2f
        val cargoRight = track.lastCenterX + track.lastWidth / 2f
        val cargoBottom = track.lastCenterY + track.lastHeight / 2f

        val horizontalPadding = palletTrack.lastWidth * tuning.palletReference.zoneHorizontalPaddingRatio
        val zoneLeft = palletLeft - horizontalPadding
        val zoneRight = palletRight + horizontalPadding
        val overlapWidth = overlapLength(cargoLeft, cargoRight, zoneLeft, zoneRight)
        val overlapRatio = overlapWidth / max(track.lastWidth, 1f)
        val centerInside = track.lastCenterX in zoneLeft..zoneRight
        val supportMin = palletTop - max(
            track.lastHeight * tuning.palletReference.zoneHorizontalPaddingRatio,
            palletTrack.lastHeight * tuning.palletReference.zoneSupportAboveRatio,
        )
        val supportMax = palletBottom + palletTrack.lastHeight * tuning.palletReference.zoneSupportBelowRatio
        val bottomSupported = cargoBottom in supportMin..supportMax
        val notOversized = track.lastWidth <= palletTrack.lastWidth * tuning.palletReference.zoneItemMaxWidthRatio &&
            track.lastHeight <= palletTrack.lastHeight * tuning.palletReference.zoneItemMaxHeightRatio

        return overlapRatio >= tuning.palletReference.zoneMinOverlapRatio &&
            centerInside &&
            bottomSupported &&
            notOversized
    }

    private fun scoreSpatialMatch(
        track: TrackMemory,
        detection: LiveRecognition,
    ): Float? {
        val centerDistance = hypot(
            detection.centerX - track.lastCenterX,
            detection.centerY - track.lastCenterY,
        )
        val maxDistance = max(
            max(track.lastWidth, track.lastHeight),
            max(detection.width, detection.height),
        ) * 0.95f + 72f
        if (centerDistance > maxDistance) {
            return null
        }

        val widthRatio = dimensionRatio(track.lastWidth, detection.width)
        val heightRatio = dimensionRatio(track.lastHeight, detection.height)
        if (widthRatio > 2.2f || heightRatio > 2.2f) {
            return null
        }

        val labelPenalty = when {
            detection.label == track.bestKnownLabel() -> 0f
            detection.category == track.lastCategory -> 0.08f
            detection.label in GENERIC_LABELS || track.bestKnownLabel() in GENERIC_LABELS -> 0.16f
            else -> 0.32f
        }
        return centerDistance / maxDistance +
            (widthRatio - 1f) * 0.35f +
            (heightRatio - 1f) * 0.35f +
            labelPenalty
    }

    private fun isAlreadyCounted(
        track: TrackMemory,
        detection: LiveRecognition,
        analyzedAt: Long,
    ): Boolean {
        if (countedTrackKeys.contains(track.key)) {
            return true
        }
        return countedEvidence.any { evidence ->
            evidence.matches(
                label = track.bestKnownLabel(),
                centerX = detection.centerX,
                centerY = detection.centerY,
                width = detection.width,
                height = detection.height,
                observedAt = analyzedAt,
                ttlMs = countedEvidenceTtlMs,
            )
        }
    }

    private fun CountedEvidence.matches(
        label: String,
        centerX: Float,
        centerY: Float,
        width: Float,
        height: Float,
        observedAt: Long,
        ttlMs: Long,
    ): Boolean {
        if (observedAt - countedAt > ttlMs) {
            return false
        }
        val labelCompatible = this.label == label || this.label in GENERIC_LABELS || label in GENERIC_LABELS
        if (!labelCompatible) {
            return false
        }
        val distance = hypot(centerX - this.centerX, centerY - this.centerY)
        val maxDistance = max(max(this.width, this.height), max(width, height)) * 0.85f + 48f
        val widthRatio = dimensionRatio(this.width, width)
        val heightRatio = dimensionRatio(this.height, height)
        return distance <= maxDistance &&
            widthRatio <= 1.9f &&
            heightRatio <= 1.9f
    }

    private fun addLabelVote(
        track: TrackMemory,
        label: String,
        weight: Int,
    ) {
        val normalized = label.trim()
        if (normalized.isBlank() || normalized == "Unknown") {
            return
        }
        track.labelVotes[normalized] = (track.labelVotes[normalized] ?: 0) + weight
    }

    private fun pruneStaleState(now: Long) {
        countedEvidence.removeAll { now - it.countedAt > countedEvidenceTtlMs }
        val staleKeys = tracksByKey.values
            .filter { now - it.lastSeenAt > maxTrackGapMs * 2 }
            .map { it.key }
        staleKeys.forEach { key ->
            tracksByKey.remove(key)?.trackingId?.let(trackIdToKey::remove)
        }
    }

    private fun dimensionRatio(first: Float, second: Float): Float {
        val a = max(first, 1f)
        val b = max(second, 1f)
        return max(a, b) / max(minOf(a, b), 1f)
    }

    private fun overlapLength(
        leftA: Float,
        rightA: Float,
        leftB: Float,
        rightB: Float,
    ): Float {
        return max(0f, minOf(rightA, rightB) - maxOf(leftA, leftB))
    }

    companion object {
        const val DEFAULT_MIN_STABLE_FRAMES = 3
        const val DEFAULT_MAX_TRACK_GAP_MS = 1_500L
        const val DEFAULT_COUNTED_EVIDENCE_TTL_MS = 20_000L

        private val GENERIC_LABELS = setOf(
            "Tracked cargo",
            "Pallet candidate",
            "Unknown",
            "Wood pallet base",
        )

        private fun labelQualityScore(label: String): Int {
            val normalized = label.lowercase()
            return when {
                normalized.contains("pallet") -> 1
                normalized.contains("cargo") -> 0
                normalized.length <= 3 -> 0
                else -> 2
            }
        }
    }
}
