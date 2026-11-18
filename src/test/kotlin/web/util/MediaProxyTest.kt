package web.util

import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaProxyTest {
    private fun withAudioServer(action: (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/audio") { exchange ->
            exchange.use {
                val range = exchange.requestHeaders.getFirst("Range")
                exchange.responseHeaders.set("Content-Type", "audio/mpeg")
                exchange.responseHeaders.set("Accept-Ranges", "bytes")
                exchange.responseHeaders.set("ETag", "\"audio-1\"")
                exchange.responseHeaders.set("X-Saw-Private", exchange.requestHeaders.getFirst("X-Private") ?: "missing")
                exchange.responseHeaders.set("X-Saw-Source", exchange.requestHeaders.getFirst("X-Source") ?: "missing")
                if (range == "bytes=99-") {
                    exchange.responseHeaders.set("Content-Range", "bytes */10")
                    exchange.sendResponseHeaders(416, -1)
                } else {
                    val partial = range == "bytes=2-5" &&
                        exchange.requestHeaders.getFirst("If-Range") != "\"old\""
                    val bytes = (if (partial) "2345" else "0123456789").toByteArray()
                    if (partial) exchange.responseHeaders.set("Content-Range", "bytes 2-5/10")
                    if (exchange.requestMethod == "HEAD") {
                        exchange.responseHeaders.set("Content-Length", bytes.size.toString())
                        exchange.sendResponseHeaders(if (partial) 206 else 200, -1)
                    } else {
                        exchange.sendResponseHeaders(if (partial) 206 else 200, bytes.size.toLong())
                        exchange.responseBody.write(bytes)
                    }
                }
            }
        }
        server.createContext("/redirect") { exchange ->
            exchange.use {
                exchange.responseHeaders.set("Location", "/audio")
                exchange.sendResponseHeaders(302, -1)
            }
        }
        server.createContext("/loop") { exchange ->
            exchange.use {
                exchange.responseHeaders.set("Location", "/loop")
                exchange.sendResponseHeaders(307, -1)
            }
        }
        server.start()
        try {
            action("http://127.0.0.1:${server.address.port}/audio")
        } finally {
            server.stop(0)
        }
    }

    private fun request(
        url: String,
        method: String = "GET",
        range: String? = null,
        ifRange: String? = null,
        check: (HttpURLConnection) -> Unit
    ) {
        val connection = MediaProxy.connect(url, null, method = method, range = range, ifRange = ifRange)
        try {
            check(connection)
        } finally {
            connection.errorStream?.close()
            connection.disconnect()
        }
    }

    @Test
    fun fullAudioResponseRetainsLengthAndType() = withAudioServer { url ->
        request(url) {
            assertEquals(200, it.responseCode)
            assertEquals("audio/mpeg", it.contentType)
            assertEquals(10, it.contentLength)
            assertEquals("0123456789", it.inputStream.use { input -> input.readBytes().decodeToString() })
        }
    }

    @Test
    fun rangeAndIfRangeReachUpstream() = withAudioServer { url ->
        request(url, range = "bytes=2-5", ifRange = "\"audio-1\"") {
            assertEquals(206, it.responseCode)
            assertEquals("bytes 2-5/10", it.getHeaderField("Content-Range"))
            assertEquals("bytes", it.getHeaderField("Accept-Ranges"))
            assertEquals(4, it.contentLength)
            assertEquals("2345", it.inputStream.use { input -> input.readBytes().decodeToString() })
        }
        request(url, range = "bytes=2-5", ifRange = "\"old\"") {
            assertEquals(200, it.responseCode)
            assertEquals("0123456789", it.inputStream.use { input -> input.readBytes().decodeToString() })
        }
    }

    @Test
    fun unsatisfiedRangeIsNotRewrittenToSuccess() = withAudioServer { url ->
        request(url, range = "bytes=99-") {
            assertEquals(416, it.responseCode)
            assertEquals("bytes */10", it.getHeaderField("Content-Range"))
        }
    }

    @Test
    fun headReturnsMetadataWithoutBody() = withAudioServer { url ->
        request(url, method = "HEAD") {
            assertEquals(200, it.responseCode)
            assertEquals(10, it.contentLength)
            assertEquals(-1, it.inputStream.use { input -> input.read() })
        }
    }

    @Test
    fun explicitHeadersOverrideSourceIgnoringCaseAndTransportHeadersAreControlled() {
        val connection = MediaProxy.openConnection(
            "http://127.0.0.1/audio",
            """{"referer":"https://override.test/","Accept-Encoding":"gzip","Range":"bytes=90-","Connection":"X-Hop","X-Hop":"removed"}""",
            mapOf("Referer" to "https://source.test/", "User-Agent" to "media-test", "X-Source" to "present"),
            range = "bytes=2-5",
            ifRange = "\"audio-1\"",
            sourceUrl = "http://127.0.0.1/"
        )
        try {
            assertEquals("https://override.test/", connection.getRequestProperty("Referer"))
            assertEquals("media-test", connection.getRequestProperty("User-Agent"))
            assertEquals("present", connection.getRequestProperty("X-Source"))
            assertEquals("identity", connection.getRequestProperty("Accept-Encoding"))
            assertEquals("bytes=2-5", connection.getRequestProperty("Range"))
            assertEquals("\"audio-1\"", connection.getRequestProperty("If-Range"))
            assertNull(connection.getRequestProperty("Connection"))
            assertNull(connection.getRequestProperty("X-Hop"))
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun sourcePrivateHeadersAreNotSentToUnrelatedOrigins() {
        for ((target, expected) in listOf(
            "http://source.test/audio" to "present",
            "https://source.test/audio" to "present",
            "http://other.test/audio" to null,
            "http://source.test:8080/audio" to null
        )) {
            val connection = MediaProxy.openConnection(target, null,
                mapOf("X-Source" to "present", "User-Agent" to "media-test"),
                sourceUrl = "http://source.test/")
            try {
                assertEquals(expected, connection.getRequestProperty("X-Source"))
                assertEquals("media-test", connection.getRequestProperty("User-Agent"))
            } finally {
                connection.disconnect()
            }
        }
    }

    @Test
    fun sameOriginRedirectRetainsRangeHeadAndExplicitHeaders() = withAudioServer { url ->
        val redirect = url.replace("/audio", "/redirect")
        for (method in listOf("GET", "HEAD")) {
            val connection = MediaProxy.connect(redirect, """{"X-Private":"present"}""",
                method = method, range = "bytes=2-5", ifRange = "\"audio-1\"")
            try {
                assertEquals(206, connection.responseCode)
                assertEquals("present", connection.getHeaderField("X-Saw-Private"))
                assertEquals("bytes 2-5/10", connection.getHeaderField("Content-Range"))
                assertEquals(if (method == "HEAD") "" else "2345",
                    connection.inputStream.use { it.readBytes().decodeToString() })
            } finally {
                connection.disconnect()
            }
        }
    }

    @Test
    fun crossOriginRedirectDropsExplicitAndInheritedPrivateHeaders() = withAudioServer { url ->
        val redirector = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        redirector.createContext("/redirect") { exchange ->
            exchange.use {
                exchange.responseHeaders.set("Location", url)
                exchange.sendResponseHeaders(302, -1)
            }
        }
        redirector.start()
        try {
            val origin = "http://127.0.0.1:${redirector.address.port}/redirect"
            val connection = MediaProxy.connect(origin, """{"X-Private":"present"}""",
                mapOf("X-Source" to "present"), range = "bytes=2-5", sourceUrl = origin)
            try {
                assertEquals(206, connection.responseCode)
                assertEquals("missing", connection.getHeaderField("X-Saw-Private"))
                assertEquals("missing", connection.getHeaderField("X-Saw-Source"))
                assertEquals("2345", connection.inputStream.use { it.readBytes().decodeToString() })
            } finally {
                connection.disconnect()
            }
        } finally {
            redirector.stop(0)
        }
    }

    @Test
    fun redirectLoopsHaveAFiniteLimit() = withAudioServer { url ->
        assertFailsWith<IOException> { MediaProxy.connect(url.replace("/audio", "/loop"), null) }
    }

    @Test
    fun invalidUrlsAndHeadersFailBeforeNetworking() {
        for (url in listOf("file:///etc/passwd", "ftp://invalid.test/audio", "relative.mp3", "http://")) {
            assertFailsWith<IllegalArgumentException> { MediaProxy.openConnection(url, null) }
        }
        for (header in listOf("{bad}", "[]", "null", "{\"X-Test\":{}}", "{\"Bad Header\":\"value\"}", "{\"X-Test\":\"bad\\r\\nInjected: yes\"}")) {
            assertFailsWith<IllegalArgumentException> { MediaProxy.openConnection("http://127.0.0.1/audio", header) }
        }
        assertFailsWith<IllegalArgumentException> {
            MediaProxy.openConnection("http://127.0.0.1/audio", null, range = "bytes=0-\r\nInjected: yes")
        }
    }

    @Test
    fun imageDecodeFailureReturnsAllOriginalBytesAndClosesInput() {
        var closed = false
        val original = byteArrayOf(1, 2, 3, 4)
        val input = object : ByteArrayInputStream(original) {
            override fun close() {
                closed = true
                super.close()
            }
        }
        val result = MediaProxy.decodeImage(input) {
            it.readBytes()
            throw IllegalStateException("decode failed")
        }
        assertContentEquals(original, result)
        assertTrue(closed)
    }

    @Test
    fun successfulDecodeReturnsTransformedBytes() {
        val decoded = byteArrayOf(9, 8, 7)
        assertContentEquals(decoded, MediaProxy.decodeImage(byteArrayOf(1, 2).inputStream()) { decoded })
    }

    @Test
    fun oversizedImageIsRejectedBeforeRunningDecode() {
        var decoded = false
        assertFailsWith<IOException> {
            MediaProxy.decodeImage(byteArrayOf(1, 2, 3, 4).inputStream(), maxBytes = 3) {
                decoded = true
                byteArrayOf(9)
            }
        }
        assertEquals(false, decoded)
    }

    @Test
    fun upstreamReadFailureIsNotTreatedAsDecodeFallback() {
        val input = object : java.io.InputStream() {
            override fun read(): Int = throw IOException("upstream failed")
        }
        assertFailsWith<IOException> { MediaProxy.decodeImage(input) { byteArrayOf(1) } }
    }
}
