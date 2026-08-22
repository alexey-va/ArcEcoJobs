package ru.ruscrafting.ecojobs.boost

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import java.util.Base64

object SigningKeyStore {
    fun loadOrCreate(dataFolder: Path): ByteArray {
        Files.createDirectories(dataFolder)
        val path = dataFolder.resolve("secret.key")
        if (Files.isRegularFile(path)) {
            restrictPermissions(path)
            return load(path)
        }
        require(!Files.exists(path)) { "secret.key exists but is not a regular file" }
        val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
        val temporary = Files.createTempFile(dataFolder, ".secret-key-", ".tmp")
        try {
            restrictPermissions(temporary)
            Files.writeString(
                temporary,
                Base64.getEncoder().encodeToString(bytes) + System.lineSeparator(),
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, path)
            }
            restrictPermissions(path)
        } finally {
            Files.deleteIfExists(temporary)
        }
        return bytes
    }

    private fun load(path: Path): ByteArray = Base64.getDecoder().decode(Files.readString(path).trim()).also {
        require(it.size >= 32) { "secret.key is too short" }
    }

    private fun restrictPermissions(path: Path) {
        runCatching {
            Files.setPosixFilePermissions(path, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
        }
    }
}
