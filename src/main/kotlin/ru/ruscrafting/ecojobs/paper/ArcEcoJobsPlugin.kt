package ru.ruscrafting.ecojobs.paper

import net.luckperms.api.LuckPerms
import org.bukkit.plugin.java.JavaPlugin
import ru.ruscrafting.ecojobs.boost.BoostService
import ru.ruscrafting.ecojobs.boost.SigningKeyStore
import ru.ruscrafting.ecojobs.boost.VoucherService
import ru.ruscrafting.ecojobs.config.AddonSettings
import ru.ruscrafting.ecojobs.config.BoosterRegistry
import ru.ruscrafting.ecojobs.config.JobsLocale
import ru.ruscrafting.ecojobs.domain.BoostNodeCodec
import ru.ruscrafting.ecojobs.integration.BoostPlaceholderExpansion
import ru.ruscrafting.ecojobs.integration.EcoJobsBridge
import java.util.logging.Level

class ArcEcoJobsPlugin : JavaPlugin() {
    private lateinit var settings: AddonSettings
    private lateinit var boosterRegistry: BoosterRegistry
    private lateinit var locale: JobsLocale
    private lateinit var ecoJobs: EcoJobsBridge
    private lateinit var boosts: BoostService
    private lateinit var vouchers: VoucherService
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
            boosts = BoostService(this, luckPerms, settings = { settings })
            vouchers = VoucherService(
                this,
                locale,
                { settings },
                { ecoJobs.jobs().map { it.id }.toSet() },
                ecoJobs::names,
                SigningKeyStore.loadOrCreate(dataFolder.toPath()),
            )
            expansion = BoostPlaceholderExpansion(pluginMeta.version, boosts).also {
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
        if (::ecoJobs.isInitialized) ecoJobs.shutdown()
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
        val jobIds = validatedJobIds()
        boosterRegistry = BoosterRegistry.load(dataFolder.resolve("boosters.yml"), settings, jobIds)
        locale.validate(boosterRegistry.values())
        enforceMoneyIntegration()
        val menu = JobsMenu(
            this, { settings }, locale, ecoJobs, boosts, { boosterRegistry }, vouchers, ::reloadPlugin,
        )
        val command = JobsCommand(
            { settings }, locale, ecoJobs, boosts, { boosterRegistry }, vouchers, menu, ::reloadPlugin,
        )
        requireNotNull(getCommand("arcjobs")).apply {
            setExecutor(command)
            tabCompleter = command
        }
        server.pluginManager.registerEvents(JobsListener({ settings }, locale, menu, boosts, vouchers), this)
        initialized = true
        ecoJobs.prepareLeaderboards()
        logger.info("ArcEcoJobs enabled with ${ecoJobs.jobs().size} jobs and ${boosterRegistry.values().size} booster presets")
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
        val problems = ecoJobs.moneyIntegrationProblems()
        if (problems.isEmpty()) return
        val message = "EcoJobs money boost placeholder is missing from jobs: ${problems.joinToString(", ")}"
        if (candidate.requireMoneyPlaceholder) error(message) else logger.warning(message)
    }

    private fun saveResourceIfMissing(path: String) {
        if (!dataFolder.resolve(path).isFile) saveResource(path, false)
    }
}
