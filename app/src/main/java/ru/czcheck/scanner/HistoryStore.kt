package ru.czcheck.scanner

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Хранит историю сканирований в файле, чтобы она не терялась при перезапуске. */
class HistoryStore(private val file: File) {

    fun load(): MutableList<ScanItem> {
        val list = ArrayList<ScanItem>()
        if (!file.exists()) return list
        try {
            val arr = JSONArray(file.readText(Charsets.UTF_8))
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(fromJson(o))
            }
        } catch (e: Exception) {
            // повреждённый файл — начинаем с чистой истории
        }
        return list
    }

    fun toJsonString(items: List<ScanItem>): String {
        val arr = JSONArray()
        for (it in items.take(MAX_ITEMS)) arr.put(toJson(it))
        return arr.toString()
    }

    fun write(json: String) {
        try {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json, Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.writeText(json, Charsets.UTF_8)
                tmp.delete()
            }
        } catch (e: Exception) {
            // не критично
        }
    }

    private fun toJson(s: ScanItem): JSONObject {
        val o = JSONObject()
        o.put("id", s.id)
        o.put("time", s.time)
        o.put("raw", s.raw)
        o.put("code", s.code)
        o.put("gtin", s.gtin ?: JSONObject.NULL)
        o.put("serial", s.serial ?: JSONObject.NULL)
        o.put("notes", JSONArray(s.notes))
        o.put("repeat", s.repeat)
        // незавершённую проверку сохраняем как «не проверено»
        o.put("level", if (s.level == Level.PENDING) Level.ERROR.name else s.level.name)
        o.put("title", if (s.level == Level.PENDING) "Проверка прервана" else s.title)
        o.put("product", s.product ?: JSONObject.NULL)
        o.put("statusCode", s.statusCode ?: JSONObject.NULL)
        val d = JSONArray()
        for ((k, v) in s.details) d.put(JSONArray().put(k).put(v))
        o.put("details", d)
        o.put("httpCode", s.httpCode)
        o.put("requestInfo", s.requestInfo ?: JSONObject.NULL)
        o.put("response", s.response?.take(MAX_RESPONSE) ?: JSONObject.NULL)
        return o
    }

    private fun fromJson(o: JSONObject): ScanItem {
        val notes = ArrayList<String>()
        o.optJSONArray("notes")?.let { a -> for (i in 0 until a.length()) notes.add(a.optString(i)) }
        val item = ScanItem(
            id = o.optLong("id"),
            time = o.optLong("time"),
            raw = o.optString("raw"),
            code = o.optString("code"),
            gtin = optStr(o, "gtin"),
            serial = optStr(o, "serial"),
            notes = notes,
            repeat = o.optBoolean("repeat")
        )
        item.level = try { Level.valueOf(o.optString("level")) } catch (e: Exception) { Level.ERROR }
        item.title = o.optString("title")
        item.product = optStr(o, "product")
        item.statusCode = optStr(o, "statusCode")
        val det = ArrayList<Pair<String, String>>()
        o.optJSONArray("details")?.let { a ->
            for (i in 0 until a.length()) {
                val p = a.optJSONArray(i) ?: continue
                det.add(p.optString(0) to p.optString(1))
            }
        }
        item.details = det
        item.httpCode = o.optInt("httpCode")
        item.requestInfo = optStr(o, "requestInfo")
        item.response = optStr(o, "response")
        return item
    }

    private fun optStr(o: JSONObject, k: String): String? =
        if (!o.has(k) || o.isNull(k)) null else o.optString(k)

    companion object {
        const val MAX_ITEMS = 1000
        const val MAX_RESPONSE = 20_000
    }
}
