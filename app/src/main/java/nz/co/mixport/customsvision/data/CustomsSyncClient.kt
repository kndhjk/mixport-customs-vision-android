package nz.co.mixport.customsvision.data

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.time.Instant

data class ScannerBootstrapRow(
    val barcodeKey: String,
    val cargoTrackingId: Long,
    val parentHblNo: String,
    val matchedChildHbl: String,
    val matchedBarcodeCode: String,
    val matchedBy: String,
    val childHbls: String,
    val barcodeCodes: String,
    val status: String,
    val customersStatus: String,
    val mpiStatus: String,
    val location: String,
    val pkgs: Int?,
    val outTurnQty: Int?,
    val submissionDate: String,
    val containerNo: String,
    val vesselName: String,
    val company: String,
    val customerName: String,
    val updatedCursor: String,
    val serverScanCount: Int,
    val serverMatchedScanCount: Int,
    val serverMismatchScanCount: Int,
    val serverErrorScanCount: Int,
    val serverLastScannedAt: String,
    val serverLastMatchStatus: String,
)

data class ScannerBootstrapPayload(
    val rows: List<ScannerBootstrapRow>,
    val syncedAt: Long,
    val cursor: String,
)

data class ScannerUploadResponse(
    val batchId: Long,
    val uploadedCount: Int,
    val uploadedAt: Long,
)

class CustomsSyncClient {
    fun fetchScannerBootstrap(
        settings: ScannerSyncSettings,
        sinceCursor: String? = null,
        limit: Int = DEFAULT_BOOTSTRAP_PAGE_SIZE,
    ): ScannerBootstrapPayload {
        val normalizedLimit = limit.coerceIn(1, MAX_BOOTSTRAP_PAGE_SIZE)
        val relativePath = buildString {
            append("scanner-sync/bootstrap?limit=")
            append(normalizedLimit)
            sinceCursor?.trim()?.takeIf(String::isNotEmpty)?.let { cursor ->
                append("&since=")
                append(Uri.encode(cursor))
            }
        }
        val response = requestJson(
            method = "GET",
            url = endpointUrl(settings.apiBaseUrl, relativePath),
            bearerToken = settings.bearerToken,
        )
        val rowsJson = response.optJSONArray("rows") ?: JSONArray()
        val rows = buildList {
            for (index in 0 until rowsJson.length()) {
                val item = rowsJson.optJSONObject(index) ?: continue
                add(
                    ScannerBootstrapRow(
                        barcodeKey = normalizeScannerBarcode(item.optString("barcode_key")),
                        cargoTrackingId = item.optLong("cargo_tracking_id"),
                        parentHblNo = item.optString("parent_hbl_no"),
                        matchedChildHbl = item.optString("matched_child_hbl"),
                        matchedBarcodeCode = item.optString("matched_barcode_code"),
                        matchedBy = item.optString("matched_by"),
                        childHbls = item.optString("child_hbls"),
                        barcodeCodes = item.optString("barcode_codes"),
                        status = item.optString("status"),
                        customersStatus = item.optString("customers_status"),
                        mpiStatus = item.optString("mpi_status"),
                        location = item.optString("location"),
                        pkgs = item.optInt("pkgs").takeIf { item.has("pkgs") && !item.isNull("pkgs") },
                        outTurnQty = item.optInt("out_turn_qty").takeIf {
                            item.has("out_turn_qty") && !item.isNull("out_turn_qty")
                        },
                        submissionDate = item.optString("submission_date"),
                        containerNo = item.optString("container_no"),
                        vesselName = item.optString("vessel_name"),
                        company = item.optString("company"),
                        customerName = item.optString("customer_name"),
                        updatedCursor = item.optString("updated_cursor"),
                        serverScanCount = item.optInt("server_scan_count"),
                        serverMatchedScanCount = item.optInt("server_matched_scan_count"),
                        serverMismatchScanCount = item.optInt("server_mismatch_scan_count"),
                        serverErrorScanCount = item.optInt("server_error_scan_count"),
                        serverLastScannedAt = item.optString("server_last_scanned_at"),
                        serverLastMatchStatus = item.optString("server_last_match_status"),
                    ),
                )
            }
        }
        return ScannerBootstrapPayload(
            rows = rows,
            syncedAt = parseServerTimeMillis(response.optString("synced_at")) ?: System.currentTimeMillis(),
            cursor = response.optString("cursor"),
        )
    }

