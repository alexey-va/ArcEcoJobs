package ru.ruscrafting.ecojobs.paper

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.bukkit.Bukkit
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.loadPlugin
import ru.arc.sql.onetime.MySqlOneTimeUseLedger
import ru.ruscrafting.ecojobs.boost.SigningKeyStore
import ru.ruscrafting.ecojobs.boost.VoucherLedgerStorage
import ru.ruscrafting.ecojobs.config.AddonSettings
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ArcEcoJobsLifecycleTest : FreeSpec({
    "maintenance enable waits for IO preparation and handles failure before returning" {
        MockBukkitTestRuntime.open().use { paper ->
            listOf("EcoJobs", "LuckPerms", "PlaceholderAPI", "Vault", "ARC").forEach(paper::createSimplePlugin)
            var preparations = 0
            mockkObject(SigningKeyStore)
            every { SigningKeyStore.loadOrCreate(any()) } answers {
                Bukkit.isPrimaryThread() shouldBe false
                preparations++
                throw IllegalStateException("isolated signing preparation failure")
            }
            try {
                val plugin = paper.loadPlugin<ArcEcoJobsPlugin>()
                preparations shouldBe 1
                plugin.isEnabled shouldBe false
                plugin.drained().isDone shouldBe true
                plugin.onDisable()
                preparations shouldBe 1
            } finally { unmockkObject(SigningKeyStore) }
        }
    }

    "maintenance failure drains the actual ledger before enable returns" {
        MockBukkitTestRuntime.open().use { paper ->
            listOf("EcoJobs", "LuckPerms", "PlaceholderAPI", "Vault", "ARC").forEach(paper::createSimplePlugin)
            val bundled = AddonSettings.load(Path.of(System.getProperty("arcecojobs.projectDir"))
                .resolve("src/main/resources/config.yml").toFile())
            val enabled = bundled.copy(redemptionStorage = bundled.redemptionStorage.copy(enabled = true, password = "disposable-test"))
            val closeEntered = CountDownLatch(1)
            val ledgerClose = CompletableFuture<Unit>()
            val returned = AtomicBoolean()
            val observedBeforeReturn = AtomicBoolean()
            val ledger = mockk<MySqlOneTimeUseLedger> {
                every { closeAsync() } answers {
                    Bukkit.isPrimaryThread() shouldBe false
                    closeEntered.countDown()
                    ledgerClose
                }
            }
            mockkObject(AddonSettings.Companion, VoucherLedgerStorage, SigningKeyStore)
            every { AddonSettings.load(any()) } returns enabled
            every { VoucherLedgerStorage.open(any()) } returns ledger
            every { SigningKeyStore.loadOrCreate(any()) } throws IllegalStateException("isolated startup failure after acquisition")
            val release = Thread {
                try {
                    if (closeEntered.await(5, TimeUnit.SECONDS)) observedBeforeReturn.set(!returned.get())
                } finally { ledgerClose.complete(Unit) }
            }.apply { start() }
            try {
                val plugin = paper.loadPlugin<ArcEcoJobsPlugin>()
                returned.set(true)
                observedBeforeReturn.get() shouldBe true
                plugin.isEnabled shouldBe false
                plugin.drained().isDone shouldBe true
                plugin.drained().join()
            } finally {
                ledgerClose.complete(Unit)
                release.join(5_000)
                unmockkObject(AddonSettings.Companion, VoucherLedgerStorage, SigningKeyStore)
            }
        }
    }
})
