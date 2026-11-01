package dev.alicebridge.app

import dev.alicebridge.bridge.DnsWire
import dev.alicebridge.bridge.ShizukuProbe
import org.junit.Assert.*
import org.junit.Test
import java.net.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class RootNetworkProbeTest {
    private class DnsEndpoint(private val wrongAddress: Boolean = false, private val wrongId: Boolean = false) : AutoCloseable {
        private val tcp = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val port = tcp.localPort
        private val udp = DatagramSocket(InetSocketAddress("127.0.0.1", port))
        val count = AtomicInteger()
        @Volatile private var closed = false
        init {
            thread(isDaemon = true) {
                while (!closed) try {
                    val packet = DatagramPacket(ByteArray(4096), 4096); udp.receive(packet)
                    val reply = answer(packet.data.copyOf(packet.length)); udp.send(DatagramPacket(reply, reply.size, packet.socketAddress))
                } catch (_: Exception) { if (closed) break }
            }
            thread(isDaemon = true) {
                while (!closed) try { tcp.accept().use { socket -> socket.soTimeout = 2000; DnsWire.writeFrame(socket.getOutputStream(), answer(DnsWire.readFrame(socket.getInputStream()))) } }
                catch (_: Exception) { if (closed) break }
            }
        }
        private fun answer(query: ByteArray): ByteArray {
            count.incrementAndGet()
            return DnsWire.localAnswer(query, DnsWire.question(query), if (wrongAddress) "192.0.2.77" else "127.0.0.1").also { if (wrongId) it[0] = (it[0].toInt() xor 1).toByte() }
        }
        override fun close() { closed = true; udp.close(); tcp.close() }
    }
    @Test fun runningProxyMustAnswerBothTransportsAndAllRequiredNames() {
        DnsEndpoint().use { dns ->
            val probe = RootNetworkProbe("127.0.0.1", "127.0.0.1", dns.port)
            probe.dns(false); probe.dns(true)
            assertEquals(10, dns.count.get())
        }
    }
    @Test fun wrongServerAddressStopsPreparation() {
        DnsEndpoint(wrongAddress = true).use { dns ->
            try { RootNetworkProbe("127.0.0.1", "127.0.0.1", dns.port).dns(false); fail("Wrong DNS must not pass") }
            catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("вместо")) }
        }
    }
    @Test fun matchingAddressWithWrongDnsTransactionIdStillFails() {
        DnsEndpoint(wrongId = true).use { dns ->
            try { RootNetworkProbe("127.0.0.1", "127.0.0.1", dns.port).dns(true); fail("Unmatched DNS response must not pass") }
            catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("ID")) }
        }
    }
    @Test fun serverErrorCannotBeTreatedAsInternetReady() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val worker = thread(isDaemon = true) { server.accept().use { socket -> socket.soTimeout = 2000; val reader = socket.getInputStream().bufferedReader(); while (reader.readLine()?.isNotEmpty() == true) {} ; socket.getOutputStream().write("HTTP/1.1 503 Unavailable\r\nContent-Length: 0\r\n\r\n".toByteArray()) } }
            try { RootNetworkProbe("127.0.0.1", "127.0.0.1", httpPort = server.localPort).http(); fail("HTTP 503 must block setup") }
            catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("204")) }
            worker.join(1000)
        }
    }
    @Test fun shizukuCannotProceedWhenOnlyUdpIsCorrect() {
        try { ShizukuProbe.require("[127.0.0.1]", "[192.0.2.77]", "HTTP/1.1 204 No Content", "127.0.0.1"); fail("Wrong TCP DNS must fail") }
        catch (e: java.io.IOException) { assertTrue(e.message!!.contains("DNS")) }
    }
}
