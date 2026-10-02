package ru.czcheck.scanner

/**
 * Приводит строку от сканера к виду, который понимает API Честного знака:
 * убирает префиксы и мусор, исправляет русскую раскладку,
 * восстанавливает разделители GS, если сканер их потерял.
 */
object CodeNormalizer {

    const val GS = '\u001D'
    const val FNC1 = "{FNC1}"

    enum class Kind {
        /** Код маркировки GS1 DataMatrix: 01 + GTIN + 21 + серийный номер ... */
        GS1,
        /** Пачка сигарет: 29 символов без идентификаторов применения */
        TOBACCO_PACK,
        /** Обычный штрихкод EAN/UPC — это не код маркировки */
        EAN,
        UNKNOWN
    }

    data class Normalized(
        /** Код с разделителями GS, без {FNC1} */
        val code: String,
        val kind: Kind,
        val gtin: String?,
        val serial: String?,
        /** Что было исправлено (для подсказки пользователю) */
        val notes: List<String>
    ) {
        /** Код идентификации (КИ) без криптохвоста: 01 + GTIN + 21 + серийный */
        val cis: String?
            get() = if (gtin != null && serial != null) "01${gtin}21$serial" else null

        val hasGs: Boolean get() = code.indexOf(GS) >= 0
    }

    private val aimPrefixes = listOf("]d2", "]d1", "]C1", "]Q3", "]Q1", "]e0")

    /** Текстовые заменители GS, которые встречаются в настройках сканеров. */
    private val gsSubstitutes = listOf("<GS>", "{GS}", "[GS]", "\\u001d", "\\u001D", "\\x1d", "\\x1D", "␝")

    // Русская раскладка ЙЦУКЕН -> латинская QWERTY (та же клавиша)
    private const val RU =
        "ёйцукенгшщзхъфывапролджэячсмитьбю" +
            "ЁЙЦУКЕНГШЩЗХЪФЫВАПРОЛДЖЭЯЧСМИТЬБЮ" +
            ".,\"№;:?"
    private const val EN =
        "`qwertyuiop[]asdfghjkl;'zxcvbnm,." +
            "~QWERTYUIOP{}ASDFGHJKL:\"ZXCVBNM<>" +
            "/?@#$^&"

    fun normalize(input: String, restoreGs: Boolean = true, fixLayout: Boolean = true): Normalized {
        val notes = mutableListOf<String>()
        var s = input

        // 1. Русская раскладка
        if (fixLayout && s.any { it in 'Ѐ'..'ӿ' }) {
            s = fixKeyboardLayout(s)
            notes.add("исправлена русская раскладка")
        }

        // 2. Текстовые заменители GS
        for (sub in gsSubstitutes) {
            if (s.contains(sub)) s = s.replace(sub, GS.toString())
        }
        // Символ FNC1 в виде 'è' (код 232) у некоторых декодеров
        s = s.replace('è', GS)
        // '~' не входит в набор символов GS1, его ставят вместо GS
        if (s.contains('~')) s = s.replace('~', GS)

        // 3. Убираем переводы строк, пробелы и прочие управляющие символы (кроме GS)
        s = buildString {
            for (c in s) {
                if (c == GS) append(c)
                else if (c == ' ' || c == ' ' || c.code < 0x20 || c.code == 0x7F) continue
                else append(c)
            }
        }

        // 4. Префиксы
        var changed = true
        while (changed) {
            changed = false
            if (s.startsWith(FNC1)) { s = s.substring(FNC1.length); changed = true }
            for (p in aimPrefixes) if (s.startsWith(p)) { s = s.substring(p.length); changed = true }
            while (s.isNotEmpty() && s[0] == GS) { s = s.substring(1); changed = true }
        }
        // 4а. Формат «со скобками»: (01)04670535000453(21)ABC...(91)EE12(92)...
        // Так выдают код сканеры Urovo и др., если включён вывод GS1 с идентификаторами в скобках.
        if (s.startsWith("(")) {
            val plain = fromBracketed(s.replace(GS.toString(), ""))
            if (plain != null) {
                s = plain
                notes.add("убраны скобки вокруг идентификаторов (01)(21)…")
            }
        }

        // хвостовые GS не нужны
        s = s.trimEnd(GS)
        // двойные GS
        while (s.contains("$GS$GS")) s = s.replace("$GS$GS", GS.toString())

        // 5. Определяем тип
        val kind = detectKind(s)

        // 6. Восстановление GS
        if (kind == Kind.GS1 && restoreGs && s.indexOf(GS) < 0) {
            val restored = restoreGs(s)
            if (restored != null && restored != s) {
                s = restored
                notes.add("восстановлены разделители GS")
            } else {
                notes.add("в коде нет разделителей GS")
            }
        }

        var gtin: String? = null
        var serial: String? = null
        when (kind) {
            Kind.GS1 -> {
                val ais = parseGs1(s)
                gtin = ais["01"]
                serial = ais["21"]
            }
            Kind.TOBACCO_PACK -> {
                gtin = s.substring(0, 14)
                serial = s.substring(14, 21)
            }
            Kind.EAN -> gtin = s
            Kind.UNKNOWN -> {}
        }
        return Normalized(s, kind, gtin, serial, notes)
    }

