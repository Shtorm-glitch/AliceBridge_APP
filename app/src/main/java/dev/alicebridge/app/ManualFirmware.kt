package dev.alicebridge.app

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * Raw HTTP/1.1 control plane. Host must be quasar.yandex.net while the socket goes to the
 * chosen backend; HttpURLConnection silently drops a custom Host header, so the request is
 * written explicitly. The standard OTA URL is unchanged; only the served bytes are selected.
 */
class ManualFirmware(private val resolver: String, private val token: String) {
    private fun line(input: InputStream): String {
        val out = StringBuilder()
        while (true) {
            val value = input.read()
            if (value < 0) break
            if (value == '\n'.code) break
            if (value != '\r'.code) out.append(value.toChar())
        }
        return out.toString()
    }
    private fun body(input: InputStream, length: Int): ByteArray {
        val data = ByteArray(length)
        var offset = 0
        while (offset <
            length
        ) {
            val read = input.read(data, offset, length - offset)
            if (read < 0) break
            offset += read
        }
        return if (offset == length) data else data.copyOf(offset)
    }
    private fun chunked(input: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val size = line(input).substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size == 0) {
                line(input)
                break
            }
            out.write(body(input, size))
            input.read()
            input.read()
        }
        return out.toByteArray()
    }
    private fun request(
        method: String,
        headers: Map<String, String> = emptyMap(),
        payload: File? = null,
        progress: (Long) -> Unit = {},
    ): JSONObject {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(resolver, 80), 15_000)
            socket.soTimeout = 15 * 60_000
            val output = BufferedOutputStream(socket.getOutputStream())
            val head = buildString {
                append("$method /__alicebridge/manual HTTP/1.1\r\n")
                append("Host: quasar.yandex.net\r\n")
                append("Connection: close\r\n")
                append("X-AliceBridge-Token: $token\r\n")
                headers.forEach { (name, value) -> append("$name: $value\r\n") }
                if (payload !=
                    null
                ) {
                    append("Content-Type: application/octet-stream\r\nContent-Length: ${payload.length()}\r\n")
                } else if (method == "POST") {
                    append("Content-Length: 0\r\n")
                }
                append("\r\n")
            }
            output.write(head.toByteArray(StandardCharsets.UTF_8))
            if (payload != null) {
                payload.inputStream().use { input ->
                    val buffer = ByteArray(1 shl 16)
                    var sent = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <
                            0
                        ) {
                            break
                        }
                        output.write(buffer, 0, read)
                        sent += read
                        progress(sent)
                    }
                }
            }
            output.flush()
            val input = BufferedInputStream(socket.getInputStream())
            val status = line(input).split(' ')
            val code = status.getOrNull(1)?.toIntOrNull() ?: 0
            var length = -1
            var isChunked = false
            while (true) {
                val header = line(input)
                if (header.isEmpty()) break
                val lower = header.lowercase()
                if (lower.startsWith("content-length:")) length = header.substringAfter(':').trim().toIntOrNull() ?: -1
                if (lower.startsWith("transfer-encoding:") && lower.contains("chunked")) isChunked = true
            }
            val bytes = when {
                isChunked -> chunked(input)
                length >= 0 -> body(input, length)
                else -> input.readBytes()
            }
            val text = String(bytes, StandardCharsets.UTF_8)
            if (code !in 200..299) throw IllegalStateException("Сервер: HTTP $code ${text.take(300)}")
            return JSONObject(text)
        }
    }

    fun refresh(): JSONObject = request("GET")
    fun clear(): JSONObject = request("DELETE")
    fun remove(sha256: String): JSONObject = request("DELETE", mapOf("X-AliceBridge-Sha" to sha256))
    fun select(sha256: String): JSONObject = request("POST", mapOf("X-AliceBridge-Select" to sha256))

    fun upload(
        file: File,
        platform: String,
        template: String,
        version: String,
        signature: String,
        progress: (Long) -> Unit,
    ): JSONObject = request(
        "POST",
        mapOf(
            "X-AliceBridge-Platform" to platform,
            "X-AliceBridge-Template" to template,
            "X-AliceBridge-Version" to version,
            "X-AliceBridge-Filename" to file.name,
            "X-AliceBridge-Signature" to signature,
        ),
        file,
        progress,
    )
}
