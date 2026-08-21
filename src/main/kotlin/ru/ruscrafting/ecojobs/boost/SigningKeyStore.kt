package ru.ruscrafting.ecojobs.boost

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import java.util.Base64

object SigningKeyStore {
    fun loadOrCreate(dataFolder: Path): ByteArray {
        Files.createDirectories(dataFolder)
        val path = dataFolder.resolve("secret.key")
        if (Files.isRegularFile(path)) {
            return Base64.getDecoder().decode(Files.readString(path).trim()).also {
                require(it.size >= 32) { "secret.key is too short" }
            }
        }
        val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
        Files.writeString(path, Base64.getEncoder().encodeToString(bytes) + System.lineSeparator())
        runCatching {
            Files.setPosixFilePermissions(path, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
        }
        return bytes
    }
}
