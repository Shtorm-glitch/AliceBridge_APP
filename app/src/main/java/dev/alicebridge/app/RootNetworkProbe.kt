package dev.alicebridge.app

import dev.alicebridge.bridge.DnsWire
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom

/** Tests the running proxy socket itself, not only direct DNS from the phone. */
class RootNetworkProbe(
    private val gateway: String,
    private val resolver: String,
    private val dnsPort: Int = 5354,
    private val httpPort: Int = 80,
) {
    private val random = SecureRandom()
    fun dns(tcp: Boolean) {
        for (name in listOf("scbh.yandex.net", "quasar.yandex.net", "quasar.yandex.ru")) {
            val response = exchange(name, 1, tcp)
            val addresses = DnsWire.ipv4Answers(response)
            require(addresses == listOf(resolver)) {
                "DNS ${if (tcp) "TCP" else "UDP"} для $name вернул $addresses вместо $resolver. Проверьте адрес сервера и интернет телефона."
            }
        }
        val ipv6 = exchange("quasar.yandex.net", 28, tcp)
        require(DnsWire.u16(ipv6, 6) == 0) {
            "DNS вернул IPv6 для quasar.yandex.net: возможен обход выбранного сервера"
        }
        val ordinary = exchange("example.com", 1, tcp)
        require(DnsWire.ipv4Answers(ordinary).isNotEmpty()) { "Обычные домены через DNS пока не разрешаются" }
    }
    private fun exchange(name: String, type: Int, tcp: Boolean): ByteArray {
        val query = DnsWire.query(name, type, random.nextInt(65536))
        val address = InetAddress.getByName(gateway)
        val answer = if (tcp) {
            Socket().use {
                it.bind(InetSocketAddress(address, 0))
                it.connect(InetSocketAddress(address, dnsPort), 2500)
                it.soTimeout =
                    6000
                DnsWire.writeFrame(it.getOutputStream(), query)
                DnsWire.readFrame(it.getInputStream())
            }
        } else {
            DatagramSocket(InetSocketAddress(address, 0)).use {
                it.connect(address, dnsPort)
                it.soTimeout = 6000
                it.send(DatagramPacket(query, query.size))
                val packet = DatagramPacket(ByteArray(65535), 65535)
                it.receive(packet)
                packet.data.copyOf(packet.length)
            }
        }
        require(answer.size >= 12 && answer[0] == query[0] && answer[1] == query[1]) { "DNS ID ответа не совпал" }
        val echo = answer.clone().also { it[2] = (it[2].toInt() and 0x7f).toByte() }
        val question = DnsWire.question(echo)
        require(question.name() == name && question.type() == type) { "DNS вернул ответ на другой вопрос" }
        require(DnsWire.status(answer).startsWith("NOERROR")) { "DNS: ${DnsWire.status(answer)}" }
        return answer
    }
    fun http() {
        val line = Socket().use {
            it.connect(InetSocketAddress(resolver, httpPort), 2500)
            it.soTimeout = 4000
            it.getOutputStream().write(
                "HEAD /generate_204 HTTP/1.1\r\nHost: scbh.yandex.net\r\nConnection: close\r\n\r\n".toByteArray(
                    Charsets.US_ASCII,
                ),
            )
            it.getOutputStream().flush()
            it.getInputStream().bufferedReader(Charsets.US_ASCII).readLine()
        }
        require(line != null && Regex("HTTP/1\\.[01] 204(?: .*)?").matches(line)) {
            "Сервер не подтвердил доступность HTTP 204: $line"
        }
    }
}
