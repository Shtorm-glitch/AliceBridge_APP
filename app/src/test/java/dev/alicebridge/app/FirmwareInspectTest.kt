package dev.alicebridge.app
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FirmwareInspectTest {
    private fun fixture(name: String): File {
        val bytes = javaClass.getResourceAsStream("/firmware/$name")!!.readBytes()
        return Files.createTempFile("firmware-", ".zip").toFile().also { it.writeBytes(bytes) }
    }

    @Test fun signedArchiveIsAcceptedWithSigner() {
        val result = FirmwareInspect.inspect(fixture("signed-ota.zip"))
        assertEquals("ZIP", result.format)
        assertTrue(result.signed)
        assertTrue(result.signatureValid)
        assertTrue(result.signer.contains("CN=AliceBridge Test"))
        assertTrue(result.sha256.matches(Regex("[0-9a-f]{64}")))
        assertTrue(result.size > 0)
    }

    @Test fun modifiedArchiveIsRejected() {
        val result = FirmwareInspect.inspect(fixture("tampered-ota.zip"))
        assertTrue(result.signed)
        assertFalse(result.signatureValid)
        assertTrue(result.message.contains("недействительна"))
    }

    @Test fun unsignedZipIsNotPresentedAsVerified() {
        val file = Files.createTempFile("unsigned-", ".zip").toFile()
        java.util.zip.ZipOutputStream(file.outputStream()).use {
            it.putNextEntry(java.util.zip.ZipEntry("payload"))
            it.write("hello".toByteArray())
        }
        val result = FirmwareInspect.inspect(file)
        assertFalse(result.signed)
        assertFalse(result.signatureValid)
        assertTrue(result.message.contains("нет встроенной подписи"))
    }

    @Test fun cpioHasNoEmbeddedSignature() {
        val file = Files.createTempFile("cpio-", ".bin").toFile()
        file.writeBytes("070701".toByteArray() + ByteArray(64))
        val result = FirmwareInspect.inspect(file)
        assertTrue(result.format.startsWith("CPIO"))
        assertFalse(result.signed)
        assertTrue(result.message.contains("нет встроенной подписи"))
    }
}
