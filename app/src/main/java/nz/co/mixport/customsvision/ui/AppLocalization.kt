package nz.co.mixport.customsvision.ui

import nz.co.mixport.customsvision.data.AppLanguage
import nz.co.mixport.customsvision.data.ScannerMatchStatus
import nz.co.mixport.customsvision.nativebridge.ScannerNativeBridge

fun AppLanguage.pick(english: String, chinese: String): String {
    if (this != AppLanguage.CHINESE) {
        return english
    }
    return if (looksLikeMojibake(chinese)) english else chinese
}

fun localizedCargoLabel(language: AppLanguage, label: String): String {
    val normalized = label.trim()
    return when (normalized) {
        "Wood pallet base" -> language.pick("Wood pallet base", "木托盘底座")
        "Pallet candidate" -> language.pick("Pallet candidate", "托盘候选")
        "Tracked cargo" -> language.pick("Tracked cargo", "跟踪货物")
        "Electric kettle" -> language.pick("Electric kettle", "烧水壶")
        "Cup / mug" -> language.pick("Cup / mug", "杯子 / 马克杯")
        "Bowl" -> language.pick("Bowl", "碗")
        "Chopsticks / cutlery" -> language.pick("Chopsticks / cutlery", "筷子 / 餐具")
        "Pen / marker" -> language.pick("Pen / marker", "笔 / 记号笔")
        "Computer" -> language.pick("Computer", "电脑")
        "Laptop" -> language.pick("Laptop", "笔记本电脑")
        "Notebook / book" -> language.pick("Notebook / book", "笔记本 / 书本")
        "Carton / box" -> language.pick("Carton / box", "箱子 / 纸箱")
        "Bottle" -> language.pick("Bottle", "瓶子")
        "Plate" -> language.pick("Plate", "盘子")
        "Keyboard" -> language.pick("Keyboard", "键盘")
        "Spoon / fork" -> language.pick("Spoon / fork", "勺子 / 叉子")
        "Pot / pan" -> language.pick("Pot / pan", "锅 / 平底锅")
        "Mouse / pointer" -> language.pick("Mouse / pointer", "鼠标")
        "Phone / tablet" -> language.pick("Phone / tablet", "手机 / 平板")
        "Printer" -> language.pick("Printer", "打印机")
        "Cable / charger" -> language.pick("Cable / charger", "线材 / 充电器")
        "Speaker / audio" -> language.pick("Speaker / audio", "音箱 / 音频设备")
        "Bag / backpack" -> language.pick("Bag / backpack", "包 / 背包")
        "Suitcase / luggage" -> language.pick("Suitcase / luggage", "行李箱")
        "Chair / stool" -> language.pick("Chair / stool", "椅子 / 凳子")
        "Lamp / lighting" -> language.pick("Lamp / lighting", "灯具")
        "Fan / appliance" -> language.pick("Fan / appliance", "风扇 / 电器")
        "Rice cooker / appliance" -> language.pick("Rice cooker / appliance", "电饭煲 / 小家电")
        "Clothing / fabric" -> language.pick("Clothing / fabric", "衣物 / 布料")
        "Toy / gift" -> language.pick("Toy / gift", "玩具 / 礼品")
        "Helmet / safety gear" -> language.pick("Helmet / safety gear", "头盔 / 安全用品")
        "Umbrella" -> language.pick("Umbrella", "雨伞")
        "Tissue / paper goods" -> language.pick("Tissue / paper goods", "纸巾 / 纸品")
        "Detergent / household liquids" -> language.pick("Detergent / household liquids", "清洁剂 / 家用液体")
        else -> normalized
    }
}

fun localizedStatus(language: AppLanguage, status: String): String {
    return when (status.trim().uppercase()) {
        "ACTIVE" -> language.pick("Active", "进行中")
        "READY_TO_COMPLETE" -> language.pick("Ready to complete", "可完成")
        "COMPLETED" -> language.pick("Completed", "已完成")
        "PAUSED" -> language.pick("Paused", "已暂停")
        "LOADING" -> language.pick("Loading", "装载中")
        "SEALED" -> language.pick("Sealed", "已封膜")
        "AVAILABLE" -> language.pick("Available", "可用")
        "UNAVAILABLE" -> language.pick("Unavailable", "不可用")
        "UPLIFTED" -> language.pick("Uplifted", "已提货")
        "HOLD" -> language.pick("Hold", "待处理")
        "FAILED" -> language.pick("Failed", "失败")
        "CLEAR" -> language.pick("Clear", "放行")
        else -> status
    }
}

fun canonicalScannerClearanceStatus(status: String?): String {
    return ScannerNativeBridge.canonicalScannerClearanceStatus(status)
        ?: canonicalScannerClearanceStatusFallback(status)
}

internal fun canonicalScannerClearanceStatusFallback(status: String?): String {
    val normalized = status
        .orEmpty()
        .trim()
        .uppercase()
        .replace("-", "")
        .replace("_", "")
        .replace(" ", "")
    return when {
        normalized.isBlank() -> "HOLD"
        normalized in setOf("CLEAR", "PASS", "PASSED", "RELEASED", "已通过", "放行") -> "CLEAR"
        normalized in setOf("FAILED", "FAIL", "REJECTED", "未通过", "失败") -> "FAILED"
        normalized in setOf("HOLD", "ONHOLD", "PENDING", "WAITING", "待处理", "暂扣") -> "HOLD"
        else -> "HOLD"
    }
}

fun overallScannerClearanceStatus(
    nzcsStatus: String?,
    mpiStatus: String?,
): String {
    return ScannerNativeBridge.overallScannerClearanceStatus(nzcsStatus, mpiStatus)
        ?: overallScannerClearanceStatusFallback(nzcsStatus, mpiStatus)
}

