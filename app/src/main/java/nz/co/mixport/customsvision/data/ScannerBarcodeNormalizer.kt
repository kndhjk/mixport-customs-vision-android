package nz.co.mixport.customsvision.data

private const val MIN_SCANNER_BARCODE_LENGTH = 4
private const val MAX_SCANNER_BARCODE_LENGTH = 64

fun normalizeScannerBarcode(rawValue: String): String {
    NativeScannerBridge.normalizeBarcodeOrNull(rawValue)?.let { return it }
    return normalizeScannerBarcodeFallback(rawValue)
}

fun isUsableScannerBarcode(value: String): Boolean {
    return value.length in MIN_SCANNER_BARCODE_LENGTH..MAX_SCANNER_BARCODE_LENGTH
}

internal fun normalizeScannerBarcodeFallback(rawValue: String): String {
    val compact = buildString(rawValue.length) {
        rawValue.forEach { character ->
            if (character != '\u0000' && !character.isWhitespace()) {
                append(character)
            }
        }
    }

    val withoutCode39Guards = if (compact.length > 2 && compact.first() == '*' && compact.last() == '*') {
        compact.substring(1, compact.length - 1)
    } else {
        compact
    }

    return withoutCode39Guards.uppercase()
}
