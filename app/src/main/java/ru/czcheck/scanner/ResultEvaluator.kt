package ru.czcheck.scanner

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Превращает ответ сервера Честного знака в «светофор»:
 * зелёный — в обороте, красный — проблема, жёлтый — непонятно, серый — не удалось проверить.
 *
 * Разбор сделан терпимым: все поля необязательные, неизвестные поля игнорируются.
 */
object ResultEvaluator {

    data class Verdict(
        val level: Level,
        val title: String,
        val product: String? = null,
        val statusCode: String? = null,
        val details: List<Pair<String, String>> = emptyList()
    )

    private val GREEN = setOf("INTRODUCED", "IN_CIRCULATION", "INTRODUCED_RETURNED", "RETURNED")
    private val NOT_INTRODUCED = setOf("EMITTED", "APPLIED", "APPLIED_PAID", "APPLIED_NOT_PAID", "NOT_INTRODUCED")
    private val RETIRED = setOf(
        "RETIRED", "WITHDRAWN", "WRITTEN_OFF", "DESTROYED", "SOLD", "IN_CIRCULATION_SOLD",
        "OUT_OF_CIRCULATION", "EXPIRED", "UTILISED", "UTILIZED"
    )
    private val BLOCKED = setOf("WITHHELD", "BLOCKED", "SUSPENDED", "FROZEN")

    val STATUS_RU = mapOf(
        "EMITTED" to "Эмитирован (не нанесён)",
        "APPLIED" to "Нанесён, не введён в оборот",
        "APPLIED_PAID" to "Нанесён, не введён в оборот",
        "APPLIED_NOT_PAID" to "Нанесён, не оплачен",
        "NOT_INTRODUCED" to "Не введён в оборот",
        "INTRODUCED" to "В обороте",
        "IN_CIRCULATION" to "В обороте",
        "INTRODUCED_RETURNED" to "В обороте (возвращён)",
        "RETURNED" to "В обороте (возвращён)",
        "WRITTEN_OFF" to "Списан",
        "RETIRED" to "Выбыл из оборота",
        "WITHDRAWN" to "Выведен из оборота",
        "OUT_OF_CIRCULATION" to "Выведен из оборота",
        "DISAGGREGATED" to "Расформирован (упаковка)",
        "DESTROYED" to "Уничтожен",
        "UTILISED" to "Утилизирован",
        "UTILIZED" to "Утилизирован",
        "SOLD" to "Продан",
        "IN_CIRCULATION_SOLD" to "Продан",
        "WITHHELD" to "Приостановлен",
        "BLOCKED" to "Заблокирован",
        "SUSPENDED" to "Приостановлен",
        "FROZEN" to "Заморожен",
        "SHIPPED" to "Отгружен",
        "EXPIRED" to "Истёк срок годности"
    )

    private val EMISSION_RU = mapOf(
        "PRODUCTION" to "Произведён в РФ",
        "LOCAL" to "Произведён в РФ",
        "IMPORT" to "Ввезён в РФ",
        "FOREIGN" to "Ввезён в РФ",
        "REMAINS" to "Маркировка остатков",
        "REMARK" to "Перемаркировка",
        "CROSSBORDER" to "Ввоз из ЕАЭС",
        "COMMISSIONING" to "Ввод в оборот",
        "COMMISSION" to "Принят на комиссию"
    )

    private val CATEGORY_RU = mapOf(
        "milk" to "Молочная продукция",
        "lp" to "Одежда и текстиль",
        "shoes" to "Обувь",
        "tobacco" to "Табак",
        "otp" to "Альтернативная табачная продукция",
        "nicotine" to "Никотинсодержащая продукция",
        "water" to "Упакованная вода",
        "beer" to "Пиво",
        "nabeer" to "Безалкогольное пиво",
        "softdrinks" to "Безалкогольные напитки",
        "juice" to "Соки",
        "perfumery" to "Парфюмерия",
        "perfume" to "Парфюмерия",
        "tires" to "Шины",
        "photo" to "Фототехника",
        "camera" to "Фототехника",
        "bicycle" to "Велосипеды",
        "wheelchairs" to "Кресла-коляски",
        "furs" to "Меховые изделия",
        "bio" to "БАДы",
        "drugs" to "Лекарства",
        "pharma" to "Лекарства",
        "antiseptic" to "Антисептики",
        "medical" to "Медицинские изделия",
        "conserve" to "Консервы",
        "petfood" to "Корма для животных",
        "vetpharma" to "Ветпрепараты",
        "cosmetics" to "Косметика и бытовая химия",
        "chemistry" to "Бытовая химия",
        "grocery" to "Бакалея",
        "seafood" to "Морепродукты",
        "vegetableoil" to "Растительные масла",
        "sweets" to "Кондитерские изделия",
        "toys" to "Игрушки",
        "electronics" to "Электроника",
        "construction" to "Стройматериалы",
        "autofluids" to "Автохимия"
    )

    private val NOT_GROUP_KEYS = setOf("codeResolveData", "catalogData", "cisInfo", "errors")

