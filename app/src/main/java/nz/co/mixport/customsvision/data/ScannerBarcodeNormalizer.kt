package nz.co.mixport.customsvision.data

private const val MIN_SCANNER_BARCODE_LENGTH = 4
private const val MAX_SCANNER_BARCODE_LENGTH = 64

fun normalizeScannerBarcode(rawValue: String): String {
    val trimmed = rawValue
        .trim()
        .replace("\u0000", "")
        .replace(Regex("\\s+"), "")

    val withoutCode39Guards = if (trimmed.length > 2 && trimmed.startsWith("*") && trimmed.endsWith("*")) {
        trimmed.substring(1, trimmed.length - 1)
    } else {
        trimmed
    }

    return withoutCode39Guards.uppercase()
}

fun isUsableScannerBarcode(value: String): Boolean {
    return value.length in MIN_SCANNER_BARCODE_LENGTH..MAX_SCANNER_BARCODE_LENGTH
}
