package ru.ruscrafting.ecojobs.boost

import java.nio.file.Files
import java.nio.file.LinkOption
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
        if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            restrictPermissions(path)
            return load(path)
        }
        require(!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) { "secret.key exists but is not a regular file" }
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

    private fun load(path: Path): ByteArray {
        val encoded = Files.readString(path)
        require(encoded.endsWith('\n')) { "secret.key must end with a newline" }
        val line = encoded.dropLast(1).removeSuffix("\r")
        require(line.isNotEmpty() && line.none(Char::isWhitespace)) { "secret.key must contain one Base64 line" }
        return Base64.getDecoder().decode(line).also {
            require(it.size >= 32) { "secret.key is too short" }
        }
    }

    private fun restrictPermissions(path: Path) {
        runCatching {
            Files.setPosixFilePermissions(path, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
        }
    }
}
