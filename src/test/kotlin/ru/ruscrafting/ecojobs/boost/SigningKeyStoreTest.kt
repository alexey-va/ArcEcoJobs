package ru.ruscrafting.ecojobs.boost

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.Base64

class SigningKeyStoreTest : StringSpec({
    "signing key creation is private, stable, and leaves no temporary file" {
        val folder = Files.createTempDirectory("arcecojobs-signing-key-")
        try {
            val created = SigningKeyStore.loadOrCreate(folder)
            val loaded = SigningKeyStore.loadOrCreate(folder)
            val path = folder.resolve("secret.key")

            created.size shouldBe 32
            loaded.contentEquals(created) shouldBe true
            Base64.getDecoder().decode(Files.readString(path).trim()).contentEquals(created) shouldBe true
            Files.list(folder).use { files ->
                files.noneMatch { it.fileName.toString().startsWith(".secret-key-") } shouldBe true
            }
            if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
                Files.getPosixFilePermissions(path) shouldBe setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                )
            }
        } finally {
            folder.toFile().deleteRecursively()
        }
    }

    "malformed existing signing key fails closed" {
        val folder = Files.createTempDirectory("arcecojobs-short-signing-key-")
        try {
            Files.writeString(folder.resolve("secret.key"), Base64.getEncoder().encodeToString(ByteArray(16)))
            shouldThrow<IllegalArgumentException> { SigningKeyStore.loadOrCreate(folder) }
        } finally {
            folder.toFile().deleteRecursively()
        }
    }


    "valid Base64 without the required trailing newline fails closed" {
        val folder = Files.createTempDirectory("arcecojobs-unterminated-signing-key-")
        try {
            Files.writeString(folder.resolve("secret.key"), Base64.getEncoder().encodeToString(ByteArray(32)))
            shouldThrow<IllegalArgumentException> { SigningKeyStore.loadOrCreate(folder) }
        } finally {
            folder.toFile().deleteRecursively()
        }
    }

    "symbolic link signing key fails closed" {
        val folder = Files.createTempDirectory("arcecojobs-linked-signing-key-")
        val target = Files.createTempFile("arcecojobs-signing-key-target-", ".key")
        try {
            Files.writeString(target, Base64.getEncoder().encodeToString(ByteArray(32)) + "\n")
            Files.createSymbolicLink(folder.resolve("secret.key"), target)

            shouldThrow<IllegalArgumentException> { SigningKeyStore.loadOrCreate(folder) }
        } finally {
            folder.toFile().deleteRecursively()
            Files.deleteIfExists(target)
        }
    }
})
