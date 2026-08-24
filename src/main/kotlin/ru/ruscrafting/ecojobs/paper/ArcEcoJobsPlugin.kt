package ru.ruscrafting.ecojobs.paper

import com.willfp.eco.core.integrations.economy.EconomyManager
import net.luckperms.api.LuckPerms
import net.milkbowl.vault.economy.Economy
import org.bukkit.plugin.java.JavaPlugin
import ru.ruscrafting.ecojobs.boost.BoostService
import ru.ruscrafting.ecojobs.boost.MySqlVoucherLedger
import ru.ruscrafting.ecojobs.boost.SigningKeyStore
import ru.ruscrafting.ecojobs.boost.UnavailableVoucherLedger
import ru.ruscrafting.ecojobs.boost.VoucherLedger
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
import ru.ruscrafting.ecojobs.integration.VaultEconomyIntegration
import java.util.logging.Level

class ArcEcoJobsPlugin : JavaPlugin() {
    private lateinit var settings: AddonSettings
    private lateinit var boosterRegistry: BoosterRegistry
    private lateinit var locale: JobsLocale
    private lateinit var ecoJobs: EcoJobsBridge
    private lateinit var boosts: BoostService
    private lateinit var vouchers: VoucherService
    private var voucherLedger: VoucherLedger = UnavailableVoucherLedger
    private var discoveryLedger: DiscoveryLedger = UnavailableDiscoveryLedger
    private var earnings: EarningsService? = null
    private var moneyAttribution: MoneyAttribution? = null
    private var explorationListener: ExplorationListener? = null
    private var expansion: BoostPlaceholderExpansion? = null
    private var bootstrapTaskId: Int? = null
    private var initialized = false

    override fun onEnable() {
        saveDefaultConfig()
        saveResourceIfMissing("boosters.yml")
        saveResourceIfMissing("lang/ru.yml")
        saveResourceIfMissing("lang/en.yml")
        try {
            settings = AddonSettings.load(dataFolder.resolve("config.yml"))
            locale = JobsLocale(dataFolder) { settings }.also(JobsLocale::validate)
            ecoJobs = EcoJobsBridge(this) { settings }
            val luckPerms = requireNotNull(server.servicesManager.load(LuckPerms::class.java)) { "LuckPerms API is unavailable" }
            voucherLedger = if (settings.redemptionStorage.enabled) {
                MySqlVoucherLedger.open(settings.redemptionStorage).also {
                    logger.info("Voucher redemption ledger is ready")
                }
            } else {
                logger.warning("Voucher redemption is disabled because redemptions.mysql.enabled is false")
                UnavailableVoucherLedger
            }
            discoveryLedger = if (settings.exploration.enabled) {
                MySqlDiscoveryLedger.open(settings.redemptionStorage).also {
                    logger.info("Chunk discovery ledger is ready")
                }
            } else {
                UnavailableDiscoveryLedger
            }
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
            }
            requireNotNull(getCommand("arcjobs")).apply {
                setExecutor { sender, _, _, _ ->
                    sender.sendMessage(locale.render("message.not-ready", sender))
                    true
                }
            }
            scheduleBootstrap()
        } catch (failure: Throwable) {
            logger.log(Level.SEVERE, "ArcEcoJobs failed closed during startup", failure)
            server.pluginManager.disablePlugin(this)
        }
    }

    override fun onDisable() {
        bootstrapTaskId?.let(server.scheduler::cancelTask)
        bootstrapTaskId = null
        runCatching { expansion?.unregister() }
        expansion = null
        explorationListener?.shutdown()
        explorationListener = null
        runCatching { earnings?.close() }
            .onFailure { logger.log(Level.SEVERE, "Could not close hourly job earnings analytics", it) }
        earnings = null
        moneyAttribution?.clear()
        moneyAttribution = null
        if (::ecoJobs.isInitialized) ecoJobs.shutdown()
        runCatching { discoveryLedger.close() }
            .onFailure { logger.log(Level.SEVERE, "Could not close the chunk discovery ledger", it) }
        discoveryLedger = UnavailableDiscoveryLedger
        runCatching { voucherLedger.close() }
            .onFailure { logger.log(Level.SEVERE, "Could not close the voucher redemption ledger", it) }
        voucherLedger = UnavailableVoucherLedger
        initialized = false
    }

    private fun scheduleBootstrap() {
        var attempts = 0
        bootstrapTaskId = server.scheduler.scheduleSyncRepeatingTask(this, {
            attempts++
            if (ecoJobs.jobs().isNotEmpty()) {
                bootstrapTaskId?.let(server.scheduler::cancelTask)
                bootstrapTaskId = null
                runCatching(::finishInitialization).onFailure { failure ->
                    logger.log(Level.SEVERE, "ArcEcoJobs failed closed during delayed startup", failure)
                    server.pluginManager.disablePlugin(this)
                }
            } else if (attempts >= 120) {
                logger.severe("EcoJobs did not register any jobs within 120 seconds")
                server.pluginManager.disablePlugin(this)
            }
        }, 1L, 20L)
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
            ).also { server.pluginManager.registerEvents(it, this) }
        }
        initialized = true
        ecoJobs.prepareLeaderboards()
        logger.info("ArcEcoJobs enabled with ${ecoJobs.jobs().size} jobs and ${boosterRegistry.values().size} booster presets")
    }

    private fun bindEconomyIntegration() {
        val economy = requireNotNull(server.servicesManager.load(Economy::class.java)) {
            "A Vault economy provider must be registered before ArcEcoJobs initializes"
        }
        EconomyManager.register(
            VaultEconomyIntegration(economy, moneyAttribution) { player, jobId, amount ->
                earnings?.recordMoney(player.uniqueId, jobId, amount)
            },
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
}
