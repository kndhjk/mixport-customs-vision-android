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
        "Spoon / fork" -> language.pick("Spoon / fork", "勺子/叉子")
        "Pot / pan" -> language.pick("Pot / pan", "锅/平底锅")
        "Mouse / pointer" -> language.pick("Mouse / pointer", "鼠标")
        "Phone / tablet" -> language.pick("Phone / tablet", "手机/平板")
        "Printer" -> language.pick("Printer", "打印机")
        "Cable / charger" -> language.pick("Cable / charger", "线材/充电器")
        "Speaker / audio" -> language.pick("Speaker / audio", "音箱/音频设备")
        "Bag / backpack" -> language.pick("Bag / backpack", "包/背包")
        "Suitcase / luggage" -> language.pick("Suitcase / luggage", "行李箱")
        "Chair / stool" -> language.pick("Chair / stool", "椅子/凳子")
        "Lamp / lighting" -> language.pick("Lamp / lighting", "灯具")
        "Fan / appliance" -> language.pick("Fan / appliance", "风扇/电器")
        "Rice cooker / appliance" -> language.pick("Rice cooker / appliance", "电饭煲/小家电")
        "Clothing / fabric" -> language.pick("Clothing / fabric", "衣物/布料")
        "Toy / gift" -> language.pick("Toy / gift", "玩具/礼品")
        "Helmet / safety gear" -> language.pick("Helmet / safety gear", "头盔/安全用具")
        "Umbrella" -> language.pick("Umbrella", "雨伞")
        "Tissue / paper goods" -> language.pick("Tissue / paper goods", "纸巾/纸品")
        "Detergent / household liquids" -> language.pick("Detergent / household liquids", "清洁剂/家用液体")
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
