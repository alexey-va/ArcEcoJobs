package ru.ruscrafting.ecojobs.paper

import com.willfp.eco.core.integrations.economy.EconomyManager
import net.luckperms.api.LuckPerms
import net.milkbowl.vault.economy.Economy
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.whenCompleteSync
import java.util.concurrent.CompletableFuture
import ru.arc.core.ScheduledTask
import ru.arc.observability.RuntimeHealthContribution
import ru.arc.observability.RuntimeHealthState
import ru.arc.onetime.OneTimeUseLedger
import ru.arc.onetime.UnavailableOneTimeUseLedger
import ru.arc.paper.runtime.PaperPluginRuntime
import ru.arc.paper.sql.PaperSqlServices
import ru.ruscrafting.ecojobs.boost.BoostService
import ru.ruscrafting.ecojobs.boost.SigningKeyStore
import ru.ruscrafting.ecojobs.boost.VoucherLedgerStorage
import ru.ruscrafting.ecojobs.boost.VoucherService
import ru.ruscrafting.ecojobs.config.AddonSettings
import ru.ruscrafting.ecojobs.config.BoosterRegistry
import ru.ruscrafting.ecojobs.config.JobsLocale
import ru.ruscrafting.ecojobs.domain.BoostNodeCodec
import ru.ruscrafting.ecojobs.earnings.EarningsService
import ru.ruscrafting.ecojobs.earnings.MoneyAttribution
import ru.ruscrafting.ecojobs.earnings.MySqlHourlyEarningsStore
import ru.ruscrafting.ecojobs.exploration.DiscoveryLedger
import ru.ruscrafting.ecojobs.exploration.LibreforgeExplorationTrigger
import ru.ruscrafting.ecojobs.exploration.MySqlDiscoveryLedger
import ru.ruscrafting.ecojobs.exploration.UnavailableDiscoveryLedger
import ru.ruscrafting.ecojobs.integration.BoostPlaceholderExpansion
import ru.ruscrafting.ecojobs.integration.EcoJobsBridge
import ru.ruscrafting.ecojobs.integration.ArcAuditBridge
import ru.ruscrafting.ecojobs.integration.VaultEconomyIntegration
import ru.ruscrafting.ecojobs.integration.VaultShopPaymentGateway
import ru.ruscrafting.ecojobs.integration.RedisTokenShopPaymentGateway
import ru.ruscrafting.ecojobs.integration.RoutedShopPaymentGateway
import ru.ruscrafting.ecojobs.shop.AtomicShopPurchaseStore
import ru.ruscrafting.ecojobs.shop.BoosterShopService
import java.util.concurrent.TimeUnit
import java.util.logging.Level

open class ArcEcoJobsPlugin : JavaPlugin() {
    private lateinit var settings: AddonSettings
    private lateinit var menuLayouts: JobsMenuLayouts
    private lateinit var jobsMenu: JobsMenu
    private lateinit var boosterRegistry: BoosterRegistry
    private lateinit var locale: JobsLocale
    private lateinit var ecoJobs: EcoJobsBridge
    private lateinit var boosts: BoostService
    private lateinit var vouchers: VoucherService
    private lateinit var shop: BoosterShopService
    private lateinit var moneyIntegration: VaultEconomyIntegration
    private var voucherLedger: OneTimeUseLedger = UnavailableOneTimeUseLedger
    private var discoveryLedger: DiscoveryLedger = UnavailableDiscoveryLedger
    private lateinit var rewardGuard: ru.ruscrafting.ecojobs.antifarm.JobRewardGuard
    private var earnings: EarningsService? = null
    private var moneyAttribution: MoneyAttribution? = null
    private var explorationListener: ExplorationListener? = null
    private var expansion: BoostPlaceholderExpansion? = null
    private var pluginRuntime: PaperPluginRuntime? = null
    private var bootstrapTask: ScheduledTask? = null
    private var initialized = false
    private lateinit var storage: BoundedAsyncExecutor
    private lateinit var nativeWork: BudgetedTaskQueue
    private var reloadInFlight: CompletableFuture<Result<Unit>>? = null
    private val storageResources = java.util.concurrent.ConcurrentLinkedDeque<AutoCloseable>()
    private var sqlBinding: AutoCloseable? = null
    private var shutdownDrain = CompletableFuture.completedFuture(Unit)
    private var explorationDrain = CompletableFuture.completedFuture(Unit)
    internal fun drained(): CompletableFuture<Unit> = shutdownDrain.copy()

