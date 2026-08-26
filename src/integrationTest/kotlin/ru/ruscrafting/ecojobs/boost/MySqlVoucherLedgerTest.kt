package ru.ruscrafting.ecojobs.boost

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import ru.ruscrafting.ecojobs.config.RedemptionStorageSettings
import ru.ruscrafting.ecojobs.domain.BoostType
import ru.ruscrafting.ecojobs.domain.VoucherPayload
import ru.ruscrafting.ecojobs.testing.ArcEcoJobsMySqlFixture
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MySqlVoucherLedgerTest : StringSpec({
    lateinit var mysql: ArcEcoJobsMySqlFixture
    lateinit var settings: RedemptionStorageSettings

    beforeSpec {
        mysql = ArcEcoJobsMySqlFixture.start("arcecojobs_test")
        settings = mysql.settings
    }

    afterSpec { mysql.close() }

    "two server nodes can claim one voucher ID only once" {
        val firstNode = MySqlVoucherLedger.open(settings)
        val secondNode = MySqlVoucherLedger.open(settings)
        try {
            val payload = voucher()
            val players = listOf(UUID.randomUUID(), UUID.randomUUID())
            val nodes = listOf(firstNode, secondNode)
            val gate = CountDownLatch(1)
            val attempts = players.mapIndexed { index, player ->
                CompletableFuture.supplyAsync {
                    gate.await()
                    nodes[index] to nodes[index].claim(payload, player).join()
                }
            }
            gate.countDown()
            val results = attempts.map { it.join() }
            results.map { it.second }.filterIsInstance<VoucherClaimResult.Acquired>() shouldHaveSize 1
            results.map { it.second }.filterIsInstance<VoucherClaimResult.Busy>() shouldHaveSize 1

            val (ownerNode, result) = results.single { it.second is VoucherClaimResult.Acquired }
            val acquired = result.shouldBeInstanceOf<VoucherClaimResult.Acquired>()
            ownerNode.markApplied(acquired.claim).join() shouldBe true
            firstNode.claim(payload, players[0]).join() shouldBe VoucherClaimResult.AlreadyApplied
            secondNode.claim(payload, players[1]).join() shouldBe VoucherClaimResult.AlreadyApplied
        } finally {
            firstNode.close()
            secondNode.close()
        }
    }

    "active claim excludes copied attempts and safe release restores transferability" {
        MySqlVoucherLedger.open(settings).use { ledger ->
            val payload = voucher()
            val firstPlayer = UUID.randomUUID()
            val first = ledger.claim(payload, firstPlayer).join().shouldBeInstanceOf<VoucherClaimResult.Acquired>()
            first.claim.newlyCreated shouldBe true

            ledger.claim(payload, firstPlayer).join() shouldBe VoucherClaimResult.Busy
            ledger.claim(payload, UUID.randomUUID()).join() shouldBe VoucherClaimResult.Busy

            ledger.release(first.claim).join() shouldBe true
            val transferred = ledger.claim(payload, UUID.randomUUID()).join()
                .shouldBeInstanceOf<VoucherClaimResult.Acquired>()
            ledger.release(transferred.claim).join() shouldBe true
        }
    }

    "same player can recover a pending claim after the original node disconnects" {
        val payload = voucher()
        val player = UUID.randomUUID()
        val firstNode = MySqlVoucherLedger.open(settings)
        val original = firstNode.claim(payload, player).join().shouldBeInstanceOf<VoucherClaimResult.Acquired>()
        firstNode.close()

        MySqlVoucherLedger.open(settings).use { recoveredNode ->
            recoveredNode.claim(payload, UUID.randomUUID()).join() shouldBe VoucherClaimResult.Busy
            val recovered = recoveredNode.claim(payload, player).join()
                .shouldBeInstanceOf<VoucherClaimResult.Acquired>()
            recovered.claim.token shouldBe original.claim.token
            recovered.claim.newlyCreated shouldBe false
            recoveredNode.markApplied(recovered.claim).join() shouldBe true
        }
    }

    "completion cannot be starved when every pool connection is held by active claims" {
        MySqlVoucherLedger.open(settings).use { ledger ->
            val first = ledger.claim(voucher(), UUID.randomUUID()).join()
                .shouldBeInstanceOf<VoucherClaimResult.Acquired>()
            val second = ledger.claim(voucher(), UUID.randomUUID()).join()
                .shouldBeInstanceOf<VoucherClaimResult.Acquired>()
            val waiting = ledger.claim(voucher(), UUID.randomUUID())

            ledger.markApplied(first.claim).get(2, TimeUnit.SECONDS) shouldBe true
            val third = waiting.get(2, TimeUnit.SECONDS).shouldBeInstanceOf<VoucherClaimResult.Acquired>()
            ledger.release(second.claim).join() shouldBe true
            ledger.release(third.claim).join() shouldBe true
        }
    }

    "abandon releases the live lock but keeps an uncertain claim reserved" {
        MySqlVoucherLedger.open(settings).use { ledger ->
            val payload = voucher()
            val player = UUID.randomUUID()
            val original = ledger.claim(payload, player).join()
                .shouldBeInstanceOf<VoucherClaimResult.Acquired>()
            ledger.abandon(original.claim).join() shouldBe true

            ledger.claim(payload, UUID.randomUUID()).join() shouldBe VoucherClaimResult.Busy
            val recovered = ledger.claim(payload, player).join()
                .shouldBeInstanceOf<VoucherClaimResult.Acquired>()
            recovered.claim.newlyCreated shouldBe false
            recovered.claim.token shouldBe original.claim.token
            ledger.markApplied(recovered.claim).join() shouldBe true
        }
    }

    "one signed identity cannot be reused with different payload fields" {
        MySqlVoucherLedger.open(settings).use { ledger ->
            val payload = voucher()
            val claim = ledger.claim(payload, UUID.randomUUID()).join()
                .shouldBeInstanceOf<VoucherClaimResult.Acquired>()
            ledger.markApplied(claim.claim).join() shouldBe true
            ledger.claim(payload.copy(durationSeconds = 7_200), UUID.randomUUID()).join() shouldBe
                VoucherClaimResult.Conflict
        }
    }

    "applied redemption survives pool restart and migration replay" {
        val payload = voucher()
        val player = UUID.randomUUID()
        MySqlVoucherLedger.open(settings).use { ledger ->
            val claim = ledger.claim(payload, player).join().shouldBeInstanceOf<VoucherClaimResult.Acquired>().claim
            ledger.markApplied(claim).join() shouldBe true
        }
        MySqlVoucherLedger.open(settings).use { reopened ->
            reopened.claim(payload, UUID.randomUUID()).join() shouldBe VoucherClaimResult.AlreadyApplied
        }
    }
}) {
    companion object {
        private fun voucher(): VoucherPayload = VoucherPayload(
            presetId = "workday",
            voucherId = UUID.randomUUID(),
            type = BoostType.ALL,
            multiplierBasisPoints = 150,
            durationSeconds = 3_600,
            jobs = setOf("miner", "fisherman"),
            issuedAtEpochSecond = Instant.now().epochSecond,
        )
    }
}
