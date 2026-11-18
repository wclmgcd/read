package web.util

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.TreeMap

internal object MediaProxy {
    private val logger = LoggerFactory.getLogger(MediaProxy::class.java)
    private val headerName = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")
    private val publicHeaders = setOf("user-agent", "referer", "origin", "accept", "accept-language")
    private val transportHeaders = setOf(
        "host", "connection", "content-length", "transfer-encoding", "upgrade",
        "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailer",
        "accept-encoding", "range", "if-range", "proxy"
    )
    val responseHeaders = listOf(
        "Content-Type", "Content-Length", "Content-Range", "Accept-Ranges",
        "Content-Encoding", "ETag", "Last-Modified"
    )

    private fun httpUri(url: String): URI {
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            throw IllegalArgumentException("Invalid media URL")
        }
        require(uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank())
        return uri
    }

    private fun canForwardCredentials(from: String?, to: String): Boolean {
        if (from.isNullOrBlank()) return false
        val source = runCatching { httpUri(from) }.getOrNull() ?: return false
        val target = httpUri(to)
        if (!source.host.equals(target.host, true)) return false
        val sourcePort = if (source.port >= 0) source.port else if (source.scheme.equals("https", true)) 443 else 80
        val targetPort = if (target.port >= 0) target.port else if (target.scheme.equals("https", true)) 443 else 80
        return (source.scheme.equals(target.scheme, true) && sourcePort == targetPort) ||
            (source.scheme.equals("http", true) && sourcePort == 80 &&
                target.scheme.equals("https", true) && targetPort == 443)
    }

    private fun parseHeaders(header: String?): Map<String, String> {
        if (header.isNullOrBlank()) return emptyMap()
        val json = try {
            JsonParser.parseString(header)
        } catch (_: Exception) {
            throw IllegalArgumentException("Invalid media headers")
        }
        require(json.isJsonObject)
        return json.asJsonObject.entrySet().associate { (name, value) ->
            require(value.isJsonPrimitive && value.asJsonPrimitive.isString)
            name to value.asString
        }
    }

    fun openConnection(
        url: String,
        header: String?,
        sourceHeaders: Map<String, String> = emptyMap(),
        method: String = "GET",
        range: String? = null,
        ifRange: String? = null,
        sourceUrl: String? = null
    ): HttpURLConnection {
        val uri = httpUri(url)
        require(method == "GET" || method == "HEAD")
        val headers = TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)
        headers.putAll(if (canForwardCredentials(sourceUrl, url)) sourceHeaders else
            sourceHeaders.filterKeys { it.lowercase() in publicHeaders })
        headers.putAll(parseHeaders(header))
        val connectionHeaders = headers["Connection"]?.split(',')
            ?.map { it.trim().lowercase() }.orEmpty()
        headers.entries.removeIf { (name, _) ->
            name.lowercase() in transportHeaders || name.lowercase() in connectionHeaders
        }
        // Range 的字节偏移必须与实际传输的未压缩实体一致。
        headers["Accept-Encoding"] = "identity"
        if (!range.isNullOrBlank()) {
            headers["Range"] = range
            if (!ifRange.isNullOrBlank()) headers["If-Range"] = ifRange
        }
        headers.forEach { (name, value) ->
            require(headerName.matches(name) && value.none { it == '\r' || it == '\n' || it == '\u0000' })
        }
        return (uri.toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = false
            connectTimeout = 20_000
            readTimeout = 20_000
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
    }

    fun connect(
        url: String,
        header: String?,
        sourceHeaders: Map<String, String> = emptyMap(),
        method: String = "GET",
        range: String? = null,
        ifRange: String? = null,
        sourceUrl: String? = null
    ): HttpURLConnection {
        var target = url
        var explicitHeaders = header
        var inheritedHeaders = sourceHeaders
        repeat(6) { redirect ->
            val connection = openConnection(target, explicitHeaders, inheritedHeaders, method, range, ifRange, sourceUrl)
            try {
                if (connection.responseCode !in listOf(301, 302, 303, 307, 308)) return connection
                if (redirect == 5) throw IOException("Too many media redirects")
                val location = connection.getHeaderField("Location") ?: throw IOException("Missing media redirect")
                val next = try {
                    httpUri(httpUri(target).resolve(location).toString()).toString()
                } catch (_: IllegalArgumentException) {
                    throw IOException("Invalid media redirect")
                }
                if (!canForwardCredentials(target, next)) {
                    // CDN 跳转可保留防盗链头，但不能继承账号凭据或自定义密钥。
                    explicitHeaders = Gson().toJson(parseHeaders(explicitHeaders).filterKeys { it.lowercase() in publicHeaders })
                    inheritedHeaders = inheritedHeaders.filterKeys { it.lowercase() in publicHeaders }
                }
                target = next
            } catch (error: Exception) {
                connection.disconnect()
                throw error
            }
            connection.disconnect()
        }
        throw IOException("Too many media redirects")
    }

    fun decodeImage(input: InputStream, maxBytes: Int = 32 * 1024 * 1024, decode: (InputStream) -> ByteArray): ByteArray {
        val original = input.use { it.readNBytes(maxBytes + 1) }
        if (original.size > maxBytes) throw IOException("Image exceeds decode limit")
        return try {
            original.inputStream().use(decode)
        } catch (_: Exception) {
            logger.warn("图片解密失败，返回原图")
            original
        }
    }
}
