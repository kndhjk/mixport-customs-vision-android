package nz.co.mixport.customsvision.camera

class CargoLabelResolver(
    private val vocabulary: List<CargoVocabularyEntry> = defaultVocabulary(),
) {
    data class CargoVocabularyEntry(
        val label: String,
        val keywords: Set<String>,
    )

    data class CargoLabelMatch(
        val label: String,
        val score: Int,
        val matchedKeywords: List<String>,
    )

    fun resolve(
        sourceLabel: String,
        markerText: String,
        labelHints: List<String>,
    ): CargoLabelMatch? {
        val normalizedSource = normalizeCorpus(sourceLabel)
        val normalizedMarker = normalizeCorpus(markerText)
        val normalizedHints = labelHints
            .map(::normalizeCorpus)
            .filter(String::isNotBlank)

        val match = vocabulary
            .asSequence()
            .mapNotNull { entry ->
                scoreEntry(
                    entry = entry,
                    normalizedSource = normalizedSource,
                    normalizedMarker = normalizedMarker,
                    normalizedHints = normalizedHints,
                )
            }
            .maxWithOrNull(
                compareBy<CargoLabelMatch> { it.score }
                    .thenBy { it.matchedKeywords.size }
                    .thenBy { it.label.length },
            )

        return match?.takeIf { it.score >= MIN_MATCH_SCORE }
    }

    private fun scoreEntry(
        entry: CargoVocabularyEntry,
        normalizedSource: String,
        normalizedMarker: String,
        normalizedHints: List<String>,
    ): CargoLabelMatch? {
        var score = 0
        val matchedKeywords = linkedSetOf<String>()

        entry.keywords.forEach { keyword ->
            val normalizedKeyword = normalizeCorpus(keyword)
            if (normalizedKeyword.isBlank()) {
                return@forEach
            }

            if (normalizedSource.contains(normalizedKeyword)) {
                score += SOURCE_LABEL_WEIGHT
                matchedKeywords += keyword
            }
            if (normalizedMarker.contains(normalizedKeyword)) {
                score += MARKER_TEXT_WEIGHT
                matchedKeywords += keyword
            }
            normalizedHints.forEach { normalizedHint ->
                if (normalizedHint.contains(normalizedKeyword)) {
                    score += LABEL_HINT_WEIGHT
                    matchedKeywords += keyword
                }
            }
        }

        if (score == 0) {
            return null
        }

        if (normalizedSource.contains(normalizeCorpus(entry.label))) {
            score += EXACT_LABEL_BONUS
        }
        if (matchedKeywords.size >= 2) {
            score += MULTI_KEYWORD_BONUS
        }

        return CargoLabelMatch(
            label = entry.label,
            score = score,
            matchedKeywords = matchedKeywords.toList(),
        )
    }

    private fun normalizeCorpus(value: String): String {
        return value.lowercase()
            .replace("&", " and ")
            .replace("/", " ")
            .replace('-', ' ')
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    companion object {
        private const val SOURCE_LABEL_WEIGHT = 4
        private const val MARKER_TEXT_WEIGHT = 5
        private const val LABEL_HINT_WEIGHT = 3
        private const val EXACT_LABEL_BONUS = 2
        private const val MULTI_KEYWORD_BONUS = 2
        private const val MIN_MATCH_SCORE = 4

        fun defaultVocabulary(): List<CargoVocabularyEntry> = listOf(
            CargoVocabularyEntry(
                label = "Electric kettle",
                keywords = setOf("kettle", "teapot", "water boiler", "boiler", "coffee pot", "烧水壶", "水壶"),
            ),
            CargoVocabularyEntry(
                label = "Cup / mug",
                keywords = setOf("cup", "mug", "tumbler", "glass", "teacup", "杯", "杯子", "马克杯"),
            ),
            CargoVocabularyEntry(
                label = "Bowl",
                keywords = setOf("bowl", "dish bowl", "basin", "碗"),
            ),
            CargoVocabularyEntry(
                label = "Plate",
                keywords = setOf("plate", "dish", "serving plate", "盘", "盘子", "餐盘"),
            ),
            CargoVocabularyEntry(
                label = "Chopsticks / cutlery",
                keywords = setOf("chopstick", "chopsticks", "cutlery", "utensil", "utensils", "sticks", "筷", "筷子", "餐具"),
            ),
            CargoVocabularyEntry(
                label = "Spoon / fork",
                keywords = setOf("spoon", "fork", "ladle", "勺", "勺子", "叉子"),
            ),
            CargoVocabularyEntry(
                label = "Pot / pan",
                keywords = setOf("pot", "pan", "cookware", "skillet", "锅", "锅具", "平底锅"),
            ),
            CargoVocabularyEntry(
                label = "Pen / marker",
                keywords = setOf("pen", "marker", "pencil", "stylus", "signature pen", "签字笔", "笔", "记号笔"),
            ),
            CargoVocabularyEntry(
                label = "Computer",
                keywords = setOf("computer", "desktop", "monitor", "display", "screen", "电脑", "显示器"),
            ),
            CargoVocabularyEntry(
                label = "Laptop",
                keywords = setOf("laptop", "notebook computer", "macbook", "笔记本电脑", "手提电脑"),
            ),
            CargoVocabularyEntry(
                label = "Notebook / book",
                keywords = setOf("notebook", "book", "exercise book", "journal", "笔记本", "本子", "书"),
            ),
            CargoVocabularyEntry(
                label = "Keyboard",
                keywords = setOf("keyboard", "keypad", "键盘"),
            ),
            CargoVocabularyEntry(
                label = "Mouse / pointer",
                keywords = setOf("mouse", "computer mouse", "鼠标"),
            ),
            CargoVocabularyEntry(
                label = "Phone / tablet",
                keywords = setOf("phone", "smartphone", "mobile phone", "tablet", "ipad", "手机", "平板"),
            ),
            CargoVocabularyEntry(
                label = "Printer",
                keywords = setOf("printer", "scanner", "打印机"),
            ),
            CargoVocabularyEntry(
                label = "Cable / charger",
                keywords = setOf("cable", "charger", "adapter", "wire", "cord", "数据线", "充电器", "线材"),
            ),
            CargoVocabularyEntry(
                label = "Speaker / audio",
                keywords = setOf("speaker", "audio", "sound box", "headphone", "earphone", "耳机", "音箱", "音响"),
            ),
            CargoVocabularyEntry(
                label = "Carton / box",
                keywords = setOf("carton", "box", "package", "parcel", "crate", "箱", "纸箱", "盒子"),
            ),
            CargoVocabularyEntry(
                label = "Bottle",
                keywords = setOf("bottle", "thermos", "flask", "瓶", "瓶子", "保温瓶"),
            ),
            CargoVocabularyEntry(
                label = "Bag / backpack",
                keywords = setOf("bag", "backpack", "handbag", "luggage bag", "包", "背包", "手提包"),
            ),
            CargoVocabularyEntry(
                label = "Suitcase / luggage",
                keywords = setOf("suitcase", "luggage", "travel case", "行李箱", "旅行箱"),
            ),
            CargoVocabularyEntry(
                label = "Chair / stool",
                keywords = setOf("chair", "stool", "seat", "椅子", "凳子"),
            ),
            CargoVocabularyEntry(
                label = "Lamp / lighting",
                keywords = setOf("lamp", "light", "lantern", "台灯", "灯具"),
            ),
            CargoVocabularyEntry(
                label = "Fan / appliance",
                keywords = setOf("fan", "blower", "ventilator", "电风扇", "风扇"),
            ),
            CargoVocabularyEntry(
                label = "Rice cooker / appliance",
                keywords = setOf("rice cooker", "appliance", "kitchen appliance", "电饭煲", "小家电"),
            ),
            CargoVocabularyEntry(
                label = "Clothing / fabric",
                keywords = setOf("clothing", "garment", "shirt", "pants", "fabric", "衣服", "服装", "布料"),
            ),
            CargoVocabularyEntry(
                label = "Toy / gift",
                keywords = setOf("toy", "doll", "gift", "玩具", "礼品"),
            ),
            CargoVocabularyEntry(
                label = "Helmet / safety gear",
                keywords = setOf("helmet", "hard hat", "safety helmet", "头盔", "安全帽"),
            ),
            CargoVocabularyEntry(
                label = "Umbrella",
                keywords = setOf("umbrella", "伞", "雨伞"),
            ),
            CargoVocabularyEntry(
                label = "Tissue / paper goods",
                keywords = setOf("tissue", "paper towel", "napkin", "纸巾", "抽纸"),
            ),
            CargoVocabularyEntry(
                label = "Detergent / household liquids",
                keywords = setOf("detergent", "cleaner", "liquid soap", "washing liquid", "清洁剂", "洗衣液", "洗洁精"),
            ),
        )
    }
}
