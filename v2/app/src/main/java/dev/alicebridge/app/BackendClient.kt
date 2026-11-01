package dev.alicebridge.app

import dev.alicebridge.bridge.DnsWire
import dev.alicebridge.domain.SetupSession
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom

/** Control feedback uses DNS TXT; speaker HTTP/TLS is never decrypted by the app. */
class BackendClient(private val token: String) {
    private val random = SecureRandom()
    fun poll(session: SetupSession, mode: String = "normal"): JSONObject {
        val hint = when(session.modelId){"lite2"->"ca-plum";"mini3"->"ca-bergamot";else->if(session.platform=="yandexstation")"yandexstation" else ""}
        val name = "status.$token.${hint.ifBlank { "base" }}.${if (mode == "manual") "manual" else "normal"}.alicebridge.invalid"
        val query = DnsWire.query(name, 16, random.nextInt(65536))
        val response = Socket().use {
            it.connect(InetSocketAddress(session.resolver, 53), 2000); it.soTimeout = 2500
            DnsWire.writeFrame(it.getOutputStream(), query); DnsWire.readFrame(it.getInputStream())
        }
        require(response[0] == query[0] && response[1] == query[1]) { "DNS ID статуса не совпал" }
        val echo = response.clone().also { it[2] = (it[2].toInt() and 0x7f).toByte() }
        val question = DnsWire.question(echo)
        require(question.name() == name && question.type() == 16) { "DNS question статуса не совпал" }
        require(DnsWire.status(response).startsWith("NOERROR")) { "Сервер не вернул статус через DNS" }
        return JSONObject(DnsWire.txtAnswers(response).firstOrNull() ?: error("В DNS нет статуса сервера")).also {
            if(it.optString("status") in setOf("ambiguous","model_mismatch"))error(it.optString("message","Не удалось однозначно определить сеанс колонки"))
            if(session.modelId in setOf("lite2","mini3"))require(it.optString("retail_ca_mode")==session.platform){"Сервер ещё не поддерживает режим retail CA для выбранной модели. Нужен обновлённый backend; не подключайте колонку, пока режим без OTA не подтверждён."}
            require(it.optInt("feedback_schema") >= 2) { "Обновите сервер AliceBridge: требуется feedback_schema=2. Старый статус не используем как подтверждение прогресса." }
        }
    }
}
