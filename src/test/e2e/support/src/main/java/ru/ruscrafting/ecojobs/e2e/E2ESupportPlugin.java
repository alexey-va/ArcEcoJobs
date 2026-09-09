package ru.ruscrafting.ecojobs.e2e;

import com.willfp.eco.core.integrations.afk.AFKIntegration;
import com.willfp.eco.core.integrations.afk.AFKManager;
import com.willfp.ecojobs.api.EcoJobsAPI;
import com.willfp.ecojobs.jobs.Job;
import com.willfp.ecojobs.jobs.Jobs;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import ru.arc.paper.api.ArcTelemetryProvider;

import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public final class E2ESupportPlugin extends JavaPlugin implements CommandExecutor, AFKIntegration {
    private final Set<UUID> afk = new HashSet<>();
    private final JobWorkProbe jobWorkProbe = new JobWorkProbe();

    @Override
    public void onEnable() {
        AFKManager.register(this);
        getCommand("arce2e").setExecutor(this);
        if (getServer().getPluginManager().isPluginEnabled("ARC")) {
            getServer().getServicesManager().register(ArcTelemetryProvider.class, jobWorkProbe, this, ServicePriority.Highest);
        }
    }

    @Override
    public String getPluginName() {
        return getName();
    }

    @Override
    public boolean isAfk(Player player) {
        return afk.contains(player.getUniqueId());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player) || args.length == 0) return false;
        String jobId = args.length > 1 && (args[0].equalsIgnoreCase("setup") || args[0].equalsIgnoreCase("state"))
            ? args[1] : "slayer";
        Job job = Jobs.getByID(jobId);
        if (job == null) return false;
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "afk" -> {
                boolean enabled = args.length > 1 && args[1].equalsIgnoreCase("on");
                if (enabled) afk.add(player.getUniqueId()); else afk.remove(player.getUniqueId());
                player.sendMessage("E2E_AFK=" + enabled);
            }
            case "setup" -> {
                player.getWorld().setTime(18000);
                player.getWorld().setGameRule(org.bukkit.GameRule.DO_DAYLIGHT_CYCLE, false);
                player.getWorld().setGameRule(org.bukkit.GameRule.DO_MOB_SPAWNING, false);
                if (!EcoJobsAPI.hasJobActive(player, job)) EcoJobsAPI.joinJob(player, job);
                EcoJobsAPI.setJobLevel(player, job, 1);
                EcoJobsAPI.setJobXP(player, job, 0.0);
                if (!EcoJobsAPI.hasJobActive(player, job)) throw new IllegalStateException("Job did not become active: " + jobId);
                player.sendMessage("E2E_SETUP");
            }
            case "state" -> {
                Economy economy = Bukkit.getServicesManager().load(Economy.class);
                double balance = economy == null ? Double.NaN : economy.getBalance(player);
                player.sendMessage("E2E_STATE xp=" + EcoJobsAPI.getJobXP(player, job) + " balance=" + balance
                    + " active=" + EcoJobsAPI.hasJobActive(player, job)
                    + " afk=" + AFKManager.isAfk(player)
                    + " account=" + (economy != null && economy.hasAccount(player))
                    + " placeholders=" + me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player,
                        "%arcecojobs_work_" + job.getId() + "_allowed%/%arcecojobs_boost_" + job.getId() + "_money_multiplier%"));
            }
            case "metrics" -> player.sendMessage(jobWorkMetrics());
            case "spawn" -> {
                EntityType type = EntityType.valueOf(args.length > 1 ? args[1].toUpperCase(Locale.ROOT) : "ZOMBIE");
                LivingEntity entity = (LivingEntity) player.getWorld().spawnEntity(player.getLocation().add(0, 0, 2), type);
                entity.setMaxHealth(1.0);
                entity.setHealth(1.0);
                entity.setAI(false);
                entity.setSilent(true);
                if (args.length > 2 && args[2].equalsIgnoreCase("spawner")) {
                    entity.getPersistentDataContainer().set(
                        new NamespacedKey("ecomobs", "from_spawner"), PersistentDataType.BYTE, (byte) 1
                    );
                }
                player.sendMessage("E2E_SPAWN=" + entity.getUniqueId());
            }
            default -> { return false; }
        }
        return true;
    }

    private String jobWorkMetrics() {
        if (getServer().getServicesManager().getRegistration(ArcTelemetryProvider.class) == null) {
            return "E2E_METRICS unavailable required=" + getConfig().getBoolean("require-arc", false);
        }
        return jobWorkProbe.report();
    }

    private static final class JobWorkProbe implements ArcTelemetryProvider {
        private final Map<String, Long> observations = new HashMap<>();
        private final Map<String, Long> observedMillis = new HashMap<>();
        private final Map<UUID, Map<String, Long>> previousSamples = new HashMap<>();

        @Override
        public boolean recordJobWork(UUID playerId, String job) {
            long now = System.currentTimeMillis();
            Map<String, Long> samples = previousSamples.computeIfAbsent(playerId, ignored -> new HashMap<>());
            Long previous = samples.put(job, now);
            observations.merge(job, 1L, Long::sum);
            if (previous != null) observedMillis.merge(job, now - previous, Long::sum);
            return true;
        }

        @Override
        public void breakJobWork(UUID playerId) {
            previousSamples.remove(playerId);
        }

        private String report() {
            StringBuilder message = new StringBuilder("E2E_METRICS");
            observations.forEach((job, count) -> message.append(' ')
                .append(job).append("_observations=").append(count)
                .append(' ').append(job).append("_observedMillis=").append(observedMillis.getOrDefault(job, 0L)));
            return message.toString();
        }
    }
}
