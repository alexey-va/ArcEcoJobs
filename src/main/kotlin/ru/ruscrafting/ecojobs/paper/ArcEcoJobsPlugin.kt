package ru.ruscrafting.ecojobs.paper

import com.willfp.eco.core.integrations.economy.EconomyManager
import net.luckperms.api.LuckPerms
import net.milkbowl.vault.economy.Economy
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.ScheduledTask
import ru.arc.observability.RuntimeHealthContribution
import ru.arc.observability.RuntimeHealthState
import ru.arc.onetime.OneTimeUseLedger
import ru.arc.onetime.UnavailableOneTimeUseLedger
import ru.arc.paper.runtime.PaperPluginRuntime
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
import ru.ruscrafting.ecojobs.integration.ReflectiveArcAuditBridge
import ru.ruscrafting.ecojobs.integration.VaultEconomyIntegration
import java.util.concurrent.TimeUnit
import java.util.logging.Level

class ArcEcoJobsPlugin : JavaPlugin() {
    private lateinit var settings: AddonSettings
    private lateinit var boosterRegistry: BoosterRegistry
    private lateinit var locale: JobsLocale
    private lateinit var ecoJobs: EcoJobsBridge
    private lateinit var boosts: BoostService
    private lateinit var vouchers: VoucherService
    private var voucherLedger: OneTimeUseLedger = UnavailableOneTimeUseLedger
    private var discoveryLedger: DiscoveryLedger = UnavailableDiscoveryLedger
    private var earnings: EarningsService? = null
    private var moneyAttribution: MoneyAttribution? = null
    private var explorationListener: ExplorationListener? = null
    private var expansion: BoostPlaceholderExpansion? = null
    private var pluginRuntime: PaperPluginRuntime? = null
    private var bootstrapTask: ScheduledTask? = null
    private var initialized = false