    fun evaluate(httpCode: Int, body: String?, now: Long = System.currentTimeMillis()): Verdict {
        val json = parseObject(body)

        if (json == null) {
            return when {
                httpCode == 451 -> Verdict(Level.ERROR, "Сервис ЧЗ недоступен из этой страны (нужен российский интернет)")
                httpCode == 403 -> Verdict(Level.ERROR, "Сервер ЧЗ отклонил запрос (403)")
                httpCode == 429 -> Verdict(Level.ERROR, "Слишком много запросов — подождите минуту")
                httpCode == 404 || httpCode == 400 -> Verdict(Level.BAD, "Код не найден в Честном знаке")
                httpCode >= 500 -> Verdict(Level.ERROR, "Сервер ЧЗ не отвечает (ошибка $httpCode)")
                httpCode == 200 -> Verdict(Level.ERROR, "Пустой или непонятный ответ сервера")
                else -> Verdict(Level.ERROR, "Непонятный ответ сервера (HTTP $httpCode)")
            }
        }

        val group = findGroup(json)
        val cisInfo = json.optJSONObject("cisInfo")
        val sources = listOfNotNull(json, group, cisInfo)

        val details = ArrayList<Pair<String, String>>()
        val product = firstString(sources, "productName", "name", "goodName", "good_name")
            ?: catalogString(json, "good_name", "name", "productName", "goodName")

        // --- ошибки сервера с JSON-телом ---
        if (httpCode >= 400 && !json.has("codeFounded") && group == null && cisInfo == null) {
            val msg = firstString(listOf(json), "message", "error_message", "errorDescription", "description", "error", "title")
            return when (httpCode) {
                451 -> Verdict(Level.ERROR, "Сервис ЧЗ недоступен из этой страны (нужен российский интернет)")
                429 -> Verdict(Level.ERROR, "Слишком много запросов — подождите минуту")
                404, 400 -> Verdict(Level.BAD, "Код не найден в Честном знаке", details = listOfNotNull(msg?.let { "Ответ" to it }))
                else -> Verdict(Level.ERROR, "Ошибка сервера ЧЗ (HTTP $httpCode)", details = listOfNotNull(msg?.let { "Ответ" to it }))
            }
        }

        // --- статус ---
        val topStatus = json.optString("status", "").trim()
        if (topStatus.equals("wrong", ignoreCase = true) || topStatus.equals("invalid", ignoreCase = true)) {
            return Verdict(Level.BAD, "Неверный код маркировки", product, topStatus,
                listOf("Пояснение" to "Код не прошёл проверку формата. Отсканируйте ещё раз или проверьте, что сканер передаёт символы GS."))
        }

        val found = if (json.has("codeFounded")) json.optBoolean("codeFounded", true) else null
        if (found == false) {
            val d = ArrayList<Pair<String, String>>()
            catalogOrGroupDetails(json, group, d)
            return Verdict(Level.BAD, "Код не найден в Честном знаке", product, null, d)
        }

        val rawStatus = firstString(
            listOf(json), "outerStatus"
        ) ?: firstString(listOfNotNull(group, cisInfo), "outerStatus", "status", "cisStatus", "statusEx")
            ?: firstString(listOf(json), "cisStatus")
            ?: topStatus.takeIf { it.isNotEmpty() && it.uppercase(Locale.ROOT) in STATUS_RU.keys }

        val status = rawStatus?.uppercase(Locale.ROOT)

        var level: Level
        var title: String
        when {
            status == null -> {
                level = Level.WARN
                title = if (found == true) "Код найден, но статус не указан" else "В ответе нет статуса"
            }
            status in GREEN -> { level = Level.OK; title = "В обороте" }
            status in NOT_INTRODUCED -> { level = Level.BAD; title = "Не введён в оборот" }
            status in RETIRED -> {
                level = Level.BAD
                title = when (status) {
                    "SOLD", "IN_CIRCULATION_SOLD" -> "Уже продан (выведен из оборота)"
                    "EXPIRED" -> "Истёк срок годности"
                    "WRITTEN_OFF" -> "Списан"
                    "DESTROYED", "UTILISED", "UTILIZED" -> "Уничтожен"
                    else -> "Выведен из оборота"
                }
            }
            status in BLOCKED -> { level = Level.BAD; title = "Оборот приостановлен / заблокирован" }
            else -> { level = Level.WARN; title = STATUS_RU[status] ?: "Статус: $rawStatus" }
        }

        if (status != null) {
            details.add("Статус" to (STATUS_RU[status]?.let { "$it ($rawStatus)" } ?: rawStatus))
        }

        // --- дополнительные признаки проблем ---
        val blocked = sources.any { it.optBoolean("isBlocked", false) || it.optBoolean("blocked", false) }
        if (blocked && level != Level.BAD) {
            level = Level.BAD
            title = "Код заблокирован"
        }

        val expire = sources.firstNotNullOfOrNull { parseDate(it.opt("expireDate") ?: it.opt("expirationDate")) }
        if (expire != null) {
            details.add("Годен до" to formatDate(expire))
            // дата без времени = до конца этого дня
            if (expire + 86_400_000L <= now && level == Level.OK) {
                level = Level.BAD
                title = "Истёк срок годности"
            }
        }

        if (json.has("checkResult") && !json.optBoolean("checkResult", true)) {
            details.add("Проверка подписи" to "не пройдена")
            if (level == Level.OK) {
                level = Level.WARN
                title = "В обороте, но проверка кода не пройдена"
            }
        }

        catalogOrGroupDetails(json, group, details)

        return Verdict(level, title, product, rawStatus, details)
    }