    fun verifyBarcode(settings: ScannerSyncSettings, barcode: String): BarcodeLookupResult? {
        val payload = JSONObject().apply {
            put("barcode", normalizeScannerBarcode(barcode))
        }
        val response = requestJson(
            method = "POST",
            url = endpointUrl(settings.apiBaseUrl, "barcode/verify"),
            bearerToken = settings.bearerToken,
            body = payload,
        )
        if (!response.optBoolean("ok") || !response.optBoolean("found")) {
            return null
        }
        val data = response.optJSONObject("data") ?: return null
        return BarcodeLookupResult(
            found = true,
            databaseRecord = data.optString("parent_hbl_no").ifBlank { data.optString("barcode") },
            status = data.optString("status"),
            source = "SERVER_LIVE",
            cargoTrackingId = data.optLong("id").takeIf { data.has("id") && !data.isNull("id") },
            parentHblNo = data.optString("parent_hbl_no"),
            matchedChildHbl = data.optString("matched_child_hbl").ifBlank { null },
            matchedBarcodeCode = data.optString("matched_barcode_code").ifBlank { null },
            matchedBy = data.optString("matched_by").ifBlank { null },
            childHbls = data.optString("child_hbls").ifBlank { null },
            barcodeCodes = data.optString("barcode_codes").ifBlank { null },
            containerNo = data.optString("container_no").ifBlank { null },
            vesselName = data.optString("vessel_name").ifBlank { null },
            company = data.optString("company").ifBlank { null },
            customerName = data.optString("customer_name").ifBlank { null },
            location = data.optString("location").ifBlank { null },
            pkgs = data.optInt("pkgs").takeIf { data.has("pkgs") && !data.isNull("pkgs") },
            outTurnQty = data.optInt("out_turn_qty").takeIf { data.has("out_turn_qty") && !data.isNull("out_turn_qty") },
            submissionDate = data.optString("submission_date").ifBlank { null },
            customersStatus = data.optString("customers_status").ifBlank { null },
            mpiStatus = data.optString("mpi_status").ifBlank { null },
        )
    }

    fun uploadScannerRecords(
        settings: ScannerSyncSettings,
        operatorName: String,
        workflowMode: String,
        records: List<PendingScannerUploadRecord>,
    ): ScannerUploadResponse {
        val payload = JSONObject().apply {
            put("deviceId", settings.deviceId.trim())
            put("operatorName", operatorName.trim())
            put("workflowMode", workflowMode.trim())
            put("uploadedAt", Instant.now().toString())
            put("records", JSONArray().apply {
                records.forEach { record ->
                    put(
                        JSONObject().apply {
                            put("localId", record.id)
                            put("scannedBarcode", record.scannedBarcode)
                            put("databaseRecord", record.databaseRecord)
                            put("matchStatus", record.matchStatus.name)
                            put("status", record.status)
                            put("source", record.source)
                            put("scannedAt", Instant.ofEpochMilli(record.scannedAt).toString())
                            put("cargoTrackingId", record.cargoTrackingId)
                            put("parentHblNo", record.parentHblNo)
                            put("matchedChildHbl", record.matchedChildHbl)
                            put("matchedBy", record.matchedBy)
                            put("childHbls", record.childHbls)
                            put("containerNo", record.containerNo)
                            put("vesselName", record.vesselName)
                            put("company", record.company)
                            put("customerName", record.customerName)
                            put("location", record.location)
                        },
                    )
                }
            })
        }
        val response = requestJson(
            method = "POST",
            url = endpointUrl(settings.apiBaseUrl, "scanner-sync/upload"),
            bearerToken = settings.bearerToken,
            body = payload,
        )
        return ScannerUploadResponse(
            batchId = response.optLong("batch_id"),
            uploadedCount = response.optInt("uploaded_count"),
            uploadedAt = parseServerTimeMillis(response.optString("uploaded_at")) ?: System.currentTimeMillis(),
        )
    }

    private fun endpointUrl(baseUrl: String, relativePath: String): String {
        val normalizedBase = baseUrl.trim().ifBlank { throw IllegalArgumentException("API base URL is required.") }
        val parsed = Uri.parse(if (normalizedBase.endsWith("/")) normalizedBase else "$normalizedBase/")
        val base = parsed.toString()
        return if (relativePath.startsWith("/")) {
            base + relativePath.removePrefix("/")
        } else {
            base + relativePath
        }
    }

    private fun requestJson(
        method: String,
        url: String,
        bearerToken: String,
        body: JSONObject? = null,
    ): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10000
            readTimeout = 15000
            doInput = true
            setRequestProperty("Accept", "application/json")
            if (bearerToken.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer ${bearerToken.trim()}")
            }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }

        try {
            if (body != null) {
                connection.outputStream.use { stream ->
                    stream.write(body.toString().toByteArray(StandardCharsets.UTF_8))
                }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.use { input ->
                BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).readText()
            }.orEmpty()
            val json = if (responseText.isBlank()) JSONObject() else JSONObject(responseText)
            if (code !in 200..299 || !json.optBoolean("ok", code in 200..299)) {
                throw IllegalStateException(json.optString("error").ifBlank { "HTTP $code" })
            }
            return json
        } finally {
            connection.disconnect()
        }
    }

    private fun parseServerTimeMillis(value: String?): Long? {
        val candidate = value?.trim().orEmpty()
        if (candidate.isBlank()) {
            return null
        }
        return runCatching { Instant.parse(candidate).toEpochMilli() }.getOrNull()
    }

    companion object {
        const val DEFAULT_BOOTSTRAP_PAGE_SIZE: Int = 5000
        const val MAX_BOOTSTRAP_PAGE_SIZE: Int = 5000
    }
}
