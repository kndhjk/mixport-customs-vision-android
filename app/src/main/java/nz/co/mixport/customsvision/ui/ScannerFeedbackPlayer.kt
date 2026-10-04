package nz.co.mixport.customsvision.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.media.ToneGenerator
import nz.co.mixport.customsvision.R
import nz.co.mixport.customsvision.data.ScannerMatchStatus
import java.util.concurrent.ConcurrentHashMap

internal enum class ScannerFeedbackSound {
    CLEAR,
    HOLD,
    FAILED,
    NONE,
}

internal fun scannerFeedbackSound(
    matchStatus: ScannerMatchStatus,
    nzcsStatus: String?,
    mpiStatus: String?,
): ScannerFeedbackSound {
    return when (matchStatus) {
        ScannerMatchStatus.MATCHED -> when (overallScannerClearanceStatus(nzcsStatus, mpiStatus)) {
            "CLEAR" -> ScannerFeedbackSound.CLEAR
            "FAILED" -> ScannerFeedbackSound.FAILED
            else -> ScannerFeedbackSound.HOLD
        }

        ScannerMatchStatus.MISMATCH,
        ScannerMatchStatus.ERROR,
        -> ScannerFeedbackSound.FAILED

        ScannerMatchStatus.WAITING -> ScannerFeedbackSound.NONE
    }
}

internal class ScannerFeedbackPlayer(context: Context) : AutoCloseable {
    private val soundPool = SoundPool.Builder()
        .setMaxStreams(1)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
    private val toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
    private val loadedSounds = ConcurrentHashMap.newKeySet<Int>()
    private val clearSoundId: Int
    private val failedSoundId: Int
    private var activeStreamId = 0

    init {
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) {
                loadedSounds += sampleId
            }
        }
        clearSoundId = soundPool.load(context, R.raw.scanner_clear, 1)
        failedSoundId = soundPool.load(context, R.raw.scanner_failed, 1)
    }

    fun play(sound: ScannerFeedbackSound) {
        stop()
        when (sound) {
            ScannerFeedbackSound.CLEAR -> playSampleOrTone(
                sampleId = clearSoundId,
                volume = 1f,
                fallbackTone = ToneGenerator.TONE_PROP_ACK,
                fallbackDurationMs = 180,
            )

            ScannerFeedbackSound.HOLD -> toneGenerator.startTone(
                ToneGenerator.TONE_PROP_BEEP2,
                220,
            )

            ScannerFeedbackSound.FAILED -> playSampleOrTone(
                sampleId = failedSoundId,
                volume = 1f,
                fallbackTone = ToneGenerator.TONE_PROP_NACK,
                fallbackDurationMs = 280,
            )

            ScannerFeedbackSound.NONE -> Unit
        }
    }

    private fun playSampleOrTone(
        sampleId: Int,
        volume: Float,
        fallbackTone: Int,
        fallbackDurationMs: Int,
    ) {
        if (sampleId in loadedSounds) {
            activeStreamId = soundPool.play(sampleId, volume, volume, 1, 0, 1f)
            if (activeStreamId != 0) return
        }
        toneGenerator.startTone(fallbackTone, fallbackDurationMs)
    }

    private fun stop() {
        if (activeStreamId != 0) {
            soundPool.stop(activeStreamId)
            activeStreamId = 0
        }
        toneGenerator.stopTone()
    }

    override fun close() {
        stop()
        soundPool.release()
        toneGenerator.release()
    }
}
