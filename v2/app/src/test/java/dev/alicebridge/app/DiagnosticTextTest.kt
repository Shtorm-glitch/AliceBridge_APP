package dev.alicebridge.app

import org.junit.Assert.*
import org.junit.Test

class DiagnosticTextTest {
    @Test fun verboseJsonMasksCompleteAuthorizationAndKeepsTroubleshootingFields() {
        val safe = DiagnosticText.redact("""{"authorization":"Bearer private value","http":503,"version":"1.2.3","password":"two words"}""")
        assertFalse(safe.contains("private"))
        assertFalse(safe.contains("two words"))
        assertTrue(safe.contains("503"))
        assertTrue(safe.contains("1.2.3"))
    }
    @Test fun querySecretsAndFeedbackTokensAreMasked() {
        val safe = DiagnosticText.redact("/register?activation_code=123456&platform=mini status.abcdef.ca-plum.alicebridge.invalid")
        assertFalse(safe.contains("123456"))
        assertFalse(safe.contains("abcdef"))
        assertTrue(safe.contains("platform=mini"))
    }
}
