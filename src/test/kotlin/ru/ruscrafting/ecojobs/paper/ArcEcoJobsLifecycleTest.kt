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
import java.util.concurrent.atomic.AtomicInteger

class ArcEcoJobsLifecycleTest : FreeSpec({
    "reenable and a superseded waiting enable preserve the exact unfinished predecessor" {
        MockBukkitTestRuntime.open().use { paper ->
            listOf("EcoJobs", "LuckPerms", "PlaceholderAPI", "Vault", "ARC").forEach(paper::createSimplePlugin)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val preparations = AtomicInteger()
            mockkObject(SigningKeyStore)
            every { SigningKeyStore.loadOrCreate(any()) } answers {
                Bukkit.isPrimaryThread() shouldBe false
                if (preparations.incrementAndGet() == 1) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                ByteArray(32) { 1 }
            }
            var plugin: ArcEcoJobsPlugin? = null
            try {
                plugin = paper.loadPlugin<ArcEcoJobsPlugin>()
                awaitLifecycle(paper) { entered.count == 0L }
                paper.server.pluginManager.disablePlugin(plugin)
                plugin.drained().isDone shouldBe false
                plugin.onDisable()
                plugin.drained().isDone shouldBe false
                paper.server.pluginManager.enablePlugin(plugin)
                paper.performTicks(3)
                preparations.get() shouldBe 1
                paper.server.pluginManager.disablePlugin(plugin)
                plugin.drained().isDone shouldBe false
                paper.server.pluginManager.enablePlugin(plugin)
                paper.performTicks(3)
                preparations.get() shouldBe 1
                release.countDown()
                awaitLifecycle(paper) { preparations.get() == 2 }
                plugin.isEnabled shouldBe true
            } finally {
                release.countDown()
                plugin?.let { active ->
                    paper.server.pluginManager.disablePlugin(active)
                    active.drained().get(5, TimeUnit.SECONDS)
                }
                unmockkObject(SigningKeyStore)
            }
        }
    }

    "terminal ledger close remains part of the actual drain and a failed predecessor seals reenable" {
        MockBukkitTestRuntime.open().use { paper ->
            listOf("EcoJobs", "LuckPerms", "PlaceholderAPI", "Vault", "ARC").forEach(paper::createSimplePlugin)
            val bundled = AddonSettings.load(Path.of(System.getProperty("arcecojobs.projectDir"))
                .resolve("src/main/resources/config.yml").toFile())
            val enabled = bundled.copy(redemptionStorage = bundled.redemptionStorage.copy(enabled = true, password = "disposable-test"))
            val prepared = CountDownLatch(1)
            val releasePrepare = CountDownLatch(1)
            val closeEntered = CountDownLatch(1)
            val ledgerClose = CompletableFuture<Unit>()
            val opens = AtomicInteger()
            val ledger = mockk<MySqlOneTimeUseLedger> {
                every { closeAsync() } answers {
                    Bukkit.isPrimaryThread() shouldBe false
                    closeEntered.countDown()
                    ledgerClose
                }
            }
            mockkObject(AddonSettings.Companion, VoucherLedgerStorage, SigningKeyStore)
            every { AddonSettings.load(any()) } returns enabled
            every { VoucherLedgerStorage.open(any()) } answers { opens.incrementAndGet(); ledger }
            every { SigningKeyStore.loadOrCreate(any()) } answers {
                prepared.countDown()
                check(releasePrepare.await(5, TimeUnit.SECONDS))
                ByteArray(32) { 1 }
            }
            var plugin: ArcEcoJobsPlugin? = null
            try {
                plugin = paper.loadPlugin<ArcEcoJobsPlugin>()
                awaitLifecycle(paper) { prepared.count == 0L }
                paper.server.pluginManager.disablePlugin(plugin)
                releasePrepare.countDown()
                closeEntered.await(5, TimeUnit.SECONDS) shouldBe true
                plugin.drained().isDone shouldBe false
                paper.server.pluginManager.enablePlugin(plugin)
                paper.performTicks(3)
                opens.get() shouldBe 1
                ledgerClose.completeExceptionally(IllegalStateException("delayed terminal cleanup failed"))
                awaitLifecycle(paper) { !plugin.isEnabled }
                plugin.drained().isCompletedExceptionally shouldBe true
                opens.get() shouldBe 1
            } finally {
                releasePrepare.countDown()
                ledgerClose.complete(Unit)
                plugin?.let { active ->
                    paper.server.pluginManager.disablePlugin(active)
                    runCatching { active.drained().get(5, TimeUnit.SECONDS) }
                }
                unmockkObject(AddonSettings.Companion, VoucherLedgerStorage, SigningKeyStore)
            }
        }
    }
})

private fun awaitLifecycle(paper: MockBukkitTestRuntime, ready: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (!ready() && System.nanoTime() < deadline) { paper.performTicks(1); Thread.sleep(1) }
    ready() shouldBe true
}