    fun detectKind(s: String): Kind {
        if (s.isEmpty()) return Kind.UNKNOWN
        if (s.all { it.isDigit() } && s.length in setOf(8, 12, 13, 14)) return Kind.EAN
        if (s.length >= 19 && s.startsWith("01") && s.substring(2, 16).all { it.isDigit() } && s.substring(16, 18) == "21") {
            return Kind.GS1
        }
        if (s.length == 29 && s.substring(0, 14).all { it.isDigit() }) return Kind.TOBACCO_PACK
        return Kind.UNKNOWN
    }

    fun fixKeyboardLayout(s: String): String = buildString(s.length) {
        for (c in s) {
            val i = RU.indexOf(c)
            append(if (i >= 0) EN[i] else c)
        }
    }

    /**
     * Восстанавливает GS в коде вида 01<GTIN>21<серийный><криптохвост> без разделителей.
     * Возвращает null, если структура не распознана.
     */
    fun restoreGs(s: String): String? {
        if (s.length < 19 || !s.startsWith("01") || s.substring(16, 18) != "21") return null
        val head = s.substring(0, 18) // 01 + GTIN + 21
        val n = s.length

        // Формат «91 + ключ(4) / 92 + код проверки(44)»: одежда, обувь, шины, парфюм, лекарства и др.
        if (n >= 18 + 1 + 6 + 46 && s.substring(n - 46, n - 44) == "92" && s.substring(n - 52, n - 50) == "91") {
            val serial = s.substring(18, n - 52)
            if (serial.length in 1..20) {
                return head + serial + GS + s.substring(n - 52, n - 46) + GS + s.substring(n - 46)
            }
        }

        // Формат «93 + код проверки(4)»: молоко, вода, пиво, БАДы, блоки табака и др.
        if (n >= 18 + 1 + 6 && s.substring(n - 6, n - 4) == "93") {
            val serial = s.substring(18, n - 6)
            // блок табака: серийный(7) + 8005 + МРЦ(6)
            if (serial.length == 17 && serial.substring(7, 11) == "8005") {
                return head + serial.substring(0, 7) + GS + serial.substring(7) + GS + s.substring(n - 6)
            }
            if (serial.length in 1..20) {
                return head + serial + GS + s.substring(n - 6)
            }
        }
        // Весовой товар: ... 93 + код(4) + 310x/330x + 6 цифр
        val weight = Regex("(31[0-9]{2}|33[0-9]{2})[0-9]{6}$")
        if (n >= 18 + 1 + 6 + 10 && weight.containsMatchIn(s) && s.substring(n - 16, n - 14) == "93") {
            val serial = s.substring(18, n - 16)
            if (serial.length in 1..20) {
                return head + serial + GS + s.substring(n - 16, n - 10) + GS + s.substring(n - 10)
            }
        }

        return null
    }

