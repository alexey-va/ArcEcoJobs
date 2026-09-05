package ru.ruscrafting.ecojobs.shop

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.ecojobs.config.BoosterPrice
import ru.ruscrafting.ecojobs.config.ShopCurrency
import java.math.BigDecimal
import java.nio.file.Files
import java.time.Instant
import java.util.UUID

class AtomicShopPurchaseStoreTest : StringSpec({
    "persists charged purchase and finds it for recovery after a new store instance" {
        val root = Files.createTempDirectory("arcecojobs-shop")
        val player = UUID.randomUUID()
        val purchase = ShopPurchase(
            UUID.randomUUID(), player, "workday", UUID.randomUUID(),
            BoosterPrice(ShopCurrency.MONEY, BigDecimal("250")), Instant.now(), "AQ==", PurchaseState.CHARGED,
        )
        AtomicShopPurchaseStore(root).create(purchase)

        val recovered = AtomicShopPurchaseStore(root).findOpen(player, "workday")
        recovered?.id shouldBe purchase.id
        recovered?.voucherId shouldBe purchase.voucherId
        recovered?.state shouldBe PurchaseState.CHARGED
    }

    "terminal failed purchase is not reused by the next click" {
        val root = Files.createTempDirectory("arcecojobs-shop")
        val player = UUID.randomUUID()
        val purchase = ShopPurchase(
            UUID.randomUUID(), player, "workday", UUID.randomUUID(),
            BoosterPrice(ShopCurrency.MONEY, BigDecimal("250")), Instant.now(), "AQ==", PurchaseState.FAILED,
        )
        val store = AtomicShopPurchaseStore(root)
        store.create(purchase)
        store.findOpen(player, "workday") shouldBe null
    }
})
