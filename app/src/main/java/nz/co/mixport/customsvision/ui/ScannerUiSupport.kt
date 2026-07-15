package nz.co.mixport.customsvision.ui

import nz.co.mixport.customsvision.data.AppLanguage
import nz.co.mixport.customsvision.data.BarcodeLookupResult
import nz.co.mixport.customsvision.data.ScannerAutoUploadResult
import nz.co.mixport.customsvision.data.ScannerAutoUploadState
import nz.co.mixport.customsvision.data.ScannerMatchStatus
import nz.co.mixport.customsvision.data.ScannerRecord
import nz.co.mixport.customsvision.data.isUsableScannerBarcode

internal const val SCANNER_REFERENCE_REFRESH_INTERVAL_MS = 15_000L
internal const val SCANNER_REFERENCE_FORCE_REFRESH_DEBOUNCE_MS = 4_000L
internal const val SCANNER_SOURCE_LOCAL = "LOCAL"
internal const val SCANNER_RECORD_NOT_FOUND = "NOT_FOUND"
internal const val SCANNER_RECORD_ERROR = "ERROR"
internal const val SCANNER_STATUS_UNKNOWN = "UNKNOWN"
internal const val SCANNER_STATUS_ERROR = "ERROR"
internal const val SCANNER_STATUS_INVALID_FORMAT = "INVALID_BARCODE_FORMAT"

internal fun buildScannerRecord(
    barcode: String,
    lookupResult: BarcodeLookupResult?,
    scannedAt: Long,
): ScannerRecord {
    return when {
        !isUsableScannerBarcode(barcode) -> {
            ScannerRecord(
                scannedBarcode = barcode,
                databaseRecord = SCANNER_RECORD_ERROR,
                matchStatus = ScannerMatchStatus.ERROR,
                status = SCANNER_STATUS_INVALID_FORMAT,
                source = SCANNER_SOURCE_LOCAL,
                scannedAt = scannedAt,
            )
        }

        lookupResult != null && lookupResult.found -> {
            ScannerRecord(
                scannedBarcode = barcode,
                databaseRecord = lookupResult.databaseRecord,
                matchStatus = ScannerMatchStatus.MATCHED,
                status = lookupResult.status,
                source = lookupResult.source,
                scannedAt = scannedAt,
                customersStatus = lookupResult.customersStatus,
                mpiStatus = lookupResult.mpiStatus,
            )
        }

        else -> {
            ScannerRecord(
                scannedBarcode = barcode,
                databaseRecord = SCANNER_RECORD_NOT_FOUND,
                matchStatus = ScannerMatchStatus.MISMATCH,
                status = SCANNER_STATUS_UNKNOWN,
                source = SCANNER_SOURCE_LOCAL,
                scannedAt = scannedAt,
            )
        }
    }
}

internal fun shouldRefreshScannerReferenceCache(
    isConfigured: Boolean,
    isRefreshing: Boolean,
    isUploading: Boolean,
    referenceCount: Int,
    lastReferenceSyncAt: Long?,
    now: Long,
    force: Boolean = false,
): Boolean {
    if (!isConfigured || isRefreshing || isUploading) {
        return false
    }
    if (referenceCount <= 0) {
        return true
    }
    val elapsed = now - (lastReferenceSyncAt ?: 0L)
    return if (force) {
        elapsed >= SCANNER_REFERENCE_FORCE_REFRESH_DEBOUNCE_MS
    } else {
        elapsed >= SCANNER_REFERENCE_REFRESH_INTERVAL_MS
    }
}

internal fun scannerMessageFor(
    record: ScannerRecord,
    language: AppLanguage,
): String {
    return when (record.matchStatus) {
        ScannerMatchStatus.MATCHED -> scannerMatchedMessage(record, language)

        ScannerMatchStatus.MISMATCH -> language.pick(
            "\"${record.scannedBarcode}\" was not found.",
            "数据库里没有找到序列号 ${record.scannedBarcode}。",
        )

        ScannerMatchStatus.ERROR -> language.pick(
            "\"${record.scannedBarcode}\" could not be verified.",
            "序列号 ${record.scannedBarcode} 无法完成校验。",
        )

        ScannerMatchStatus.WAITING -> waitingScannerMessage(language)
    }
}

internal fun scannerAutoUploadMessage(
    result: ScannerAutoUploadResult,
    language: AppLanguage,
): String {
    return when (result.state) {
        ScannerAutoUploadState.UPLOADED -> language.pick(
            "Network available. Pending scanner results were uploaded automatically.",
            "当前网络可用，本次扫码已自动上传。",
        )

        ScannerAutoUploadState.CACHED_OFFLINE -> language.pick(
            "No network connection. The scan was saved locally and queued for later upload.",
            "当前无网络，扫码结果已缓存到本地，稍后再上传。",
        )

        ScannerAutoUploadState.CACHED_UNCONFIGURED -> language.pick(
            "Online upload is unavailable on this device. The scan was saved locally.",
            "服务器同步未配置，扫码结果已先保存到本地。",
        )

        ScannerAutoUploadState.CACHED_UPLOAD_FAILED -> {
            val detail = result.errorMessage?.takeIf(String::isNotBlank)
            if (detail == null) {
                language.pick(
                    "The upload failed, so the scan was kept in the local queue.",
                    "自动上传失败，扫码结果已保留在本地待上传队列中。",
                )
            } else {
                language.pick(
                    "The upload failed, so the scan was kept locally: $detail",
                    "自动上传失败，扫码结果已保留在本地：$detail",
                )
            }
        }
    }
}

internal fun waitingScannerMessage(language: AppLanguage): String {
    return language.pick(
        "Waiting for barcode input.",
        "等待扫描序列号。",
    )
}

internal fun privateSyncMissingMessage(language: AppLanguage): String {
    return language.pick(
        "Online cargo updates are unavailable on this device.",
        "当前构建未预置私有同步配置。",
    )
}

private fun scannerMatchedMessage(
    record: ScannerRecord,
    language: AppLanguage,
): String {
    val nzcsStatus = localizedStatus(
        language,
        canonicalScannerClearanceStatus(record.customersStatus),
    )
    val mpiStatus = localizedStatus(
        language,
        canonicalScannerClearanceStatus(record.mpiStatus),
    )
    return when (overallScannerClearanceStatus(record.customersStatus, record.mpiStatus)) {
        "CLEAR" -> language.pick(
            "\"${record.scannedBarcode}\" matched and is clear. NZCS $nzcsStatus | MPI $mpiStatus.",
            "序列号 ${record.scannedBarcode} 已匹配并已放行。NZCS ${nzcsStatus} | MPI ${mpiStatus}。",
        )

        "FAILED" -> language.pick(
            "\"${record.scannedBarcode}\" matched, but clearance failed. NZCS $nzcsStatus | MPI $mpiStatus.",
            "序列号 ${record.scannedBarcode} 已匹配，但清关未通过。NZCS ${nzcsStatus} | MPI ${mpiStatus}。",
        )

        else -> language.pick(
            "\"${record.scannedBarcode}\" matched, but clearance is on hold. NZCS $nzcsStatus | MPI $mpiStatus.",
            "序列号 ${record.scannedBarcode} 已匹配，但当前仍为待处理。NZCS ${nzcsStatus} | MPI ${mpiStatus}。",
        )
    }
}
