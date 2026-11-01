package dev.alicebridge.station;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.json.*;

/** A public HTTP mirror is safe only after this pinned signature and hash check. */
public final class AgentRelease {
    public final JSONObject payload;
    public final byte[] envelope;

    private AgentRelease(JSONObject payload, byte[] envelope) {
        this.payload = payload;
        this.envelope = envelope;
    }

    public static AgentRelease verify(byte[] envelope, byte[] publicKey) throws Exception {
        if (envelope.length > 65536) throw new IOException("Манифест службы слишком большой");
        JSONObject e = new JSONObject(new String(envelope, StandardCharsets.UTF_8));
        byte[] bytes = Base64.getDecoder().decode(e.getString("payload")),
                signature = Base64.getDecoder().decode(e.getString("signature"));
        if (bytes.length > 32768 || signature.length > 256)
            throw new IOException("Неверный формат подписи службы");
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(
                KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(publicKey)));
        verifier.update(bytes);
        if (!verifier.verify(signature))
            throw new IOException("Подпись обновления службы не совпала");
        JSONObject p = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        if (p.getInt("schema") != 1
                || p.getInt("protocol") != 1
                || p.getInt("build") < 1
                || !p.getString("version").matches("[0-9]+\\.[0-9]+\\.[0-9]+"))
            throw new IOException("Приложение не поддерживает эту версию службы");
        return new AgentRelease(p, envelope.clone());
    }

    public JSONObject artifact(String arch) throws Exception {
        JSONArray a = payload.getJSONArray("artifacts");
        for (int i = 0; i < a.length(); i++) {
            JSONObject v = a.getJSONObject(i);
            if (!arch.equals(v.optString("arch"))) continue;
            if (!v.getString("file").equals(payload.getString("version") + "/agent-" + arch)
                    || !v.getString("sha256").matches("[a-f0-9]{64}")
                    || v.getLong("size") < 1
                    || v.getLong("size") > 32 * 1024 * 1024)
                throw new IOException("Неверные параметры бинарника службы");
            return v;
        }
        throw new IOException("Для этой архитектуры нет сборки службы");
    }
}
