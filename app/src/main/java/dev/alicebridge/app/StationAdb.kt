package dev.alicebridge.app

import android.content.Context
import dev.alicebridge.bridge.RootShell
import dev.alicebridge.station.StationTools
import java.net.InetSocketAddress
import java.net.Socket

/** Uses the migrated script-file and PTY transport, including Station 1 root shells. */
class StationAdb(context: Context) : AutoCloseable {
    data class Result(val code: Int, val output: String)
    private val repository = (context.applicationContext as BridgeApplication).repository
    val tools =
        StationTools(context) { kind, source, detail, status -> repository.log(kind, "$source · $detail · $status") }
    val target: String get() = tools.state.station
    fun portOpen(ip: String): Boolean = try {
        RootShell.validateIpv4(ip)
        Socket().use { it.connect(InetSocketAddress(ip, 5555), 450) }
        true
    } catch (_: Exception) {
        false
    }
    fun connect(ip: String): Boolean {
        RootShell.validateIpv4(ip)
        repository.state.value.session?.let {
            tools.selectModel(it.modelId)
            tools.hotspotCredentials(it.ssid, it.password)
        }
        return tools.connect(ip).connected()
    }
    fun shell(command: String): Result {
        val result = tools.shell(command)
        return Result(result.code(), result.output())
    }
    fun information(): Map<String, String> = tools.information()
    override fun close() = tools.close()
}
