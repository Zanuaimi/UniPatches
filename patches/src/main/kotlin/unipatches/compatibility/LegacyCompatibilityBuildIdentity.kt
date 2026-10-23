package unipatches.compatibility

import java.io.File
import java.security.MessageDigest

internal data class LegacyCompatibilityImplementationIdentity(
    val scope: String,
    val sha256: String,
)

/** Fingerprints only the loaded patch implementation artifact/class, never the full catalog bundle. */
internal object LegacyCompatibilityBuildIdentity {
    fun current(): LegacyCompatibilityImplementationIdentity {
        val implementationClass = LegacyCompatibilityBuildIdentity::class.java
        val codeSourceFile = runCatching {
            implementationClass.protectionDomain?.codeSource?.location?.toURI()?.let(::File)
        }.getOrNull()
        if (codeSourceFile?.isFile == true) {
            return LegacyCompatibilityImplementationIdentity("code-source-file", sha256(codeSourceFile))
        }

        val resource = "/" + implementationClass.name.replace('.', '/') + ".class"
        val classBytes = implementationClass.getResourceAsStream(resource)?.use { it.readBytes() }
            ?: error("Legacy compatibility: unable to fingerprint loaded implementation class.")
        return LegacyCompatibilityImplementationIdentity("class-file", sha256(classBytes))
    }

    fun sha256(file: File): String = file.inputStream().buffered().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
