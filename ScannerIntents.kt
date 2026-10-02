package ru.czcheck.scanner

import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle

/**
 * Приём штрихкодов от встроенного сканера ТСД через широковещательные сообщения (Intent).
 * Список покрывает популярные терминалы; если вашего нет — настройте в сканере
 * действие [OWN_ACTION] и поле данных "data".
 */
object ScannerIntents {

    const val OWN_ACTION = "ru.czcheck.scanner.SCAN"

    val ACTIONS = listOf(
        OWN_ACTION,
        "android.intent.ACTION_DECODE_DATA",                 // Urovo, Mertech, АТОЛ, Cipherlab (часть)
        "nlscan.action.SCANNER_RESULT",                      // Newland, Mertech, АТОЛ Smart.Lite/Smart.Slim
        "android.intent.action.SCANRESULT",                  // iData
        "com.android.server.scannerservice.broadcast",       // Seuic / Kaicom / AutoID
        "com.scanner.broadcast",                             // Chainway
        "scan.rcv.message",                                  // Chainway (старые), Mindeo, iData (часть)
        "com.sunmi.scanner.ACTION_DATA_CODE_RECEIVED",       // Sunmi
        "unitech.scanservice.data",                          // Unitech
        "com.cipherlab.barcodebaseapi.PASS_DATA_2_APP",      // CipherLab
        "device.scanner.EVENT",                              // Point Mobile
        "com.honeywell.decode.intent.action.EDIT_DATA",      // Honeywell (Data Intent)
        "com.datalogic.decodewedge.decode_action",           // Datalogic
        "com.symbol.datawedge.api.RESULT_ACTION",            // Zebra (если так названо в профиле)
        "com.xcheng.scanner.action.BARCODE_DECODING_BROADCAST",
        "com.ubx.datawedge.SCANNER_DECODE_EVENT",
        "android.intent.action.SCANNER_RESULT",
        "com.zkc.scancode",
        "com.barcode.sendBroadcast"
    )

    private val STRING_KEYS = listOf(
        "data", "barcode_string", "SCAN_BARCODE1", "com.symbol.datawedge.data_string",
        "value", "scannerdata", "barcodeData", "barcode", "text", "Decoder_Data",
        "EXTRA_BARCODE_DECODING_DATA", "com.datalogic.decode.intentwedge.barcode_string",
        "decode_rslt", "scan_result", "SCAN_RESULT", "barocode", "code", "BARCODE", "barcodeString"
    )

    private val BYTE_KEYS = listOf(
        "barcode", "barocode", "EXTRA_EVENT_DECODE_VALUE", "data", "barcode_byte", "decode_rslt_bytes",
        "EXTRA_BARCODE_DECODING_DATA"
    )

    private val SERVICE_KEY_PARTS = listOf("type", "symbolog", "aim", "source", "state", "label", "version", "name", "time")

    private val LENGTH_KEYS =listOf("length", "barcode_len", "EXTRA_EVENT_DECODE_LENGTH", "barcodelen")

    fun filter(): IntentFilter = IntentFilter().apply {
        ACTIONS.forEach { addAction(it) }
        addCategory(Intent.CATEGORY_DEFAULT)
    }

    /** Достаёт строку штрихкода из Intent любого из поддерживаемых сканеров. */
    fun extract(intent: Intent?): String? {
        val extras: Bundle = intent?.extras ?: return null
        try {
            // известные строковые поля
            for (k in STRING_KEYS) {
                when (val v = getSafe(extras, k)) {
                    is String -> if (v.isNotBlank()) return v
                    is CharSequence -> if (v.isNotBlank()) return v.toString()
                }
            }
            // известные байтовые поля
            for (k in BYTE_KEYS) {
                val v = getSafe(extras, k)
                if (v is ByteArray && v.isNotEmpty()) return bytesToString(v, lengthOf(extras))
            }
            // любое подходящее поле (кроме служебных: тип штрихкода, источник и т.п.)
            for (k in extras.keySet()) {
                val lk = k.lowercase()
                if (SERVICE_KEY_PARTS.any { lk.contains(it) }) continue
                when (val v = getSafe(extras, k)) {
                    is String -> if (v.length >= 8) return v
                    is ByteArray -> if (v.size >= 8) return bytesToString(v, lengthOf(extras))
                }
            }
        } catch (e: Exception) {
            return null
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun getSafe(b: Bundle, key: String): Any? = try { b.get(key) } catch (e: Exception) { null }

    private fun lengthOf(b: Bundle): Int {
        for (k in LENGTH_KEYS) {
            when (val v = getSafe(b, k)) {
                is Int -> if (v > 0) return v
                is Long -> if (v > 0) return v.toInt()
                is String -> v.toIntOrNull()?.let { if (it > 0) return it }
            }
        }
        return -1
    }

    private fun bytesToString(bytes: ByteArray, length: Int): String {
        var len = if (length in 1..bytes.size) length else bytes.size
        while (len > 0 && bytes[len - 1].toInt() == 0) len--
        // ISO-8859-1 сохраняет управляющие символы (GS = 0x1D) байт в байт
        return String(bytes, 0, len, Charsets.ISO_8859_1)
    }
}
