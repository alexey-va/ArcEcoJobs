package ru.ruscrafting.ecojobs.integration

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.player.PaperOfflinePlayerData
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

class EcoJobsBridgeTest : FreeSpec({
    "leaderboard inventory keeps saved UUIDs and prefers native cached names before worker fallback" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("EcoJobsPopulationTest")
            val online = paper.addPlayer("OnlineName")
            val directory = Files.createTempDirectory("ecojobs-population")
            val cached = UUID.randomUUID()
            val persisted = UUID.randomUUID()
            val corrupt = UUID.randomUUID()
            writePersistedName(directory, cached, "OldSavedName")
            writePersistedName(directory, persisted, "SavedName")
            Files.write(directory.resolve("$corrupt.dat"), byteArrayOf(1, 2, 3))
            var nativeReads = 0
            listOf(cached to "CachedName", persisted to null, corrupt to null).forEach { (id, name) ->
                val profile = paper.server.createProfileExact(id, name)
                paper.server.playerList.addOfflinePlayer(mockk<OfflinePlayer> {
                    every { uniqueId } returns id
                    every { playerProfile } answers {
                        Bukkit.isPrimaryThread() shouldBe true
                        nativeReads++
                        profile
                    }
                    every { getName() } throws AssertionError("Cold OfflinePlayer.name must not be used")
                })
            }
            val scheduler = TestTaskScheduler()
            val tasks = LifecycleTaskScope(scheduler)
            val work = BudgetedTaskQueue(tasks, maximumSteps = 1)
            val worker = BoundedAsyncExecutor("ecojobs-population-test")
            val bridge = EcoJobsBridge(plugin, work, worker, PaperOfflinePlayerData(directory)) { error("No settings needed for population capture") }
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            worker.submit { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            try {
                entered.await(5, TimeUnit.SECONDS) shouldBe true
                val captured = bridge.captureLeaderboardPlayers()
                scheduler.tick(5)
                captured.isDone shouldBe false
                nativeReads shouldBe 0
                release.countDown()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (!captured.isDone && System.nanoTime() < deadline) {
                    val before = nativeReads
                    scheduler.tick()
                    (nativeReads - before <= 1) shouldBe true
                    Thread.sleep(1)
                }
                captured.get(5, TimeUnit.SECONDS).associate { it.uuid to it.name } shouldBe mapOf(
                    online.uniqueId to "OnlineName", cached to "CachedName", persisted to "SavedName", corrupt to corrupt.toString().take(8),
                )
                nativeReads shouldBe 3
            } finally {
                release.countDown()
                bridge.shutdown()
                work.close()
                tasks.close()
                worker.closeAsync().get(5, TimeUnit.SECONDS)
                directory.toFile().deleteRecursively()
            }
        }
    }
})

private fun writePersistedName(directory: Path, id: UUID, name: String) {
    DataOutputStream(GZIPOutputStream(Files.newOutputStream(directory.resolve("$id.dat")))).use { out ->
        out.writeByte(10); out.writeUTF("")
        out.writeByte(10); out.writeUTF("bukkit")
        out.writeByte(8); out.writeUTF("lastKnownName"); out.writeUTF(name)
        out.writeByte(0); out.writeByte(0)
    }
}
