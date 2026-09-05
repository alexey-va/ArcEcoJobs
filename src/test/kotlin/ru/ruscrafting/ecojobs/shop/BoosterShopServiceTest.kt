package ru.ruscrafting.ecojobs.shop

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.ecojobs.config.*
import ru.ruscrafting.ecojobs.domain.BoostType
import java.math.BigDecimal
import java.nio.file.Files
import java.time.Duration
import java.util.UUID

class BoosterShopServiceTest : StringSpec({
    fun preset() = BoosterPreset("workday", true, BoostType.ALL, 150, Duration.ofHours(1), setOf("all"), BoosterItemDefinition(org.bukkit.Material.PAPER, null, null, "x", "x", false, false, emptySet(), emptyMap()), BoosterPrice(ShopCurrency.MONEY, BigDecimal("250")))
    open class Pay(var outcome: PaymentOutcome = PaymentOutcome.ACCEPTED, var balance: Boolean = true) : ShopPaymentGateway { var charges = 0; override fun has(playerId: UUID, amount: BigDecimal): Boolean = balance; open override fun charge(playerId: UUID, amount: BigDecimal): PaymentOutcome = outcome.also { if (it == PaymentOutcome.ACCEPTED) charges++ } }
    class TokenPay(outcome: PaymentOutcome = PaymentOutcome.ACCEPTED, balance: Boolean = true) : Pay(outcome, balance) {
        override fun forCurrency(currency: ShopCurrency): ShopPaymentGateway? = takeIf { currency == ShopCurrency.TOKENS }
    }
    open class Deliver(var room: Boolean = true, var result: DeliveryOutcome = DeliveryOutcome.DELIVERED) : ShopDeliveryGateway { var bytes = mutableListOf<ByteArray>(); var saves = 0; override fun hasRoom(playerId: UUID): Boolean = room; open override fun contains(playerId: UUID, voucherId: UUID): Boolean = false; override fun deliver(playerId: UUID, itemBytes: ByteArray): DeliveryOutcome = result.also { bytes += itemBytes }; override fun save(playerId: UUID): Boolean = true.also { saves++ } }
    fun service(root: java.nio.file.Path, pay: Pay) = BoosterShopService({ true }, AtomicShopPurchaseStore(root), pay) { _, _, _ -> byteArrayOf(1, 2, 3) }

    "insufficient funds never charges" { val p = UUID.randomUUID(); val pay = Pay(balance = false); service(Files.createTempDirectory("shop"), pay).buy(p, preset(), Deliver()) shouldBe PurchaseResult.InsufficientFunds; pay.charges shouldBe 0 }
    "token price routes to the named token gateway" {
        val p = UUID.randomUUID()
        val pay = TokenPay()
        val tokenPreset = preset().copy(price = BoosterPrice(ShopCurrency.TOKENS, BigDecimal("90")))
        (service(Files.createTempDirectory("shop"), pay).buy(p, tokenPreset, Deliver()) is PurchaseResult.Delivered) shouldBe true
        pay.charges shouldBe 1
    }
    "token price never falls back to the money gateway" {
        val p = UUID.randomUUID()
        val money = Pay()
        val tokenPreset = preset().copy(price = BoosterPrice(ShopCurrency.TOKENS, BigDecimal("90")))
        service(Files.createTempDirectory("shop"), money).buy(p, tokenPreset, Deliver()) shouldBe PurchaseResult.Unavailable
        money.charges shouldBe 0
    }
    "unknown provider remains pending and does not retry" { val p = UUID.randomUUID(); val pay = Pay(PaymentOutcome.UNKNOWN); val root = Files.createTempDirectory("shop"); val s = service(root, pay); s.buy(p, preset(), Deliver()) shouldBe PurchaseResult.PaymentPending; s.buy(p, preset(), Deliver()) shouldBe PurchaseResult.PaymentPending; pay.charges shouldBe 0 }
    "delivery failure after charge remains recoverable without a second charge" { val p = UUID.randomUUID(); val pay = Pay(); val d = Deliver(result = DeliveryOutcome.FAILED); val root = Files.createTempDirectory("shop"); service(root, pay).buy(p, preset(), d) shouldBe PurchaseResult.DeliveryFailed; pay.charges shouldBe 1; d.result = DeliveryOutcome.DELIVERED; service(root, pay).buy(p, preset(), d) shouldBe PurchaseResult.PaymentPending; d.bytes.size shouldBe 1; pay.charges shouldBe 1 }
    "full inventory rejects before reserving payment" { val p = UUID.randomUUID(); val pay = Pay(); val d = Deliver(room = false); service(Files.createTempDirectory("shop"), pay).buy(p, preset(), d) shouldBe PurchaseResult.InventoryFull; pay.charges shouldBe 0 }
    "delivering without the voucher remains pending and never reissues bytes" {
        val p = UUID.randomUUID(); val root = Files.createTempDirectory("shop"); val store = AtomicShopPurchaseStore(root)
        val purchase = ShopPurchase(UUID.randomUUID(), p, "workday", UUID.randomUUID(), preset().price!!, java.time.Instant.now(), "AQ==", PurchaseState.DELIVERING)
        store.create(purchase)
        val pay = Pay(); val d = Deliver(result = DeliveryOutcome.FAILED)
        val result = BoosterShopService({ true }, store, pay) { _, _, _ -> byteArrayOf(9) }.buy(p, preset(), d)
        result shouldBe PurchaseResult.PaymentPending
        d.bytes.size shouldBe 0
        pay.charges shouldBe 0
    }
    "delivering with voucher saves player before terminal recovery" {
        val p = UUID.randomUUID(); val root = Files.createTempDirectory("shop"); val store = AtomicShopPurchaseStore(root)
        val purchase = ShopPurchase(UUID.randomUUID(), p, "workday", UUID.randomUUID(), preset().price!!, java.time.Instant.now(), "AQ==", PurchaseState.DELIVERING)
        store.create(purchase)
        val d = object : Deliver() { override fun contains(playerId: UUID, voucherId: UUID) = true }
        val result = BoosterShopService({ true }, store, Pay()) { _, _, _ -> byteArrayOf(9) }.buy(p, preset(), d)
        (result is PurchaseResult.Recovered) shouldBe true
        d.saves shouldBe 1
        store.findOpen(p, "workday") shouldBe null
    }
    "charge unknown exception is pending and a repeated click does not charge" {
        val p = UUID.randomUUID(); val pay = object : Pay() { override fun charge(playerId: UUID, amount: BigDecimal): PaymentOutcome = throw IllegalStateException("unknown") }
        val root = Files.createTempDirectory("shop"); val d = Deliver(); val s = BoosterShopService({ true }, AtomicShopPurchaseStore(root), pay) { _, _, _ -> byteArrayOf(1) }
        s.buy(p, preset(), d) shouldBe PurchaseResult.PaymentPending
        s.buy(p, preset(), d) shouldBe PurchaseResult.PaymentPending
    }
    "a terminal delivered purchase permits a later deliberate purchase" {
        val p = UUID.randomUUID(); val pay = Pay(); val root = Files.createTempDirectory("shop"); val d = Deliver(); val s = BoosterShopService({ true }, AtomicShopPurchaseStore(root), pay) { _, _, _ -> byteArrayOf(1) }
        s.buy(p, preset(), d); s.buy(p, preset(), d)
        pay.charges shouldBe 2
    }
    "recovery delivers the originally snapshotted bytes after preset changes" {
        val player = UUID.randomUUID()
        val root = Files.createTempDirectory("shop")
        val pay = Pay()
        val delivery = Deliver()
        AtomicShopPurchaseStore(root).create(ShopPurchase(
            UUID.randomUUID(), player, "workday", UUID.randomUUID(), preset().price!!,
            java.time.Instant.now(), java.util.Base64.getEncoder().encodeToString(byteArrayOf(4, 5)), PurchaseState.CHARGED,
        ))
        val service = BoosterShopService({ true }, AtomicShopPurchaseStore(root), pay) { _, _, _ -> byteArrayOf(9, 9) }
        (service.buy(player, preset().copy(multiplierBasisPoints = 300), delivery) is PurchaseResult.Delivered) shouldBe true
        delivery.bytes.single().toList() shouldBe listOf<Byte>(4, 5)
        pay.charges shouldBe 0
    }
    "recovery keeps the original currency snapshot after repricing" {
        val player = UUID.randomUUID()
        val root = Files.createTempDirectory("shop")
        val oldPrice = BoosterPrice(ShopCurrency.TOKENS, BigDecimal("90"))
        val purchase = ShopPurchase(
            UUID.randomUUID(), player, "workday", UUID.randomUUID(), oldPrice,
            java.time.Instant.now(), java.util.Base64.getEncoder().encodeToString(byteArrayOf(6, 7)), PurchaseState.CHARGED,
        )
        AtomicShopPurchaseStore(root).create(purchase)
        val pay = TokenPay()
        val delivery = Deliver()
        val repriced = preset().copy(price = BoosterPrice(ShopCurrency.MONEY, BigDecimal("999")))

        val result = BoosterShopService({ true }, AtomicShopPurchaseStore(root), pay) { _, _, _ -> byteArrayOf(9) }
            .buy(player, repriced, delivery)

        (result is PurchaseResult.Delivered) shouldBe true
        pay.charges shouldBe 0
        delivery.bytes.single().toList() shouldBe listOf<Byte>(6, 7)
    }
    "hidden offers reject new purchases but recover an existing open purchase" {
        val player = UUID.randomUUID()
        val root = Files.createTempDirectory("shop")
        val hidden = preset().copy(shopVisible = false)
        val fresh = BoosterShopService({ true }, AtomicShopPurchaseStore(root), Pay()) { _, _, _ -> byteArrayOf(1) }
        fresh.buy(player, hidden, Deliver()) shouldBe PurchaseResult.Unavailable

        val purchase = ShopPurchase(
            UUID.randomUUID(), player, hidden.id, UUID.randomUUID(), hidden.price!!,
            java.time.Instant.now(), "AQ==", PurchaseState.CHARGED,
        )
        AtomicShopPurchaseStore(root).create(purchase)
        val recovered = BoosterShopService({ true }, AtomicShopPurchaseStore(root), Pay()) { _, _, _ -> byteArrayOf(9) }
            .buy(player, hidden, Deliver())
        (recovered is PurchaseResult.Delivered) shouldBe true
    }
})