    private data class Prepared(
        val settings: AddonSettings,
        val layouts: JobsMenuLayouts,
        val locale: JobsLocale,
        val voucherLedger: OneTimeUseLedger,
        val discoveryLedger: DiscoveryLedger,
        val earningsStore: MySqlHourlyEarningsStore?,
        val signingKey: ByteArray,
        val shopStore: AtomicShopPurchaseStore,
    )

    override fun onEnable() {
        val lifecycle = PaperPluginRuntime(this, "arc-ecojobs", BukkitTaskScheduler(this)).also {
            pluginRuntime = it
            it.start("version" to pluginMeta.version)
        }
        try {
            storage = BoundedAsyncExecutor("arc-ecojobs-storage")
            shutdownDrain = CompletableFuture()
            explorationDrain = CompletableFuture.completedFuture(Unit)
            sqlBinding = PaperSqlServices.installConsumer(this, shutdownDrain)
            nativeWork = lifecycle.own(BudgetedTaskQueue(lifecycle.tasks))
            // Maintenance startup is sequential; this helper only performs IO/codec work.
            initialize(lifecycle, storage.submit { prepare() }.get())
        } catch (failure: Throwable) {
            logger.log(Level.SEVERE, "ArcEcoJobs failed closed during startup", failure)
            server.pluginManager.disablePlugin(this)
        }
    }

    /** Worker-only: none of these parsers construct player inventories or native item stacks. */
    private fun prepare(): Prepared {
        saveDefaultConfig()
        saveResourceIfMissing("boosters.yml")
        saveResourceIfMissing("lang/ru.yml")
        saveResourceIfMissing("lang/en.yml")
        val candidate = AddonSettings.load(dataFolder.resolve("config.yml"))
        val layouts = JobsMenuLayouts(dataFolder.toPath())
        val texts = JobsLocale(dataFolder) { settings }.also(JobsLocale::validate)
        val vouchers = if (candidate.redemptionStorage.enabled) VoucherLedgerStorage.open(candidate.redemptionStorage).also(storageResources::add)
            else UnavailableOneTimeUseLedger
        val discoveries = if (candidate.exploration.enabled) MySqlDiscoveryLedger.open(candidate.redemptionStorage).also(storageResources::add)
            else UnavailableDiscoveryLedger
        val earnings = if (candidate.earnings.enabled) runCatching { MySqlHourlyEarningsStore.open(candidate.redemptionStorage) }
            .onFailure { logger.log(Level.SEVERE, "Hourly job earnings analytics is unavailable; jobs and payouts will continue", it) }.getOrNull() else null
        // Track the pool until its main-thread EarningsService takes ownership.
        earnings?.let(storageResources::add)
        return Prepared(candidate, layouts, texts, vouchers, discoveries, earnings,
            SigningKeyStore.loadOrCreate(dataFolder.toPath()), AtomicShopPurchaseStore(dataFolder.toPath()))
    }