    override fun onEnable() {
        val lifecycle = PaperPluginRuntime(this, "arc-ecojobs", BukkitTaskScheduler(this)).also {
            pluginRuntime = it
            it.start("version" to pluginMeta.version)
        }
        try {
            saveDefaultConfig()
            saveResourceIfMissing("boosters.yml")
            saveResourceIfMissing("lang/ru.yml")
            saveResourceIfMissing("lang/en.yml")
            settings = AddonSettings.load(dataFolder.resolve("config.yml"))
            locale = JobsLocale(dataFolder) { settings }.also(JobsLocale::validate)
            ecoJobs = EcoJobsBridge(this) { settings }
            lifecycle.own(AutoCloseable { ecoJobs.shutdown() })
            val luckPerms = requireNotNull(server.servicesManager.load(LuckPerms::class.java)) { "LuckPerms API is unavailable" }
            voucherLedger = if (settings.redemptionStorage.enabled) {
                VoucherLedgerStorage.open(settings.redemptionStorage).also {
                    logger.info("Voucher redemption ledger is ready")
                }
            } else {
                logger.warning("Voucher redemption is disabled because redemptions.mysql.enabled is false")
                UnavailableOneTimeUseLedger
            }
            lifecycle.own(voucherLedger)
            discoveryLedger = if (settings.exploration.enabled) {
                MySqlDiscoveryLedger.open(settings.redemptionStorage).also {
                    logger.info("Chunk discovery ledger is ready")
                }
            } else {
                UnavailableDiscoveryLedger
            }
            lifecycle.own(discoveryLedger)
            if (settings.earnings.enabled) {
                moneyAttribution = MoneyAttribution()
                runCatching {
                    val service = EarningsService(
                        this,
                        settings.earnings,
                        MySqlHourlyEarningsStore.open(settings.redemptionStorage),
                    )
                    runCatching { service.start() }.onFailure { runCatching { service.close() } }.getOrThrow()
                    service.also {
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
                SigningKeyStore.loadOrCreate(dataFolder.toPath()),
            )
            expansion = BoostPlaceholderExpansion(pluginMeta.version, boosts, moneyAttribution).also {
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
            scheduleBootstrap(lifecycle)
        } catch (failure: Throwable) {
            logger.log(Level.SEVERE, "ArcEcoJobs failed closed during startup", failure)
            runCatching {
                lifecycle.health.markDown()
                lifecycle.emitHealth()
            }
            server.pluginManager.disablePlugin(this)
        }
    }

    override fun onDisable() {
        bootstrapTask?.cancel()
        bootstrapTask = null
        runCatching { pluginRuntime?.close() }
            .onFailure { logger.log(Level.SEVERE, "Could not close ArcEcoJobs runtime", it) }
        pluginRuntime = null
        expansion = null
        explorationListener = null
        earnings = null
        moneyAttribution?.clear()
        moneyAttribution = null
        discoveryLedger = UnavailableDiscoveryLedger
        voucherLedger = UnavailableOneTimeUseLedger
        initialized = false
    }

    private fun scheduleBootstrap(lifecycle: PaperPluginRuntime) {
        var attempts = 0
        bootstrapTask = lifecycle.tasks.runTimer(delayTicks = 1L, periodTicks = 20L) {
            attempts++
            if (ecoJobs.jobs().isNotEmpty()) {
                bootstrapTask?.cancel()
                bootstrapTask = null
                runCatching(::finishInitialization).onFailure { failure ->
                    logger.log(Level.SEVERE, "ArcEcoJobs failed closed during delayed startup", failure)
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

    private fun finishInitialization() {
        check(!initialized) { "ArcEcoJobs is already initialized" }
        bindEconomyIntegration()
        val jobIds = validatedJobIds()
        boosterRegistry = BoosterRegistry.load(dataFolder.resolve("boosters.yml"), settings, jobIds)
        locale.validate(boosterRegistry.values())
        enforceMoneyIntegration()
        enforceExplorationIntegration()
        val menu = JobsMenu(
            this, { settings }, locale, ecoJobs, boosts, { boosterRegistry }, vouchers, { earnings }, ::reloadPlugin,
        )
        val command = JobsCommand(
            { settings }, locale, ecoJobs, boosts, { boosterRegistry }, vouchers, menu, ::reloadPlugin,
        )
        requireNotNull(getCommand("arcjobs")).apply {
            setExecutor(command)
            tabCompleter = command
        }
        server.pluginManager.registerEvents(JobsListener({ settings }, locale, menu, boosts, vouchers), this)
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
                requireNotNull(pluginRuntime).own(AutoCloseable {
                    it.shutdown().get(EXPLORATION_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                })
            }
        }
        initialized = true
        ecoJobs.prepareLeaderboards()
        requireNotNull(pluginRuntime).ready(
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
        EconomyManager.register(
            VaultEconomyIntegration(
                economy = economy,
                moneyAttribution = moneyAttribution,
                recordEarnings = { player, jobId, amount ->
                    earnings?.recordMoney(player.uniqueId, jobId, amount)
                },
                auditBridge = ReflectiveArcAuditBridge.discover(),
            ),
        )
        check(EconomyManager.hasRegistrations()) { "eco did not accept the Vault economy integration" }
        logger.info("EcoJobs money effects bound to Vault provider ${economy.name}")
    }

    private fun reloadPlugin(): Result<Unit> = runCatching {
        val candidateSettings = AddonSettings.load(dataFolder.resolve("config.yml"))
        val jobIds = validatedJobIds()
        val candidateBoosters = BoosterRegistry.load(
            dataFolder.resolve("boosters.yml"),
            candidateSettings,
            jobIds,
        )
        enforceMoneyIntegration(candidateSettings)
        require(candidateSettings.redemptionStorage == settings.redemptionStorage) {
            "redemptions.mysql settings require a server restart"
        }
        require(candidateSettings.exploration == settings.exploration) {
            "exploration settings require a server restart"
        }
        require(candidateSettings.earnings == settings.earnings) {
            "earnings settings require a server restart"
        }
        locale.reload(dataFolder, candidateBoosters.values())
        settings = candidateSettings
        boosterRegistry = candidateBoosters
        ecoJobs.invalidateLeaderboards()
        ecoJobs.prepareLeaderboards()
    }

    private fun validatedJobIds(): Set<String> = ecoJobs.jobs().map { it.id }.toSet().also { jobIds ->
        val invalid = jobIds.filterNot(BoostNodeCodec::isValidScope)
        require(invalid.isEmpty()) {
            "EcoJobs job IDs cannot be encoded as LuckPerms boost scopes: ${invalid.joinToString(", ")}"
        }
    }

    private fun enforceMoneyIntegration(candidate: AddonSettings = settings) {
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
