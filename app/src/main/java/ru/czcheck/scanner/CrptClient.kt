package ru.czcheck.scanner

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** Способ обращения к API проверки кода (тот же сервер, что у приложения «Честный знак»). */
enum class ApiMode(val title: String) {
    AUTO("Автоматически (рекомендуется)"),
    V2_POST("POST /v2/mobile/check"),
    V1_POST("POST /mobile/check"),
    V1_GET("GET /mobile/check")
}

/**
 * Клиент публичного API проверки кодов Честного знака (без авторизации).
 * Работает только с российского интернета: из-за рубежа сервер отвечает 451.
 */
class CrptClient {

    data class Config(
        val mode: ApiMode,
        val baseUrl: String,
        val trustAllSsl: Boolean,
        /** Вариант, который уже срабатывал раньше (для AUTO) */
        val preferred: ApiMode?
    )

    enum class ErrorKind { NO_NETWORK, TIMEOUT, SSL, OTHER }

    data class Response(
        val variant: ApiMode,
        val url: String,
        val requestBody: String?,
        val httpCode: Int,
        val body: String?,
        val error: String? = null,
        val errorKind: ErrorKind? = null
    ) {
        val isJsonObject: Boolean get() = body?.trimStart()?.startsWith("{") == true
        val isSuccess: Boolean get() = error == null && httpCode == 200 && isJsonObject
    }

    fun check(n: CodeNormalizer.Normalized, cfg: Config): Response {
        val all = listOf(ApiMode.V2_POST, ApiMode.V1_POST, ApiMode.V1_GET)
        val order: List<ApiMode> = when {
            cfg.mode != ApiMode.AUTO -> listOf(cfg.mode)
            cfg.preferred != null && cfg.preferred in all -> listOf(cfg.preferred) + all.filter { it != cfg.preferred }
            else -> all
        }

        val results = ArrayList<Response>()
        for ((i, v) in order.withIndex()) {
            val r = call(v, n, cfg)
            if (r.error != null) {
                // Нет сети / таймаут / SSL — другие варианты не помогут
                if (r.errorKind != ErrorKind.OTHER) return r
                results.add(r)
                continue
            }
            if (cfg.mode != ApiMode.AUTO) return r
            if (r.isSuccess) return r
            // Сервер явно ответил по «рабочему» адресу (например, код не найден) — верим ему
            if (i == 0 && v == cfg.preferred && r.isJsonObject && r.httpCode in 400..499 && r.httpCode != 405 && r.httpCode != 415) return r
            // Геоблокировка и лимит одинаковы для всех адресов
            if (r.httpCode == 451 || r.httpCode == 429) return r
            results.add(r)
        }
        return results.firstOrNull { it.error == null && it.isJsonObject } ?: results.first()
    }

    private fun call(v: ApiMode, n: CodeNormalizer.Normalized, cfg: Config): Response {
        val base = cfg.baseUrl.trim().trimEnd('/')
        val url: String
        val body: String?
        when (v) {
            ApiMode.V2_POST -> {
                url = "$base/v2/mobile/check"
                body = JSONObject()
                    .put("code", CodeNormalizer.wireCode(n))
                    .put("codeType", CODE_TYPE)
                    .toString()
            }
            ApiMode.V1_POST -> {
                url = "$base/mobile/check"
                body = JSONObject()
                    .put("code", n.code)
                    .put("codeType", CODE_TYPE)
                    .toString()
            }
            ApiMode.V1_GET, ApiMode.AUTO -> {
                url = "$base/mobile/check?code=" + URLEncoder.encode(n.code, "UTF-8") + "&codeType=" + CODE_TYPE
                body = null
            }
        }

        var conn: HttpURLConnection? = null
        try {
            conn = URL(url).openConnection() as HttpURLConnection
            if (cfg.trustAllSsl && conn is HttpsURLConnection) {
                conn.sslSocketFactory = trustAllFactory
                conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
            }
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.useCaches = false
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Accept-Language", "ru-RU,ru")
            conn.setRequestProperty("User-Agent", USER_AGENT)
            if (body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            } else {
                conn.requestMethod = "GET"
            }
            val code = conn.responseCode
            val stream: InputStream? = if (code >= 400) conn.errorStream else conn.inputStream
            val text = stream?.use { readAll(it) }
            return Response(v, url, body, code, text)
        } catch (e: SSLException) {
            return Response(v, url, body, 0, null,
                "Ошибка защищённого соединения (SSL): ${e.message ?: e.javaClass.simpleName}", ErrorKind.SSL)
        } catch (e: UnknownHostException) {
            return Response(v, url, body, 0, null, "Нет интернета (сервер не найден)", ErrorKind.NO_NETWORK)
        } catch (e: ConnectException) {
            return Response(v, url, body, 0, null, "Нет соединения с сервером", ErrorKind.NO_NETWORK)
        } catch (e: NoRouteToHostException) {
            return Response(v, url, body, 0, null, "Нет соединения с сервером", ErrorKind.NO_NETWORK)
        } catch (e: SocketTimeoutException) {
            return Response(v, url, body, 0, null, "Сервер не ответил вовремя", ErrorKind.TIMEOUT)
        } catch (e: IOException) {
            return Response(v, url, body, 0, null, "Ошибка сети: ${e.message ?: e.javaClass.simpleName}", ErrorKind.OTHER)
        } catch (e: Exception) {
            return Response(v, url, body, 0, null, "Ошибка: ${e.message ?: e.javaClass.simpleName}", ErrorKind.OTHER)
        } finally {
            conn?.disconnect()
        }
    }

    private fun readAll(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val r = input.read(buf)
            if (r < 0) break
            out.write(buf, 0, r)
            total += r
            if (total > 2_000_000) break
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    private val trustAllFactory: SSLSocketFactory by lazy {
        val tm = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, tm, SecureRandom())
        ctx.socketFactory
    }

    companion object {
        const val CODE_TYPE = "datamatrix"
        const val USER_AGENT = "okhttp/4.12.0"
    }
}
