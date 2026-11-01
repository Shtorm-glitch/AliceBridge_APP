package dev.alicebridge.app;

import static org.junit.Assert.*;

import dev.alicebridge.station.AgentRelease;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import org.json.*;
import org.junit.Test;

public class AgentReleaseTest {
    @Test
    public void nativeReleaseSignatureIsCompatibleWithAndroidVerifier() throws Exception {
        try (java.io.InputStream manifest =
                        getClass().getResourceAsStream("/agent/release-valid.json");
                java.io.InputStream key =
                        getClass().getResourceAsStream("/agent/signing-public.der")) {
            assertNotNull(manifest);
            assertNotNull(key);
            AgentRelease release = AgentRelease.verify(manifest.readAllBytes(), key.readAllBytes());
            assertEquals("1.0.0", release.payload.getString("version"));
            assertTrue(release.artifact("arm64").getLong("size") > 0);
            assertTrue(release.artifact("arm").getLong("size") > 0);
        }
    }

    private KeyPair key() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        return g.generateKeyPair();
    }

    private JSONObject payload() throws Exception {
        return new JSONObject()
                .put("schema", 1)
                .put("protocol", 1)
                .put("version", "1.0.0")
                .put("build", 1)
                .put(
                        "artifacts",
                        new JSONArray()
                                .put(
                                        new JSONObject()
                                                .put("arch", "arm64")
                                                .put("file", "1.0.0/agent-arm64")
                                                .put("size", 123)
                                                .put("sha256", "a".repeat(64))));
    }

    private byte[] signed(JSONObject payload, KeyPair k) throws Exception {
        byte[] data = payload.toString().getBytes(StandardCharsets.UTF_8);
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(k.getPrivate());
        s.update(data);
        return new JSONObject()
                .put("payload", Base64.getEncoder().encodeToString(data))
                .put("signature", Base64.getEncoder().encodeToString(s.sign()))
                .toString()
                .getBytes(StandardCharsets.UTF_8);
    }

    @Test
    public void acceptsValidSignatureAndRejectsUntrustedSigningKey() throws Exception {
        KeyPair a = key(), b = key();
        byte[] manifest = signed(payload(), a);
        assertEquals(
                123,
                AgentRelease.verify(manifest, a.getPublic().getEncoded())
                        .artifact("arm64")
                        .getInt("size"));
        try {
            AgentRelease.verify(manifest, b.getPublic().getEncoded());
            fail();
        } catch (java.io.IOException expected) {
        }
    }

    @Test
    public void tamperingAndPathTraversalAreRejected() throws Exception {
        KeyPair k = key();
        JSONObject envelope =
                new JSONObject(new String(signed(payload(), k), StandardCharsets.UTF_8));
        JSONObject changed = payload().put("build", 2);
        envelope.put(
                "payload",
                Base64.getEncoder()
                        .encodeToString(changed.toString().getBytes(StandardCharsets.UTF_8)));
        try {
            AgentRelease.verify(
                    envelope.toString().getBytes(StandardCharsets.UTF_8),
                    k.getPublic().getEncoded());
            fail();
        } catch (java.io.IOException expected) {
        }
        JSONObject p = payload();
        p.getJSONArray("artifacts").getJSONObject(0).put("file", "../../private");
        AgentRelease r = AgentRelease.verify(signed(p, k), k.getPublic().getEncoded());
        try {
            r.artifact("arm64");
            fail();
        } catch (java.io.IOException expected) {
        }
    }

    @Test
    public void incompatibleProtocolAndWrongArchitectureAreRejected() throws Exception {
        KeyPair k = key();
        try {
            AgentRelease.verify(
                    signed(payload().put("protocol", 99), k), k.getPublic().getEncoded());
            fail();
        } catch (java.io.IOException expected) {
        }
        AgentRelease r = AgentRelease.verify(signed(payload(), k), k.getPublic().getEncoded());
        try {
            r.artifact("arm");
            fail();
        } catch (java.io.IOException expected) {
        }
    }
}
