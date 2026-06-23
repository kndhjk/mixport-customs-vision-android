package nz.co.mixport.customsvision.data

data class SessionDraft(
    val containerCode: String = "",
    val vesselName: String = "",
    val operatorName: String = "",
    val notes: String = "",
)

data class InspectionSessionRecord(
    val id: Long,
    val containerCode: String,
    val vesselName: String,
    val operatorName: String,
    val notes: String,
    val status: String,
    val startedAt: Long,
    val endedAt: Long?,
    val recordingUri: String?,
    val containerHasRemainingCargo: Boolean,
)

data class PalletRecord(
    val id: Long,
    val sessionId: Long,
    val sequenceNumber: Int,
    val status: String,
    val startedAt: Long,
    val closedAt: Long?,
    val wrapDetected: Boolean,
    val containerEmptyAtClose: Boolean,
)

data class CargoSummaryRecord(
    val id: Long,
    val palletId: Long,
    val itemLabel: String,
    val colorName: String,
    val markerText: String,
    val quantity: Int,
)

data class EventLogRecord(
    val id: Long,
    val sessionId: Long,
    val palletId: Long?,
    val eventType: String,
    val message: String,
    val payloadJson: String,
    val createdAt: Long,
)

data class PalletDetail(
    val pallet: PalletRecord,
    val items: List<CargoSummaryRecord>,
)