    private fun initialize(lifecycle: PaperPluginRuntime, prepared: Prepared) {
            settings = prepared.settings
            menuLayouts = prepared.layouts
            locale = prepared.locale
            val jobs = EcoJobsBridge(this, nativeWork, storage, ru.arc.paper.player.PaperOfflinePlayerData.capture(server)) { settings }
            ecoJobs = jobs
            lifecycle.own(AutoCloseable(jobs::shutdown))
            val luckPerms = requireNotNull(server.servicesManager.load(LuckPerms::class.java)) { "LuckPerms API is unavailable" }
            voucherLedger = prepared.voucherLedger
            discoveryLedger = prepared.discoveryLedger
            moneyAttribution = MoneyAttribution()
            rewardGuard = ru.ruscrafting.ecojobs.antifarm.JobRewardGuard({ settings }, locale,
                isSlayerActive = { player -> ecoJobs.job("slayer")?.let { ecoJobs.active(player, it) } == true },
                isBuilderActive = { player -> ecoJobs.job("builder")?.let { ecoJobs.active(player, it) } == true },
                eligibleHunt = ecoJobs::payableHunt,
            )
            server.pluginManager.registerEvents(rewardGuard, this)
            ArcJobWorkObserver().also {
                server.pluginManager.registerEvents(it, this)
                it.start(lifecycle.tasks)
                lifecycle.own(it)
            }
            if (prepared.earningsStore != null) {
                runCatching {
                    val service = EarningsService(
                        this,
                        settings.earnings,
                        prepared.earningsStore,
                    )
                    runCatching { service.start() }.onFailure { runCatching { service.close() } }.getOrThrow()
                    service.also {
                        storageResources.remove(prepared.earningsStore)
                        earnings = service
                        lifecycle.own(service)
                        logger.info("Hourly job earnings analytics is ready")
                    }
                }.onFailure { failure ->
                    logger.log(
                        Level.SEVERE,
                        "Hourly job earnings analytics is unavailable; jobs and payouts will continue",
                        failure,
                    )
                }
            }
            boosts = BoostService(this, luckPerms, settings = { settings }, voucherLedger = voucherLedger)
            vouchers = VoucherService(
                this,
                locale,
                { settings },
                { ecoJobs.jobs().map { it.id }.toSet() },
                ecoJobs::names,
                prepared.signingKey,
            )
            expansion = BoostPlaceholderExpansion(pluginMeta.version, boosts, moneyAttribution, rewardGuard::allows).also {
                require(it.register()) { "Could not register the PlaceholderAPI expansion" }
                lifecycle.own(AutoCloseable { it.unregister() })
            }
            requireNotNull(getCommand("arcjobs")).apply {
                setExecutor { sender, _, _, _ ->
                    sender.sendMessage(locale.render("message.not-ready", sender))
                    true
                }
            }
            lifecycle.registerHealth("storage", ::storageHealth)
            scheduleBootstrap(lifecycle, prepared.shopStore)
    }

