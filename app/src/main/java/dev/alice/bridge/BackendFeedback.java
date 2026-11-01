package dev.alice.bridge;

import android.content.Context;
import android.os.SystemClock;
import java.io.IOException;
import java.net.*;
import java.util.*;
import org.json.*;

/** Backend feedback travels as DNS TXT over TCP; never decrypts speaker traffic. */
public final class BackendFeedback {
    private final Context context;
    private final String resolver, name;
    private final java.security.SecureRandom random = new java.security.SecureRandom();
    private long lastPoll;
    private String previous = "";
    public BackendFeedback(Context context, String resolver) {
        this.context = context; this.resolver = resolver;
        android.content.SharedPreferences prefs = context.getSharedPreferences("config", Context.MODE_PRIVATE);
        String token = prefs.getString("backend_session", "");
        if (token.isEmpty()) { token = UUID.randomUUID().toString().replace("-", ""); prefs.edit().putString("backend_session", token).apply(); }
        name = "status." + token + (prefs.getBoolean("station1_manual",false)?".yandexstation":"") + ".alicebridge.invalid";
    }
    public void poll() {
        long now = SystemClock.elapsedRealtime();
        if (lastPoll != 0 && now - lastPoll < 3000) return;
        lastPoll = now;
        String summary;
        try (Socket socket = new Socket()) {
            byte[] query = DnsWire.query(name, 16, random.nextInt(65536));
            socket.connect(new InetSocketAddress(resolver, 53), 2500); socket.setSoTimeout(2500);
            DnsWire.writeFrame(socket.getOutputStream(), query);
            byte[] response = DnsWire.readFrame(socket.getInputStream());
            if (response[0] != query[0] || response[1] != query[1]) throw new IOException("DNS ID статуса не совпал");
            String status = DnsWire.status(response);
            byte[] echoed = response.clone(); echoed[2] &= 0x7f;
            DnsWire.Question actual = DnsWire.question(echoed);
            if (!actual.name().equals(name) || actual.type() != 16) throw new IOException("DNS question статуса не совпал");
            List<String> texts = DnsWire.txtAnswers(response);
            if (!status.startsWith("NOERROR") || texts.isEmpty()) throw new IOException("Сервер не поддерживает обратную связь: " + status);
            JSONObject data = new JSONObject(texts.get(0));
            Session.backendPayload=data.toString();
            StringBuilder result = new StringBuilder("Сервер · ").append(resolver);
            append(result, data.optString("message"));
            if (!data.optString("platform").isEmpty()) append(result, "Платформа: " + data.optString("platform"));
            if (!data.optString("version").isEmpty()) append(result, "Текущая прошивка: " + data.optString("version"));
            if (!data.optString("target").isEmpty()) append(result, "Целевая прошивка: " + data.optString("target"));
            if (!data.optString("confirmed").isEmpty()) append(result, "Версия подтверждена сервером: " + data.optString("confirmed"));
            if (!data.isNull("http")) append(result, "Последний ответ сервера: HTTP " + data.optInt("http"));
            JSONObject download = data.optJSONObject("download");
            if (download != null && "loading".equals(download.optString("status")))
                append(result, "Сервер скачивает прошивку: " + download.optLong("received") / 1048576 + " МБ / " + download.optLong("total") / 1048576 + " МБ");
            if (download != null && "error".equals(download.optString("status"))) append(result, "Ошибка загрузки: " + download.optString("error"));
            JSONObject ota = data.optJSONObject("ota");
            if (ota != null) append(result, ota.optString("status") + " · " + ota.optLong("sent") * 100 / Math.max(1, ota.optLong("size")) + "%");
            if (!data.optString("adb").isEmpty()) append(result, "ADB со стороны сервера: " + data.optString("adb"));
            Session.progress = new ProgressInfo(data.optString("status"), data.optString("platform"),
                data.optString("version"), data.optString("target"), data.optString("confirmed"), data.optString("message"),
                value(download, "status"), value(download, "error"),
                download == null ? 0 : download.optLong("received"), download == null ? 0 : download.optLong("total"),
                ota == null ? 0 : ota.optLong("sent"), ota == null ? 0 : ota.optLong("size"), SystemClock.elapsedRealtime());
            Session.backendError = "";
            summary = result.toString();
        } catch (IOException | JSONException error) {
            Session.backendError = error.getMessage() == null ? "Не удалось связаться с сервером" : error.getMessage();
            summary = "Обратная связь сервера: " + Session.backendError;
        }
        Session.backend = summary;
        if (!summary.equals(previous)) {
            Session.log(context, "BACKEND", resolver, summary, "Статус через DNS"); previous = summary;
        }
    }
    private static String value(JSONObject data, String key) { return data == null || data.isNull(key) ? "" : data.optString(key); }
    private static void append(StringBuilder out, String value) { if (!value.isEmpty()) out.append('\n').append(value); }
}
