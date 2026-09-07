package ru.ruscrafting.ecojobs.shop

import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.persistence.AtomicFileStore
import ru.ruscrafting.ecojobs.boost.VoucherService
import ru.ruscrafting.ecojobs.config.BoosterPreset
import ru.ruscrafting.ecojobs.config.BoosterPrice
import ru.ruscrafting.ecojobs.config.ShopCurrency
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Instant
import java.util.Base64
import java.util.UUID

interface ShopPaymentGateway {
    fun has(playerId: UUID, amount: BigDecimal): Boolean
    fun charge(playerId: UUID, amount: BigDecimal): PaymentOutcome

    /** Currency routing is explicit so a token purchase can never fall back to Vault. */
    fun forCurrency(currency: ShopCurrency): ShopPaymentGateway? =
        takeIf { currency == ShopCurrency.MONEY }
}
enum class PaymentOutcome { ACCEPTED, REJECTED, UNKNOWN }
interface ShopDeliveryGateway { fun hasRoom(playerId: UUID): Boolean; fun contains(playerId: UUID, voucherId: UUID): Boolean; fun deliver(playerId: UUID, itemBytes: ByteArray): DeliveryOutcome; fun save(playerId: UUID): Boolean }
enum class DeliveryOutcome { DELIVERED, FULL, FAILED }
enum class PurchaseState { RESERVED, CHARGING, CHARGED, DELIVERING, DELIVERED, FAILED }

data class ShopPurchase(val id: UUID, val playerId: UUID, val presetId: String, val voucherId: UUID, val price: BoosterPrice, val createdAt: Instant, val voucherBytes: String, val state: PurchaseState)
interface ShopPurchaseStore { fun findOpen(playerId: UUID, presetId: String): ShopPurchase?; fun create(purchase: ShopPurchase): ShopPurchase; fun update(purchase: ShopPurchase): ShopPurchase }

class AtomicShopPurchaseStore(dataFolder: Path) : ShopPurchaseStore {
    private val store = AtomicFileStore(dataFolder, Path.of("data/shop-purchases.txt"), 32L * 1024 * 1024,
        { records -> records.joinToString("\n") { it.encode() }.toByteArray(StandardCharsets.UTF_8) },
        { bytes -> bytes.toString(StandardCharsets.UTF_8).lineSequence().filter(String::isNotBlank).map(::decodePurchase).toList() })
    private val records = store.loadOrDefault { emptyList() }.associateBy { it.id }.toMutableMap()
    @Synchronized override fun findOpen(playerId: UUID, presetId: String) = records.values.firstOrNull { it.playerId == playerId && it.presetId == presetId && it.state != PurchaseState.DELIVERED && it.state != PurchaseState.FAILED }
    @Synchronized override fun create(purchase: ShopPurchase): ShopPurchase { require(findOpen(purchase.playerId, purchase.presetId) == null); return commit(records.toMutableMap().apply { put(purchase.id, purchase) }, purchase) }
    @Synchronized override fun update(purchase: ShopPurchase): ShopPurchase = commit(records.toMutableMap().apply { put(purchase.id, purchase.copy()) }, purchase)
    private fun commit(candidate: MutableMap<UUID, ShopPurchase>, result: ShopPurchase): ShopPurchase { store.write(candidate.values.sortedBy { it.createdAt }); records.clear(); records.putAll(candidate); return result }
    private fun ShopPurchase.encode() = listOf(id, playerId, presetId, voucherId, price.currency, price.amount, createdAt.epochSecond, voucherBytes, state).joinToString("|")
    private fun decodePurchase(raw: String): ShopPurchase { val p = raw.split('|'); require(p.size == 9); return ShopPurchase(UUID.fromString(p[0]), UUID.fromString(p[1]), p[2], UUID.fromString(p[3]), BoosterPrice(ru.ruscrafting.ecojobs.config.ShopCurrency.parse(p[4]), p[5].toBigDecimal()), Instant.ofEpochSecond(p[6].toLong()), p[7], PurchaseState.valueOf(p[8])) }
}

sealed interface PurchaseResult { data class Delivered(val purchase: ShopPurchase): PurchaseResult; data class Recovered(val purchase: ShopPurchase): PurchaseResult; data object ShopDisabled: PurchaseResult; data object Unavailable: PurchaseResult; data object InsufficientFunds: PurchaseResult; data object InventoryFull: PurchaseResult; data object PaymentFailed: PurchaseResult; data object PaymentPending: PurchaseResult; data object DeliveryFailed: PurchaseResult }