    override fun onDisable() {
        bootstrapTask?.cancel()
        bootstrapTask = null
        val lifecycle = pluginRuntime ?: return
        pluginRuntime = null
        val activeEarnings = earnings
        val binding = sqlBinding.also { sqlBinding = null }
        var failure: Throwable? = null
        fun attempt(close: () -> Unit) {
            try { close() } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
            }
        }
        attempt { if (::nativeWork.isInitialized) nativeWork.close() }
        attempt { lifecycle.close() }
        // Main continuations are sealed above. These futures only drain accepted IO.
        val exploration = explorationDrain
        attempt {
            if (::storage.isInitialized) storage.closeAsync {
                var terminalFailure: Throwable? = null
                fun closeResource(close: () -> Unit) {
                    try { close() } catch (error: Throwable) {
                        if (terminalFailure == null) terminalFailure = error else if (terminalFailure !== error) terminalFailure.addSuppressed(error)
                    }
                }
                closeResource { exploration.join() }
                while (true) {
                    val resource = storageResources.pollLast() ?: break
                    closeResource {
                        if (resource is ru.arc.sql.onetime.MySqlOneTimeUseLedger) resource.closeAsync().join()
                        else resource.close()
                    }
                }
                terminalFailure?.let { throw it }
            }.join()
        }
        attempt { activeEarnings?.closeAsync()?.join() }
        attempt { binding?.close() }
        if (failure == null) shutdownDrain.complete(Unit) else {
            shutdownDrain.completeExceptionally(failure)
            logger.log(Level.SEVERE, "Could not close EcoJobs storage", failure)
        }
        expansion = null
        explorationListener = null
        earnings = null
        moneyAttribution?.clear()
        moneyAttribution = null
        discoveryLedger = UnavailableDiscoveryLedger
        voucherLedger = UnavailableOneTimeUseLedger
        initialized = false
        reloadInFlight = null
    }

    private fun scheduleBootstrap(lifecycle: PaperPluginRuntime, store: AtomicShopPurchaseStore) {
        var attempts = 0
        bootstrapTask = lifecycle.tasks.runTimer(delayTicks = 1L, periodTicks = 20L) {
            attempts++
            if (ecoJobs.jobs().isNotEmpty()) {
                val jobIds = validatedJobIds()
                bootstrapTask?.cancel()
                bootstrapTask = null
                runCatching {
                    val registry = BoosterRegistry.load(dataFolder.resolve("boosters.yml"), settings, jobIds)
                    locale.validate(registry.values())
                    finishInitialization(lifecycle, registry, store)
                }.onFailure {
                    logger.log(Level.SEVERE, "ArcEcoJobs failed closed during delayed startup", it)
                    markRuntimeDown()
                    server.pluginManager.disablePlugin(this)
                }
            } else if (attempts >= 120) {
                logger.severe("EcoJobs did not register any jobs within 120 seconds")
                markRuntimeDown()
                server.pluginManager.disablePlugin(this)
            }
        }
    }

    private fun finishInitialization(lifecycle: PaperPluginRuntime, registry: BoosterRegistry, store: AtomicShopPurchaseStore) {
        check(!initialized) { "ArcEcoJobs is already initialized" }
        bindEconomyIntegration()
        val jobIds = validatedJobIds()
        runCatching(ecoJobs::prepareHuntFilters).onFailure { failure ->
            logger.log(
                Level.WARNING,
                "Could not prewarm EcoJobs hunting filters; the first eligible hunt will retry",
                failure,
            )
        }
        boosterRegistry = registry
        shop = BoosterShopService(
            enabled = { settings.shop.enabled && settings.redemptionStorage.enabled },
            store = store,
            storage = storage,
            nativeWork = nativeWork,
            offerCurrent = { id, preset -> server.getPlayer(id)?.let { it.isOnline && it.hasPermission("arcecojobs.use") } == true && boosterRegistry.get(preset.id) == preset },
            payment = RoutedShopPaymentGateway(
                money = VaultShopPaymentGateway(moneyIntegration),
                tokens = if (server.pluginManager.isPluginEnabled("RedisEconomy")) RedisTokenShopPaymentGateway() else null,
            ),
            voucherFactory = { playerId, voucherId, preset ->
                vouchers.create(preset, requireNotNull(server.getPlayer(playerId)), voucherId, java.time.Instant.now().epochSecond).serializeAsBytes()
            },
        )
        enforceMoneyIntegration()
        enforceExplorationIntegration()
        jobsMenu = JobsMenu(
            this, { settings }, locale, ecoJobs, boosts, { boosterRegistry }, vouchers, { earnings }, ::reloadPlugin, menuLayouts, shop = shop,
        )
        lifecycle.own(jobsMenu)
        val command = JobsCommand(
            { settings }, locale, ecoJobs, boosts, { boosterRegistry }, vouchers, jobsMenu, ::reloadPlugin, shop, rewardGuard::summary,
        )
        jobsMenu.installAdminActions(command::executeAdmin)
        requireNotNull(getCommand("arcjobs")).apply {
            setExecutor(command)
            tabCompleter = command
        }
        server.pluginManager.registerEvents(JobsListener({ settings }, locale, jobsMenu, boosts, vouchers), this)
        earnings?.let { server.pluginManager.registerEvents(EarningsListener(it), this) }
        if (settings.exploration.enabled) {
            val explorer = requireNotNull(ecoJobs.job(ExplorationListener.JOB_ID)) {
                "EcoJobs explorer job is required when exploration is enabled"
            }
            explorationListener = ExplorationListener(
                this,
                ecoJobs,
                explorer,
                discoveryLedger,
                LibreforgeExplorationTrigger(),
                settings.exploration.maximumInFlight,
            ).also {
                server.pluginManager.registerEvents(it, this)
                lifecycle.own(AutoCloseable {
                    explorationDrain = it.shutdown().whenComplete { _, failure ->
                        if (failure != null) logger.log(Level.WARNING, "Could not finish exploration shutdown", failure)
                    }
                })
            }
        }
        initialized = true
        ecoJobs.prepareLeaderboards()
        lifecycle.ready(
            "jobs" to ecoJobs.jobs().size,
            "boosters" to boosterRegistry.values().size,
        )
        requireNotNull(pluginRuntime).reportHealthEvery(HEALTH_REPORT_TICKS)
        logger.info("ArcEcoJobs enabled with ${ecoJobs.jobs().size} jobs and ${boosterRegistry.values().size} booster presets")
    }

    private fun markRuntimeDown() {
        runCatching {
            pluginRuntime?.health?.markDown()
            pluginRuntime?.emitHealth()
        }
    }

    private fun storageHealth(): RuntimeHealthContribution {
        val dependencies = linkedMapOf(
            "voucher_mysql" to (!settings.redemptionStorage.enabled || voucherLedger.available),
            "discovery_mysql" to (!settings.exploration.enabled || discoveryLedger.available),
            "earnings_mysql" to (!settings.earnings.enabled || earnings != null),
        )
        val schemas = buildMap {
            if (settings.redemptionStorage.enabled) put("voucher", VoucherLedgerStorage.SCHEMA_VERSION)
            if (settings.exploration.enabled) put("discovery", MySqlDiscoveryLedger.SCHEMA_VERSION)
            if (settings.earnings.enabled) put("earnings", MySqlHourlyEarningsStore.SCHEMA_VERSION)
        }
        val backlog = (earnings?.pendingBucketCount() ?: 0).toLong()
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        return RuntimeHealthContribution(
            state = if (dependencies.values.all { it }) RuntimeHealthState.UP else RuntimeHealthState.DEGRADED,
            recoveryBacklog = backlog,
            activeLeases = voucherLedger.activeClaims,
            schemas = schemas,
            dependencies = dependencies,
        )
    }

    private fun bindEconomyIntegration() {
        val economy = requireNotNull(server.servicesManager.load(Economy::class.java)) {
            "A Vault economy provider must be registered before ArcEcoJobs initializes"
        }
        moneyIntegration = VaultEconomyIntegration(
                economy = economy,
                moneyAttribution = moneyAttribution,
                recordEarnings = { player, jobId, amount ->
                    earnings?.recordMoney(player.uniqueId, jobId, amount)
                },
                auditBridge = ArcAuditBridge.discover(),
                rewardAllowed = { player, job -> player.player?.let { rewardGuard.allows(it, job) } == true },
            )
        EconomyManager.register(moneyIntegration)
        check(EconomyManager.hasRegistrations()) { "eco did not accept the Vault economy integration" }
        logger.info("EcoJobs money effects bound to Vault provider ${economy.name}")
    }

    private fun reloadPlugin(): CompletableFuture<Result<Unit>> {
        reloadInFlight?.takeUnless { it.isDone }?.let { return it }
        val lifecycle = requireNotNull(pluginRuntime)
        val jobIds = validatedJobIds()
        val expected = settings
        data class Reload(val settings: AddonSettings, val layouts: ru.arc.paper.menu.PaperMenuConfiguration,
                          val boosters: BoosterRegistry, val locale: JobsLocale.Prepared)
        val prepared = storage.submit {
            runCatching {
                val candidate = AddonSettings.load(dataFolder.resolve("config.yml"))
                require(candidate.redemptionStorage == expected.redemptionStorage) { "redemptions.mysql settings require a server restart" }
                require(candidate.exploration == expected.exploration) { "exploration settings require a server restart" }
                require(candidate.earnings == expected.earnings) { "earnings settings require a server restart" }
                val presets = BoosterRegistry.load(dataFolder.resolve("boosters.yml"), candidate, jobIds)
                Reload(candidate, menuLayouts.prepare(dataFolder.toPath()), presets, locale.prepare(dataFolder, presets.values()))
            }
        }.handle { candidate, failure -> candidate ?: Result.failure(failure) }
        return prepared.thenCompose { candidate -> lifecycle.tasks.callSync {
            candidate.fold(onSuccess = { replacement -> runCatching {
                check(settings === expected) { "Configuration changed during reload" }
                enforceMoneyIntegration(replacement.settings)
                locale.install(replacement.locale)
                settings = replacement.settings
                boosterRegistry = replacement.boosters
                jobsMenu.replaceMenus(replacement.layouts)
                ecoJobs.invalidateLeaderboards()
                ecoJobs.prepareLeaderboards()
            } }, onFailure = { Result.failure(it) })
        } }.also { reloadInFlight = it }
    }

    private fun validatedJobIds(): Set<String> = ecoJobs.jobs().map { it.id }.toSet().also { jobIds ->
        val invalid = jobIds.filterNot(BoostNodeCodec::isValidScope)
        require(invalid.isEmpty()) {
            "EcoJobs job IDs cannot be encoded as LuckPerms boost scopes: ${invalid.joinToString(", ")}"
        }
    }

    private fun enforceMoneyIntegration(candidate: AddonSettings = settings) {
        if (candidate.blockAfkRewards || candidate.huntGuard.enabled || candidate.builderPlacement.enabled) {
            val unguarded = ecoJobs.rewardGuardIntegrationProblems()
            require(unguarded.isEmpty()) { "Missing pre-action reward guard filters in EcoJobs jobs: ${unguarded.joinToString()}. Deploy matching job configs before activation." }
        }
        val problems = ecoJobs.moneyIntegrationProblems(candidate.earnings.enabled)
        if (problems.isEmpty()) return
        val message = "EcoJobs money integration placeholders are missing from jobs: ${problems.joinToString(", ")}"
        if (candidate.requireMoneyPlaceholder) error(message) else logger.warning(message)
    }

    private fun enforceExplorationIntegration(candidate: AddonSettings = settings) {
        if (!candidate.exploration.enabled) return
        require(server.pluginManager.getPlugin("libreforge")?.isEnabled == true) {
            "libreforge must be enabled before ArcEcoJobs exploration initializes"
        }
        val explorer = requireNotNull(ecoJobs.job(ExplorationListener.JOB_ID)) {
            "EcoJobs explorer job is missing"
        }
        val expectedTrigger = LibreforgeExplorationTrigger.CONFIG_TRIGGER_ID
        val xpIntegrated = explorer.config.getSubsections("xp-gain-methods")
            .any { it.getString("trigger") == expectedTrigger }
        val moneyIntegrated = explorer.config.getSubsections("effects")
            .filter { it.getString("id") == "give_money" }
            .any { expectedTrigger in it.getStrings("triggers") }
        require(xpIntegrated && moneyIntegrated) {
            "EcoJobs explorer job must use $expectedTrigger for XP and money"
        }
    }

    private fun saveResourceIfMissing(path: String) {
        if (!dataFolder.resolve(path).isFile) saveResource(path, false)
    }

    private companion object {
        const val HEALTH_REPORT_TICKS = 1_200L
        const val EXPLORATION_SHUTDOWN_TIMEOUT_SECONDS = 5L
    }
}