    private val fixedAi = mapOf(
        "01" to 14, "02" to 14, "11" to 6, "13" to 6, "15" to 6, "17" to 6, "8005" to 6
    )
    private val variableAi = setOf("10", "21", "91", "92", "93", "240", "241", "22")

    /** Разбор идентификаторов применения GS1. Неизвестный AI прерывает разбор. */
    fun parseGs1(code: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var pos = 0
        while (pos < code.length) {
            if (code[pos] == GS) { pos++; continue }
            val rest = code.substring(pos)
            val ai: String = when {
                rest.length >= 4 && (rest.startsWith("310") || rest.startsWith("330")) && rest[3].isDigit() -> rest.substring(0, 4)
                rest.startsWith("8005") -> "8005"
                rest.startsWith("240") || rest.startsWith("241") -> rest.substring(0, 3)
                rest.length >= 2 && (fixedAi.containsKey(rest.substring(0, 2)) || variableAi.contains(rest.substring(0, 2))) -> rest.substring(0, 2)
                else -> break
            }
            pos += ai.length
            val fixedLen = fixedAi[ai] ?: if (ai.length == 4 && (ai.startsWith("310") || ai.startsWith("330"))) 6 else null
            if (fixedLen != null) {
                val end = minOf(code.length, pos + fixedLen)
                out[ai] = code.substring(pos, end)
                pos = end
            } else {
                var end = code.indexOf(GS, pos)
                if (end < 0) end = code.length
                out[ai] = code.substring(pos, end)
                pos = end
            }
        }
        return out
    }

    private val bracketAi = Regex("""^\((01|02|10|11|13|15|17|21|22|91|92|93|240|241|8005|31\d\d|33\d\d)\)""")

    /** Привычные длины значений, чтобы не спутать скобку внутри серийного номера с началом следующего AI. */
    private val usualLength = mapOf("21" to setOf(13, 6, 7), "91" to setOf(4), "93" to setOf(4), "92" to setOf(44))

    private fun bracketFixedLength(ai: String): Int? = fixedAi[ai]
        ?: if (ai.length == 4 && (ai.startsWith("31") || ai.startsWith("33"))) 6 else null

    /**
     * Переводит запись вида (01)GTIN(21)серийный(93)код в машинный вид 01GTIN21серийный<GS>93код.
     * Возвращает null, если строка не похожа на такую запись.
     */
    fun fromBracketed(s: String): String? {
        if (!s.startsWith("(") || bracketAi.find(s) == null) return null
        val out = StringBuilder()
        var pos = 0
        while (pos < s.length) {
            val m = bracketAi.find(s.substring(pos)) ?: return null
            val ai = m.groupValues[1]
            pos += m.value.length
            val fixed = bracketFixedLength(ai)
            if (fixed != null) {
                if (pos + fixed > s.length) return null
                out.append(ai).append(s, pos, pos + fixed)
                pos += fixed
                continue
            }
            // переменная длина: до следующего «(AI)» или до конца строки
            val candidates = ArrayList<Int>()
            var p = s.indexOf('(', pos)
            while (p >= 0) {
                if (bracketAi.find(s.substring(p)) != null) candidates.add(p)
                p = s.indexOf('(', p + 1)
            }
            val lengths = usualLength[ai]
            val end = candidates.firstOrNull { lengths != null && (it - pos) in lengths }
                ?: candidates.firstOrNull()
                ?: s.length
            if (end <= pos) return null
            out.append(ai).append(s, pos, end)
            pos = end
            if (pos < s.length) out.append(GS)
        }
        return out.toString()
    }

    /** Код для запроса: для GS1 добавляем {FNC1}, как это делает приложение Честный знак. */
    fun wireCode(n: Normalized): String = if (n.kind == Kind.GS1) FNC1 + n.code else n.code

    /** Видимое представление кода (GS показываем как ‹GS›). */
    fun display(code: String): String = code.replace(GS.toString(), "‹GS›")
}