class BoosterShopService(private val enabled: () -> Boolean, private val store: ShopPurchaseStore, private val payment: ShopPaymentGateway, private val voucherFactory: (UUID, UUID, BoosterPreset) -> ByteArray) {
    fun buy(playerId: UUID, preset: BoosterPreset, delivery: ShopDeliveryGateway): PurchaseResult {
        if (!enabled()) return PurchaseResult.ShopDisabled
        store.findOpen(playerId, preset.id)?.let { return recover(playerId, it, delivery) }
        val price = preset.price ?: return PurchaseResult.Unavailable
        if (!preset.enabled || !preset.shopVisible) return PurchaseResult.Unavailable
        val currencyPayment = payment.forCurrency(price.currency) ?: return PurchaseResult.Unavailable
        if (!delivery.hasRoom(playerId)) return PurchaseResult.InventoryFull
        val id = UUID.randomUUID(); val purchase = store.create(ShopPurchase(UUID.randomUUID(), playerId, preset.id, id, price, Instant.now(), Base64.getEncoder().encodeToString(voucherFactory(playerId, id, preset)), PurchaseState.RESERVED))
        if (!runCatching { currencyPayment.has(playerId, price.amount) }.getOrDefault(false)) { transition(purchase, PurchaseState.FAILED); return PurchaseResult.InsufficientFunds }
        val charging = transition(purchase, PurchaseState.CHARGING)
        return when (runCatching { currencyPayment.charge(playerId, price.amount) }.getOrDefault(PaymentOutcome.UNKNOWN)) { PaymentOutcome.UNKNOWN -> PurchaseResult.PaymentPending; PaymentOutcome.REJECTED -> { transition(charging, PurchaseState.FAILED); PurchaseResult.PaymentFailed }; PaymentOutcome.ACCEPTED -> deliver(playerId, transition(charging, PurchaseState.CHARGED), delivery) }
    }
    private fun recover(playerId: UUID, purchase: ShopPurchase, delivery: ShopDeliveryGateway): PurchaseResult = when (purchase.state) { PurchaseState.CHARGING -> PurchaseResult.PaymentPending; PurchaseState.CHARGED, PurchaseState.DELIVERING -> deliver(playerId, purchase, delivery); PurchaseState.RESERVED -> { transition(purchase, PurchaseState.FAILED); PurchaseResult.PaymentFailed }; PurchaseState.DELIVERED -> PurchaseResult.Recovered(purchase); PurchaseState.FAILED -> PurchaseResult.Unavailable }
    private fun deliver(playerId: UUID, purchase: ShopPurchase, delivery: ShopDeliveryGateway): PurchaseResult {
        if (delivery.contains(playerId, purchase.voucherId)) {
            if (!delivery.save(playerId)) return PurchaseResult.DeliveryFailed
            return PurchaseResult.Recovered(transition(purchase, PurchaseState.DELIVERED))
        }
        // An item may have left the inventory before a crash. Never mint another copy
        // after delivery has started; the original signed voucher remains valid.
        if (purchase.state == PurchaseState.DELIVERING) return PurchaseResult.PaymentPending
        if (!delivery.hasRoom(playerId)) return PurchaseResult.InventoryFull
        val delivering = transition(purchase, PurchaseState.DELIVERING)
        return when (runCatching {
            delivery.deliver(playerId, Base64.getDecoder().decode(delivering.voucherBytes))
        }.getOrDefault(DeliveryOutcome.FAILED)) {
            DeliveryOutcome.FULL -> PurchaseResult.InventoryFull
            DeliveryOutcome.FAILED -> PurchaseResult.DeliveryFailed
            DeliveryOutcome.DELIVERED -> if (delivery.save(playerId)) {
                PurchaseResult.Delivered(transition(delivering, PurchaseState.DELIVERED))
            } else PurchaseResult.DeliveryFailed
        }
    }
    private fun transition(purchase: ShopPurchase, state: PurchaseState) = store.update(purchase.copy(state = state)).also {
        if (state == PurchaseState.DELIVERED && purchase.state != PurchaseState.DELIVERED) {
            ru.ruscrafting.ecojobs.integration.ArcProductTelemetry.purchased(it.playerId, it.id)
        }
    }
}

class PlayerShopDeliveryGateway(private val player: Player, private val vouchers: VoucherService) : ShopDeliveryGateway {
    override fun hasRoom(playerId: UUID) = player.inventory.storageContents.any { it == null || it.type.isAir }
    override fun contains(playerId: UUID, voucherId: UUID) = player.inventory.contents.any { vouchers.matchesVoucherId(it, voucherId) }
    override fun deliver(playerId: UUID, itemBytes: ByteArray) = runCatching { if (player.inventory.addItem(ItemStack.deserializeBytes(itemBytes)).isEmpty()) DeliveryOutcome.DELIVERED else DeliveryOutcome.FULL }.getOrDefault(DeliveryOutcome.FAILED)
    override fun save(playerId: UUID) = runCatching { player.saveData(); true }.getOrDefault(false)
}
