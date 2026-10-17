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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import ru.arc.core.BudgetedTaskQueue

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

/** Filesystem transitions run on storage; player/payment operations run through the lifecycle. */
class BoosterShopService(
    private val enabled: () -> Boolean,
    private val store: ShopPurchaseStore,
    private val payment: ShopPaymentGateway,
    private val storage: Executor,
    private val nativeWork: BudgetedTaskQueue,
    private val offerCurrent: (UUID, BoosterPreset) -> Boolean = { _, _ -> true },
    private val voucherFactory: (UUID, UUID, BoosterPreset) -> ByteArray,
) {
    private val inFlight = ConcurrentHashMap<UUID, CompletableFuture<PurchaseResult>>()

    fun buy(playerId: UUID, preset: BoosterPreset, delivery: ShopDeliveryGateway): CompletableFuture<PurchaseResult> {
        val result = CompletableFuture<PurchaseResult>()
        inFlight.putIfAbsent(playerId, result)?.let { return it }
        val operation = nativeWork.call({ true }) { enabled() }.thenCompose { available ->
            if (!available) CompletableFuture.completedFuture(PurchaseResult.ShopDisabled)
            else CompletableFuture.supplyAsync({ store.findOpen(playerId, preset.id) }, storage).thenCompose { existing ->
                if (existing != null) recover(playerId, existing, delivery)
                else reserve(playerId, preset, delivery)
            }
        }
        operation.whenComplete { value, failure ->
            inFlight.remove(playerId, result)
            if (failure == null) result.complete(value) else result.completeExceptionally(failure)
        }
        return result
    }

    private fun reserve(playerId: UUID, preset: BoosterPreset, delivery: ShopDeliveryGateway): CompletableFuture<PurchaseResult> =
        nativeWork.call({ true }) {
            val price = preset.price
            if (!enabled()) return@call PurchaseResult.ShopDisabled
            if (price == null || !preset.enabled || !preset.shopVisible || !offerCurrent(playerId, preset)) return@call PurchaseResult.Unavailable
            if (payment.forCurrency(price.currency) == null) return@call PurchaseResult.Unavailable
            if (!delivery.hasRoom(playerId)) return@call PurchaseResult.InventoryFull
            val voucherId = UUID.randomUUID()
            ShopPurchase(UUID.randomUUID(), playerId, preset.id, voucherId, price, Instant.now(),
                Base64.getEncoder().encodeToString(voucherFactory(playerId, voucherId, preset)), PurchaseState.RESERVED)
        }.thenCompose { prepared ->
            if (prepared is PurchaseResult) CompletableFuture.completedFuture(prepared)
            else CompletableFuture.supplyAsync({ store.create(prepared as ShopPurchase) }, storage).thenCompose { reserved ->
                nativeWork.call({ true }) {
                    when {
                        !enabled() || !offerCurrent(playerId, preset) -> PurchaseResult.Unavailable
                        !runCatching { payment.forCurrency(reserved.price.currency)?.has(playerId, reserved.price.amount) == true }.getOrDefault(false) -> PurchaseResult.InsufficientFunds
                        else -> null
                    }
                }.thenCompose { rejected ->
                    if (rejected != null) transition(reserved, PurchaseState.FAILED).thenApply { rejected }
                    else transition(reserved, PurchaseState.CHARGING).thenCompose { charging ->
                        nativeWork.call({ true }) {
                            // No provider call may run after logout, permission loss or an offer replacement.
                            if (!enabled() || !offerCurrent(playerId, preset)) null
                            else runCatching { payment.forCurrency(charging.price.currency)?.charge(playerId, charging.price.amount) }
                                .getOrDefault(PaymentOutcome.UNKNOWN) ?: PaymentOutcome.UNKNOWN
                        }.thenCompose { outcome ->
                            when (outcome) {
                                null -> transition(charging, PurchaseState.FAILED).thenApply { PurchaseResult.Unavailable }
                                PaymentOutcome.UNKNOWN -> CompletableFuture.completedFuture(PurchaseResult.PaymentPending)
                                PaymentOutcome.REJECTED -> transition(charging, PurchaseState.FAILED).thenApply { PurchaseResult.PaymentFailed }
                                PaymentOutcome.ACCEPTED -> transition(charging, PurchaseState.CHARGED).thenCompose { deliver(playerId, it, delivery) }
                            }
                        }
                    }
                }
            }
        }

    private fun recover(playerId: UUID, purchase: ShopPurchase, delivery: ShopDeliveryGateway): CompletableFuture<PurchaseResult> =
        when (purchase.state) {
            PurchaseState.CHARGING -> CompletableFuture.completedFuture(PurchaseResult.PaymentPending)
            PurchaseState.CHARGED, PurchaseState.DELIVERING -> deliver(playerId, purchase, delivery)
            PurchaseState.RESERVED -> transition(purchase, PurchaseState.FAILED).thenApply { PurchaseResult.PaymentFailed }
            PurchaseState.DELIVERED -> CompletableFuture.completedFuture(PurchaseResult.Recovered(purchase))
            PurchaseState.FAILED -> CompletableFuture.completedFuture(PurchaseResult.Unavailable)
        }

    private fun deliver(playerId: UUID, purchase: ShopPurchase, delivery: ShopDeliveryGateway): CompletableFuture<PurchaseResult> =
        nativeWork.call({ true }) {
            when {
                delivery.contains(playerId, purchase.voucherId) -> if (delivery.save(playerId)) null else PurchaseResult.DeliveryFailed
                // Never reissue bytes after delivery began; the signed voucher may have left the inventory.
                purchase.state == PurchaseState.DELIVERING -> PurchaseResult.PaymentPending
                !delivery.hasRoom(playerId) -> PurchaseResult.InventoryFull
                else -> purchase
            }
        }.thenCompose { inspected ->
            when (inspected) {
                null -> transition(purchase, PurchaseState.DELIVERED).thenApply { PurchaseResult.Recovered(it) }
                is PurchaseResult -> CompletableFuture.completedFuture(inspected)
                else -> transition(purchase, PurchaseState.DELIVERING).thenCompose { delivering ->
                    nativeWork.call({ true }) {
                        when (runCatching { delivery.deliver(playerId, Base64.getDecoder().decode(delivering.voucherBytes)) }
                            .getOrDefault(DeliveryOutcome.FAILED)) {
                            DeliveryOutcome.FULL -> PurchaseResult.InventoryFull
                            DeliveryOutcome.FAILED -> PurchaseResult.DeliveryFailed
                            DeliveryOutcome.DELIVERED -> if (delivery.save(playerId)) null else PurchaseResult.DeliveryFailed
                        }
                    }.thenCompose { rejected ->
                        if (rejected != null) CompletableFuture.completedFuture(rejected)
                        else transition(delivering, PurchaseState.DELIVERED).thenApply { PurchaseResult.Delivered(it) }
                    }
                }
            }
        }

    private fun transition(purchase: ShopPurchase, state: PurchaseState): CompletableFuture<ShopPurchase> =
        CompletableFuture.supplyAsync({ store.update(purchase.copy(state = state)) }, storage).thenCompose { updated ->
            if (state != PurchaseState.DELIVERED || purchase.state == PurchaseState.DELIVERED) CompletableFuture.completedFuture(updated)
            else nativeWork.call({ true }) { ru.ruscrafting.ecojobs.integration.ArcProductTelemetry.purchased(updated.playerId, updated.id); updated }
        }
}

class PlayerShopDeliveryGateway(private val player: Player, private val vouchers: VoucherService) : ShopDeliveryGateway {
    override fun hasRoom(playerId: UUID) = player.isOnline && player.inventory.storageContents.any { it == null || it.type.isAir }
    override fun contains(playerId: UUID, voucherId: UUID) = player.isOnline && player.inventory.contents.any { vouchers.matchesVoucherId(it, voucherId) }
    override fun deliver(playerId: UUID, itemBytes: ByteArray) = runCatching { if (!player.isOnline) DeliveryOutcome.FAILED else if (player.inventory.addItem(ItemStack.deserializeBytes(itemBytes)).isEmpty()) DeliveryOutcome.DELIVERED else DeliveryOutcome.FULL }.getOrDefault(DeliveryOutcome.FAILED)
    override fun save(playerId: UUID) = runCatching { check(player.isOnline); player.saveData(); true }.getOrDefault(false)
}
