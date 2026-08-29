package com.nexusuniverse.economy.vitals;

import com.nexusuniverse.economy.AccountManager;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Three permanent, per-player progression tracks bought straight out of the shop's
 * Hearts / Hunger / Oxygen tabs (see VitalsMenu, VitalsMenuListener) -- the "buy more
 * hearts/hunger/oxygen" categories.
 *
 * <ul>
 *   <li><b>Hearts</b> genuinely raises the vanilla max-health cap via a
 *       GENERIC_MAX_HEALTH attribute modifier. Minecraft's own HUD already wraps extra
 *       hearts into additional rows once max health passes 20, so this "just works"
 *       visually with no custom rendering needed on our end.</li>
 *   <li><b>Hunger</b> and <b>Oxygen</b> can't do the same thing -- both bars are
 *       hard-capped by the client at 20 food points / 300 air ticks with no vanilla
 *       overflow rendering the way health has. Instead, each level gives a rolled
 *       chance to silently negate the next point of hunger/air a player would
 *       otherwise lose, so the bar you already have visibly empties slower the more
 *       levels you own -- see VitalsListener, which actually applies that roll.</li>
 * </ul>
 *
 * Levels persist to vitals.yml (same one-file, UUID-keyed pattern as AccountManager's
 * balances.yml) and are permanent once bought -- there's no way to lose a level short
 * of an admin editing the file or using the /economyadmin vitals override.
 */
public class VitalsManager {

    public enum VitalType { HEARTS, HUNGER, OXYGEN }

    private final Plugin plugin;
    private final AccountManager accounts;
    private final File storageFile;
    private final Map<UUID, EnumMap<VitalType, Integer>> levels = new HashMap<>();
    private final NamespacedKey heartsModifierKey;

    public VitalsManager(Plugin plugin, AccountManager accounts) {
        this.plugin = plugin;
        this.accounts = accounts;
        this.storageFile = new File(plugin.getDataFolder(), "vitals.yml");
        this.heartsModifierKey = new NamespacedKey(plugin, "hearts_upgrade");
        load();
    }

