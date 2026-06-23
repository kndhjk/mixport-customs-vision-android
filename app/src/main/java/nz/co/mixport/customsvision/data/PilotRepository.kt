package nz.co.mixport.customsvision.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PilotRepository(
    private val databaseHelper: CustomsDatabaseHelper,
) {
    suspend fun createSession(draft: SessionDraft): InspectionSessionRecord = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val session = databaseHelper.createSession(draft, startedAt)
        databaseHelper.insertEvent(
            sessionId = session.id,
            palletId = null,
            eventType = "SESSION_STARTED",
            message = "Session started for container ${draft.containerCode}.",
            payloadJson = """{"containerCode":"${draft.containerCode}","operator":"${draft.operatorName}"}""",
            createdAt = startedAt,
        )
        session
    }

    suspend fun listSessions(): List<InspectionSessionRecord> = withContext(Dispatchers.IO) {
        databaseHelper.listSessions()
    }

    suspend fun getSession(sessionId: Long): InspectionSessionRecord? = withContext(Dispatchers.IO) {
        databaseHelper.getSession(sessionId)
    }

    suspend fun openPallet(sessionId: Long, sequenceNumber: Int, startedAt: Long): PalletRecord =
        withContext(Dispatchers.IO) {
            databaseHelper.createPallet(sessionId, sequenceNumber, startedAt)
        }

    suspend fun listPalletDetails(sessionId: Long): List<PalletDetail> = withContext(Dispatchers.IO) {
        databaseHelper.listPallets(sessionId).map { pallet ->
            PalletDetail(
                pallet = pallet,
                items = databaseHelper.getPalletItems(pallet.id),
            )
        }
    }

    suspend fun listPalletItems(palletId: Long): List<CargoSummaryRecord> = withContext(Dispatchers.IO) {
        databaseHelper.getPalletItems(palletId)
    }

    suspend fun incrementPalletItem(
        palletId: Long,
        itemLabel: String,
        colorName: String,
        markerText: String,
        delta: Int,
    ): CargoSummaryRecord = withContext(Dispatchers.IO) {
        databaseHelper.incrementPalletItem(palletId, itemLabel, colorName, markerText, delta)
    }

    suspend fun closePallet(
        palletId: Long,
        closedAt: Long,
        wrapDetected: Boolean,
        containerEmptyAtClose: Boolean,
    ): PalletRecord = withContext(Dispatchers.IO) {
        databaseHelper.closePallet(palletId, closedAt, wrapDetected, containerEmptyAtClose)
    }

    suspend fun insertEvent(
        sessionId: Long,
        palletId: Long?,
        eventType: String,
        message: String,
        payloadJson: String,
        createdAt: Long,
    ): EventLogRecord = withContext(Dispatchers.IO) {
        databaseHelper.insertEvent(sessionId, palletId, eventType, message, payloadJson, createdAt)
    }

    suspend fun listEvents(sessionId: Long, limit: Int = 20): List<EventLogRecord> =
        withContext(Dispatchers.IO) {
            databaseHelper.listEvents(sessionId, limit)
        }

    suspend fun updateContainerFlag(sessionId: Long, hasRemainingCargo: Boolean) =
        withContext(Dispatchers.IO) {
            val session = databaseHelper.getSession(sessionId) ?: return@withContext
            databaseHelper.updateSessionStatus(
                sessionId = session.id,
                status = session.status,
                recordingUri = session.recordingUri,
                containerHasRemainingCargo = hasRemainingCargo,
            )
        }

    suspend fun markReadyToComplete(sessionId: Long) = withContext(Dispatchers.IO) {
        val session = databaseHelper.getSession(sessionId) ?: return@withContext
        databaseHelper.updateSessionStatus(
            sessionId = session.id,
            status = "READY_TO_COMPLETE",
            recordingUri = session.recordingUri,
            containerHasRemainingCargo = false,
        )
    }

    suspend fun updateRecordingUri(sessionId: Long, recordingUri: String) = withContext(Dispatchers.IO) {
        val session = databaseHelper.getSession(sessionId) ?: return@withContext
        databaseHelper.updateSessionStatus(
            sessionId = session.id,
            status = session.status,
            recordingUri = recordingUri,
            containerHasRemainingCargo = session.containerHasRemainingCargo,
        )
    }

    suspend fun finishSession(sessionId: Long, isComplete: Boolean) = withContext(Dispatchers.IO) {
        val status = if (isComplete) "COMPLETED" else "PAUSED"
        val endedAt = System.currentTimeMillis()
        val session = databaseHelper.getSession(sessionId) ?: return@withContext
        databaseHelper.updateSessionStatus(
            sessionId = session.id,
            status = status,
            endedAt = endedAt,
            recordingUri = session.recordingUri,
            containerHasRemainingCargo = session.containerHasRemainingCargo,
        )
        databaseHelper.insertEvent(
            sessionId = session.id,
            palletId = null,
            eventType = "SESSION_FINISHED",
            message = "Session closed with status $status.",
            payloadJson = """{"status":"$status"}""",
            createdAt = endedAt,
        )
    }
}

