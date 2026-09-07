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
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public final class E2ESupportPlugin extends JavaPlugin implements CommandExecutor, AFKIntegration {
    private final Set<UUID> afk = new HashSet<>();

    @Override
    public void onEnable() {
        AFKManager.register(this);
        getCommand("arce2e").setExecutor(this);
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
        Job slayer = Jobs.getByID("slayer");
        if (slayer == null) return false;
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
                if (!EcoJobsAPI.hasJobActive(player, slayer)) EcoJobsAPI.joinJob(player, slayer);
                EcoJobsAPI.setJobLevel(player, slayer, 1);
                EcoJobsAPI.setJobXP(player, slayer, 0.0);
                if (!EcoJobsAPI.hasJobActive(player, slayer)) throw new IllegalStateException("Slayer job did not become active");
                player.sendMessage("E2E_SETUP");
            }
            case "state" -> {
                Economy economy = Bukkit.getServicesManager().load(Economy.class);
                double balance = economy == null ? Double.NaN : economy.getBalance(player);
                player.sendMessage("E2E_STATE xp=" + EcoJobsAPI.getJobXP(player, slayer) + " balance=" + balance);
            }
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
}
