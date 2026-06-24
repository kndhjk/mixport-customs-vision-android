package nz.co.mixport.customsvision.ui

import nz.co.mixport.customsvision.data.AppLanguage
import nz.co.mixport.customsvision.data.ScannerMatchStatus

fun AppLanguage.pick(english: String, chinese: String): String {
    return if (this == AppLanguage.CHINESE) chinese else english
}

fun localizedCargoLabel(language: AppLanguage, label: String): String {
    val normalized = label.trim()
    return when (normalized) {
        "Wood pallet base" -> language.pick("Wood pallet base", "木托盘底座")
        "Pallet candidate" -> language.pick("Pallet candidate", "托盘候选")
        "Tracked cargo" -> language.pick("Tracked cargo", "跟踪货物")
        "Electric kettle" -> language.pick("Electric kettle", "烧水壶")
        "Cup / mug" -> language.pick("Cup / mug", "杯子/马克杯")
        "Bowl" -> language.pick("Bowl", "碗")
        "Chopsticks / cutlery" -> language.pick("Chopsticks / cutlery", "筷子/餐具")
        "Pen / marker" -> language.pick("Pen / marker", "笔/记号笔")
        "Computer" -> language.pick("Computer", "电脑")
        "Laptop" -> language.pick("Laptop", "笔记本电脑")
        "Notebook / book" -> language.pick("Notebook / book", "笔记本/本子")
        "Carton / box" -> language.pick("Carton / box", "箱子/纸箱")
        "Bottle" -> language.pick("Bottle", "瓶子")
        "Plate" -> language.pick("Plate", "盘子")
        "Keyboard" -> language.pick("Keyboard", "键盘")
        else -> normalized
    }
}

fun localizedStatus(language: AppLanguage, status: String): String {
    return when (status.uppercase()) {
        "ACTIVE" -> language.pick("Active", "进行中")
        "READY_TO_COMPLETE" -> language.pick("Ready to complete", "可完成")
        "COMPLETED" -> language.pick("Completed", "已完成")
        "PAUSED" -> language.pick("Paused", "已暂停")
        "LOADING" -> language.pick("Loading", "装载中")
        "SEALED" -> language.pick("Sealed", "已封膜")
        else -> status
    }
}

fun localizedScannerSource(language: AppLanguage, source: String): String {
    return when (source.uppercase()) {
        "SESSION" -> language.pick("Container session", "柜号记录")
        "PALLET_ITEM" -> language.pick("Pallet item", "托盘货物")
        else -> source
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
