package ru.czcheck.scanner

/** Цвет результата, как в приложении «Честный знак». */
enum class Level {
    /** Ещё проверяется */
    PENDING,
    /** Зелёный: введён в оборот, всё хорошо */
    OK,
    /** Красный: есть проблема (не найден, не введён в оборот, выбыл, просрочен…) */
    BAD,
    /** Жёлтый: найден, но статус непонятен / требует внимания */
    WARN,
    /** Серый: не удалось проверить (нет связи, ошибка сервера) */
    ERROR
}

class ScanItem(
    val id: Long,
    val time: Long,
    /** Строка, как пришла от сканера */
    val raw: String,
    /** Нормализованный код (с GS) */
    val code: String,
    val gtin: String?,
    val serial: String?,
    val notes: List<String>,
    var repeat: Boolean = false
) {
    var level: Level = Level.PENDING
    var title: String = "Проверяю…"
    var product: String? = null
    var statusCode: String? = null
    var details: List<Pair<String, String>> = emptyList()
    var httpCode: Int = 0
    var requestInfo: String? = null
    var response: String? = null

    val cis: String?
        get() = if (gtin != null && serial != null) "01${gtin}21$serial" else null
}
