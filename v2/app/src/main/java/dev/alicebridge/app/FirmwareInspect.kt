package dev.alicebridge.app

import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.zip.CRC32

/** Local inspection of a user-selected firmware archive. Never executes or flashes it. */
data class FirmwareInspection(
    val format: String,
    val size: Long,
    val sha256: String,
    val crc32: Long,
    val signed: Boolean,
    val signatureValid: Boolean,
    val signer: String,
    val matchesOtaCert: Boolean?,
    val message: String,
)

object FirmwareInspect {
    private val SIGNATURE = Regex("META-INF/.*\\.(RSA|DSA|EC)", RegexOption.IGNORE_CASE)
    private const val OTACERT = "META-INF/com/google/android/otacert"

    fun inspect(file: File): FirmwareInspection {
        require(file.isFile && file.length() > 0) { "Файл прошивки пуст или недоступен" }
        val magic = ByteArray(8).also { file.inputStream().use { input -> input.read(it) } }
        val format = when {
            magic.size >= 4 && magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte() -> "ZIP"
            magic.size >= 6 && magic.copyOf(6).contentEquals(byteArrayOf('0'.code.toByte(), '7'.code.toByte(), '0'.code.toByte(), '7'.code.toByte(), '0'.code.toByte(), '1'.code.toByte())) -> "CPIO · SWUpdate"
            magic.size >= 6 && magic.copyOf(6).contentEquals(byteArrayOf('0'.code.toByte(), '7'.code.toByte(), '0'.code.toByte(), '7'.code.toByte(), '0'.code.toByte(), '2'.code.toByte())) -> "CPIO · SWUpdate"
            magic.size >= 6 && magic[0] == 0xFD.toByte() && magic[1] == 0x37.toByte() && magic[2] == 0x7A.toByte() && magic[3] == 0x58.toByte() && magic[4] == 0x5A.toByte() -> "XZ · SWUpdate"
            else -> "неизвестный"
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val crc = CRC32()
        val buffer = ByteArray(1 shl 16)
        file.inputStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read); crc.update(buffer, 0, read)
            }
        }
        val base = FirmwareInspection(format, file.length(), digest.digest().joinToString("") { "%02x".format(it) },
            crc.value, false, false, "", null, "")
        if (format != "ZIP") return base.copy(message = "У формата $format нет встроенной подписи. Проверены только размер, SHA-256 и CRC32; подлинность файла подтвердить нельзя.")
        return inspectZip(file, base)
    }

    private fun inspectZip(file: File, base: FirmwareInspection): FirmwareInspection {
        var signatureFiles = false
        var signerCert: X509Certificate? = null
        var failure = ""
        var signedEntries = 0
        try {
            JarFile(file, true).use { jar ->
                val entries = jar.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    if (SIGNATURE.matches(entry.name)) signatureFiles = true
                    jar.getInputStream(entry).use { input ->
                        val buffer = ByteArray(1 shl 16)
                        while (input.read(buffer) >= 0) { /* verification happens while reading */ }
                    }
                    val signers = (entry as? JarEntry)?.codeSigners.orEmpty()
                    if (signers.isNotEmpty()) {
                        signedEntries++
                        val certificate = signers.first().signerCertPath?.certificates?.firstOrNull() as? X509Certificate
                        if (certificate != null) signerCert = certificate
                    }
                }
            }
        } catch (error: SecurityException) {
            failure = error.message ?: "подпись не совпадает"
        } catch (error: Exception) {
            failure = error.message ?: error.javaClass.simpleName
        }
        val signed = signatureFiles || signedEntries > 0
        val valid = signed && failure.isEmpty() && signedEntries > 0
        val otacert = otacert(file)
        val matches = when {
            otacert == null || signerCert == null -> null
            else -> otacert.encoded.contentEquals(signerCert.encoded)
        }
        val signer = signerCert?.subjectX500Principal?.name.orEmpty()
        val message = when {
            !signed -> "В ZIP нет встроенной подписи (META-INF/*.RSA). Подлинность файла не подтверждена; SHA-256 показан для сверки вручную."
            !valid -> "Подпись недействительна: $failure. Файл повреждён или изменён после подписи."
            matches == false -> "Подпись проверена ($signer), но сертификат не совпадает с META-INF/com/google/android/otacert."
            matches == true -> "Подпись проверена ($signer) и совпадает с otacert."
            else -> "Подпись проверена ($signer). otacert в архиве отсутствует, поэтому сравнить сертификат не с чем."
        }
        return base.copy(signed = signed, signatureValid = valid, signer = signer, matchesOtaCert = matches, message = message)
    }

    private fun otacert(file: File): X509Certificate? = try {
        JarFile(file).use { jar ->
            val entry = jar.getJarEntry(OTACERT) ?: return null
            val bytes = jar.getInputStream(entry).use { it.readBytes() }
            CertificateFactory.getInstance("X.509").generateCertificate(bytes.inputStream()) as X509Certificate
        }
    } catch (_: Exception) { null }
}