    private fun catalogOrGroupDetails(json: JSONObject, group: JSONObject?, details: MutableList<Pair<String, String>>) {
        val sources = listOfNotNull(json, group, json.optJSONObject("cisInfo"))

        fun add(label: String, value: String?) {
            if (!value.isNullOrBlank() && details.none { it.first == label }) details.add(label to value)
        }

        add("Бренд", catalogString(json, "brand_name", "brand") ?: firstString(sources, "brand"))
        add("Производитель", firstString(sources, "producerName", "producer_name", "manufacturerName")
            ?: catalogString(json, "producer_name", "producerName"))
        add("Владелец", firstString(sources, "ownerName", "owner_name"))
        add("ИНН владельца", firstString(sources, "ownerInn", "owner_inn"))

        val gtin = json.optJSONObject("codeResolveData")?.let { str(it, "gtin") }
            ?: firstString(sources, "gtin")
            ?: group?.optJSONObject("codeData")?.let { str(it, "gtin") }
        add("GTIN", gtin)

        val cat = firstString(listOf(json), "category", "productGroup") ?: firstString(sources, "productGroup")
        add("Товарная группа", cat?.let { CATEGORY_RU[it.lowercase(Locale.ROOT)] ?: it })

        sources.firstNotNullOfOrNull { parseDate(it.opt("introducedDate")) }?.let { add("Введён в оборот", formatDate(it)) }
        sources.firstNotNullOfOrNull { parseDate(it.opt("productionDate") ?: it.opt("producedDate")) }?.let { add("Дата производства", formatDate(it)) }

        val emission = firstString(sources, "emissionType")
            ?: sources.firstNotNullOfOrNull { it.optJSONObject("productProperty")?.let { p -> str(p, "emissionType") } }
        add("Способ ввода", emission?.let { EMISSION_RU[it.uppercase(Locale.ROOT)] ?: it })
    }

    /** Блок данных товарной группы: milkData, lpData, shoesData, drugsData и т.п. */
    fun findGroup(json: JSONObject): JSONObject? {
        val keys = json.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k in NOT_GROUP_KEYS) continue
            if (k.endsWith("Data")) {
                val o = json.optJSONObject(k)
                if (o != null) return o
            }
        }
        return null
    }

    private fun parseObject(body: String?): JSONObject? {
        if (body.isNullOrBlank()) return null
        val t = body.trim()
        if (!t.startsWith("{")) {
            if (t.startsWith("[")) {
                return try { JSONArray(t).optJSONObject(0) } catch (e: Exception) { null }
            }
            return null
        }
        return try { JSONObject(t) } catch (e: Exception) { null }
    }

    private fun str(o: JSONObject, key: String): String? {
        if (!o.has(key) || o.isNull(key)) return null
        val v = o.opt(key)
        if (v is JSONObject || v is JSONArray) return null
        val s = v.toString().trim()
        return s.ifEmpty { null }
    }

    private fun firstString(sources: List<JSONObject>, vararg keys: String): String? {
        for (o in sources) for (k in keys) str(o, k)?.let { return it }
        return null
    }

    private fun catalogString(json: JSONObject, vararg keys: String): String? {
        val arr = json.optJSONArray("catalogData") ?: return null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            for (k in keys) str(o, k)?.let { return it }
        }
        return null
    }

    /** Дата: миллисекунды, секунды или строка ISO (yyyy-MM-dd...). */
    fun parseDate(v: Any?): Long? {
        if (v == null || v == JSONObject.NULL) return null
        if (v is Number) {
            val n = v.toLong()
            if (n <= 0) return null
            return if (n < 100_000_000_000L) n * 1000 else n
        }
        val s = v.toString().trim()
        if (s.isEmpty()) return null
        if (s.all { it.isDigit() }) return parseDate(s.toLongOrNull())
        if (s.length >= 10 && s[4] == '-' && s[7] == '-') {
            return try {
                SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse(s.substring(0, 10))?.time
            } catch (e: Exception) { null }
        }
        if (s.length >= 10 && s[2] == '.' && s[5] == '.') {
            return try {
                SimpleDateFormat("dd.MM.yyyy", Locale.ROOT).parse(s.substring(0, 10))?.time
            } catch (e: Exception) { null }
        }
        return null
    }

    fun formatDate(ms: Long): String = SimpleDateFormat("dd.MM.yyyy", Locale.ROOT).format(Date(ms))
}