    private void load() {
        if (!storageFile.exists()) return;
        YamlConfiguration data = YamlConfiguration.loadConfiguration(storageFile);
        for (String key : data.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                EnumMap<VitalType, Integer> perPlayer = new EnumMap<>(VitalType.class);
                perPlayer.put(VitalType.HEARTS, data.getInt(key + ".hearts", 0));
                perPlayer.put(VitalType.HUNGER, data.getInt(key + ".hunger", 0));
                perPlayer.put(VitalType.OXYGEN, data.getInt(key + ".oxygen", 0));
                levels.put(id, perPlayer);
            } catch (IllegalArgumentException ignored) {
                // skip malformed keys rather than fail the whole load
            }
        }
    }

    private void save() {
        YamlConfiguration data = new YamlConfiguration();
        for (Map.Entry<UUID, EnumMap<VitalType, Integer>> entry : levels.entrySet()) {
            String key = entry.getKey().toString();
            data.set(key + ".hearts", entry.getValue().getOrDefault(VitalType.HEARTS, 0));
            data.set(key + ".hunger", entry.getValue().getOrDefault(VitalType.HUNGER, 0));
            data.set(key + ".oxygen", entry.getValue().getOrDefault(VitalType.OXYGEN, 0));
        }
        try {
            data.save(storageFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING,
                    "NexusEconomy: failed to save vitals.yml -- a vitals upgrade may not have persisted!", e);
        }
    }

    public int level(UUID id, VitalType type) {
        EnumMap<VitalType, Integer> perPlayer = levels.get(id);
        return perPlayer == null ? 0 : perPlayer.getOrDefault(type, 0);
    }

    private String configKey(VitalType type) {
        return switch (type) {
            case HEARTS -> "hearts";
            case HUNGER -> "hunger";
            case OXYGEN -> "oxygen";
        };
    }

    public boolean enabled(VitalType type) {
        return plugin.getConfig().getBoolean("shop.vitals." + configKey(type) + ".enabled", true);
    }

    public int maxLevel(VitalType type) {
        int configured = plugin.getConfig().getInt("shop.vitals." + configKey(type) + ".max-level", defaultMaxLevel(type));
        return Math.max(0, configured);
    }

    private int defaultMaxLevel(VitalType type) {
        return switch (type) {
            case HEARTS -> 15;
            case HUNGER, OXYGEN -> 10;
        };
    }

    private double defaultBasePrice(VitalType type) {
        return switch (type) {
            case HEARTS -> 8000.0;
            case HUNGER, OXYGEN -> 5000.0;
        };
    }

    private double defaultGrowthPercent(VitalType type) {
        return switch (type) {
            case HEARTS -> 25.0;
            case HUNGER, OXYGEN -> 30.0;
        };
    }

    /**
     * Cost to go from the player's current level to the next one, or -1 if they're
     * already at (or above) the configured max level. Compounds by growth-percent per
     * level already owned, same shape as the enchant-book pricing formula, rounded to
     * the nearest $50 since these numbers get large fast at the higher levels.
     */
    public double nextLevelCost(UUID id, VitalType type) {
        int current = level(id, type);
        int max = maxLevel(type);
        if (current >= max) return -1;

        double basePrice = plugin.getConfig().getDouble("shop.vitals." + configKey(type) + ".base-price", defaultBasePrice(type));
        double growthPercent = plugin.getConfig().getDouble("shop.vitals." + configKey(type) + ".growth-percent", defaultGrowthPercent(type));
        double raw = basePrice * Math.pow(1 + growthPercent / 100.0, current);
        return Math.round(raw / 50.0) * 50.0;
    }

    public boolean canAffordNext(UUID id, VitalType type) {
        double cost = nextLevelCost(id, type);
        return cost >= 0 && accounts.has(id, cost);
    }

    /** Attempts to buy the next level for this vital. Returns false if maxed out, disabled, or the player can't afford it. */
    public boolean purchaseNextLevel(Player player, VitalType type) {
        if (!enabled(type)) return false;
        UUID id = player.getUniqueId();
        double cost = nextLevelCost(id, type);
        if (cost < 0) return false;
        if (!accounts.has(id, cost)) return false;

        accounts.withdraw(id, cost);
        EnumMap<VitalType, Integer> perPlayer = levels.computeIfAbsent(id, k -> new EnumMap<>(VitalType.class));
        perPlayer.put(type, perPlayer.getOrDefault(type, 0) + 1);
        save();

        if (type == VitalType.HEARTS) applyHeartsAttribute(player);
        return true;
    }

    /** Admin override (see /economyadmin vitals) -- sets a level directly, no cost charged, clamped to [0, max]. */
    public void setLevelAdmin(OfflinePlayer target, VitalType type, int level) {
        int clamped = Math.max(0, Math.min(maxLevel(type), level));
        EnumMap<VitalType, Integer> perPlayer = levels.computeIfAbsent(target.getUniqueId(), k -> new EnumMap<>(VitalType.class));
        perPlayer.put(type, clamped);
        save();

        if (type == VitalType.HEARTS && target.getPlayer() != null) {
            applyHeartsAttribute(target.getPlayer());
        }
    }

    // --- Hearts: a real GENERIC_MAX_HEALTH attribute modifier ---

    public double hpPerHeartLevel() {
        return plugin.getConfig().getDouble("shop.vitals.hearts.hp-per-level", 2.0);
    }

    /**
     * Rebuilds this player's hearts attribute modifier from their stored level. Safe
     * to call repeatedly (on join, after a purchase, from an admin override) -- always
     * removes whatever modifier we previously added under our own key before adding
     * the current one back, so levels never stack on top of each other.
     */
    public void applyHeartsAttribute(Player player) {
        AttributeInstance attribute = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (attribute == null) return;

        double oldMax = attribute.getValue();
        attribute.getModifiers().stream()
                .filter(modifier -> heartsModifierKey.equals(modifier.getKey()))
                .findFirst()
                .ifPresent(attribute::removeModifier);

        int level = level(player.getUniqueId(), VitalType.HEARTS);
        double bonus = level * hpPerHeartLevel();
        if (bonus > 0) {
            attribute.addModifier(new AttributeModifier(heartsModifierKey, bonus, AttributeModifier.Operation.ADD_NUMBER));
        }

        double newMax = attribute.getValue();
        double delta = newMax - oldMax;
        if (delta > 0) {
            // Top the player off by however much their max just grew, so a purchase is
            // felt immediately instead of just being empty headroom.
            player.setHealth(Math.min(newMax, player.getHealth() + delta));
        } else if (player.getHealth() > newMax) {
            // Max shrank (e.g. an admin lowered someone's level) -- never leave current
            // health above the new cap, Bukkit would reject/clamp it anyway.
            player.setHealth(newMax);
        }
    }

    // --- Hunger & Oxygen: drain-negation odds consulted by VitalsListener ---

    /** Chance (0..1) that the next hunger-drain tick should be silently negated for this player. */
    public double hungerNegateChance(UUID id) {
        int level = level(id, VitalType.HUNGER);
        double perLevelPercent = plugin.getConfig().getDouble("shop.vitals.hunger.reduction-per-level-percent", 9.0);
        return Math.min(0.95, level * perLevelPercent / 100.0); // never fully immune -- there's always some drain
    }

    /** Chance (0..1) that the next air-drain tick should be silently negated for this player. */
    public double oxygenNegateChance(UUID id) {
        int level = level(id, VitalType.OXYGEN);
        double perLevelMultiplier = plugin.getConfig().getDouble("shop.vitals.oxygen.duration-multiplier-per-level", 0.2);
        double durationMultiplier = 1.0 + (level * perLevelMultiplier);
        return Math.min(0.95, 1.0 - (1.0 / durationMultiplier));
    }
}