internal fun overallScannerClearanceStatusFallback(
    nzcsStatus: String?,
    mpiStatus: String?,
): String {
    val normalizedNzcs = canonicalScannerClearanceStatusFallback(nzcsStatus)
    val normalizedMpi = canonicalScannerClearanceStatusFallback(mpiStatus)
    return when {
        normalizedNzcs == "FAILED" || normalizedMpi == "FAILED" -> "FAILED"
        normalizedNzcs == "CLEAR" && normalizedMpi == "CLEAR" -> "CLEAR"
        else -> "HOLD"
    }
}

fun localizedScannerSource(language: AppLanguage, source: String): String {
    return when (canonicalScannerValue(source)) {
        "LOCAL" -> language.pick("Local scanner flow", "本地扫码流程")
        "SESSION" -> language.pick("Container session", "柜号记录")
        "PALLET_ITEM" -> language.pick("Pallet item", "托盘货物")
        "SERVER_CACHE" -> language.pick("Offline cache", "离线缓存")
        "SERVER_LIVE" -> language.pick("Live server check", "服务器实时校验")
        else -> source
    }
}

fun localizedScannerDatabaseRecord(language: AppLanguage, databaseRecord: String): String {
    return when (canonicalScannerValue(databaseRecord)) {
        "NOT_FOUND" -> language.pick("Not found", "未找到")
        "ERROR" -> language.pick("Error", "错误")
        else -> localizedCargoLabel(language, databaseRecord)
    }
}

fun localizedScannerStatusText(language: AppLanguage, status: String): String {
    return when (canonicalScannerValue(status)) {
        "INVALID_BARCODE_FORMAT" -> language.pick("Invalid barcode format", "条码格式无效")
        "UNKNOWN" -> language.pick("Unknown", "未知")
        "ERROR" -> language.pick("Error", "错误")
        else -> {
            val normalized = status.trim()
            val separator = when {
                normalized.contains("·") -> "·"
                normalized.contains("|") -> "|"
                else -> null
            }
            if (separator == null) {
                localizedStatus(language, normalized)
            } else {
                val parts = normalized.split(separator, limit = 2).map(String::trim)
                if (parts.size == 2) {
                    "${localizedCargoLabel(language, parts[0])} $separator ${parts[1]}"
                } else {
                    localizedStatus(language, normalized)
                }
            }
        }
    }
}

fun localizedScannerMatchStatus(language: AppLanguage, status: ScannerMatchStatus): String {
    return when (status) {
        ScannerMatchStatus.MATCHED -> language.pick("Matched", "已匹配")
        ScannerMatchStatus.MISMATCH -> language.pick("Not found", "未找到")
        ScannerMatchStatus.ERROR -> language.pick("Error", "错误")
        ScannerMatchStatus.WAITING -> language.pick("Waiting", "等待中")
    }
}

fun localizedScannerMatchRoute(language: AppLanguage, matchedBy: String?): String {
    return when (matchedBy?.trim()?.uppercase()) {
        "BARCODE_CODE" -> language.pick("Barcode code", "条码编码")
        "CHILD_HBL" -> language.pick("Child HBL", "子 HBL")
        "HBL_NO" -> language.pick("Parent HBL", "主 HBL")
        else -> language.pick("Unknown", "未知")
    }
}

private fun canonicalScannerValue(value: String): String {
    val normalized = value.trim()
    return when {
        normalized.equals("LOCAL", ignoreCase = true) ||
            normalized.equals("Local scanner flow", ignoreCase = true) ||
            normalized == "本地扫码流程" -> "LOCAL"

        normalized.equals("SESSION", ignoreCase = true) ||
            normalized.equals("Container session", ignoreCase = true) ||
            normalized == "柜号记录" -> "SESSION"

        normalized.equals("PALLET_ITEM", ignoreCase = true) ||
            normalized.equals("Pallet item", ignoreCase = true) ||
            normalized == "托盘货物" -> "PALLET_ITEM"

        normalized.equals("SERVER_CACHE", ignoreCase = true) ||
            normalized.equals("Offline cache", ignoreCase = true) ||
            normalized == "离线缓存" -> "SERVER_CACHE"

        normalized.equals("SERVER_LIVE", ignoreCase = true) ||
            normalized.equals("Live server check", ignoreCase = true) ||
            normalized == "服务器实时校验" -> "SERVER_LIVE"

        normalized.equals("NOT_FOUND", ignoreCase = true) ||
            normalized.equals("Not found", ignoreCase = true) ||
            normalized == "未找到" -> "NOT_FOUND"

        normalized.equals("ERROR", ignoreCase = true) ||
            normalized.equals("Error", ignoreCase = true) ||
            normalized == "错误" -> "ERROR"

        normalized.equals("UNKNOWN", ignoreCase = true) ||
            normalized.equals("Unknown", ignoreCase = true) ||
            normalized == "未知" -> "UNKNOWN"

        normalized.equals("INVALID_BARCODE_FORMAT", ignoreCase = true) ||
            normalized.equals("Invalid barcode format", ignoreCase = true) ||
            normalized == "条码格式无效" -> "INVALID_BARCODE_FORMAT"

        else -> normalized
    }
}

private fun looksLikeMojibake(value: String): Boolean {
    if (value.isBlank()) {
        return false
    }
    if (value.any { it in '\u4e00'..'\u9fff' }) {
        return false
    }
    val markers = listOf("Ã", "Â", "æ", "ç", "å", "è", "é", "ï", "œ", "‰", "€", "™", "¢")
    return markers.any(value::contains)
}
