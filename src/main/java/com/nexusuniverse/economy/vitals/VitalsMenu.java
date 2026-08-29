package com.nexusuniverse.economy.vitals;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The GUI for the shop's Hearts / Hunger / Oxygen tabs -- one small, single-purpose
 * page per vital type: a status icon showing what the player's current level actually
 * does, and a buy button for the next one. Reused for all three (see VitalsManager.VitalType)
 * rather than three near-identical classes.
 *
 * Same PDC-action-tag pattern as BankMenu -- buttons carry an action string rather
 * than being identified by fixed slot numbers, so VitalsMenuListener reads intent
 * directly off the clicked item.
 */
public class VitalsMenu {

    private final Plugin plugin;
    private final VitalsManager vitals;
    private final NamespacedKey actionKey;

    public VitalsMenu(Plugin plugin, VitalsManager vitals) {
        this.plugin = plugin;
        this.vitals = vitals;
        this.actionKey = new NamespacedKey(plugin, "vitals_action");
    }

    public NamespacedKey actionKey() {
        return actionKey;
    }

    public Inventory build(Player player, VitalsManager.VitalType type) {
        VitalsMenuHolder holder = new VitalsMenuHolder(type);
        Inventory inv = Bukkit.createInventory(holder, 27, title(type));
        holder.setInventory(inv);

        int level = vitals.level(player.getUniqueId(), type);
        int max = vitals.maxLevel(type);

        inv.setItem(13, statusItem(player, type, level, max));
        inv.setItem(22, buyButton(player, type, level, max));
        inv.setItem(18, backButton());

        return inv;
    }

    private String title(VitalsManager.VitalType type) {
        return switch (type) {
            case HEARTS -> ChatColor.RED + "" + ChatColor.BOLD + "Hearts Upgrade";
            case HUNGER -> ChatColor.GOLD + "" + ChatColor.BOLD + "Hunger Upgrade";
            case OXYGEN -> ChatColor.AQUA + "" + ChatColor.BOLD + "Oxygen Upgrade";
        };
    }

    private ChatColor nameColor(VitalsManager.VitalType type) {
        return switch (type) {
            case HEARTS -> ChatColor.RED;
            case HUNGER -> ChatColor.GOLD;
            case OXYGEN -> ChatColor.AQUA;
        };
    }

    private Material icon(VitalsManager.VitalType type) {
        return switch (type) {
            case HEARTS -> Material.GOLDEN_APPLE;
            case HUNGER -> Material.COOKED_BEEF;
            case OXYGEN -> Material.TURTLE_HELMET;
        };
    }

    private ItemStack statusItem(Player player, VitalsManager.VitalType type, int level, int max) {
        UUID id = player.getUniqueId();
        ItemStack item = new ItemStack(icon(type));
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(nameColor(type) + "" + ChatColor.BOLD + "Level " + level + " / " + max);

        List<String> lore = new ArrayList<>();
        switch (type) {
            case HEARTS -> {
                double bonusHp = level * vitals.hpPerHeartLevel();
                double maxHp = 20 + bonusHp;
                lore.add(ChatColor.GRAY + "Permanently raises your max health.");
                lore.add(ChatColor.GRAY + "Minecraft's own HUD wraps extra hearts");
                lore.add(ChatColor.GRAY + "into extra rows automatically.");
                lore.add("");
                lore.add(ChatColor.GREEN + "Bonus right now: +" + trim(bonusHp) + " HP (+" + trim(bonusHp / 2) + " hearts)");
                lore.add(ChatColor.DARK_GRAY + "Max health: " + trim(maxHp) + " HP (" + trim(maxHp / 2) + " hearts)");
            }
            case HUNGER -> {
                double chance = vitals.hungerNegateChance(id);
                lore.add(ChatColor.GRAY + "Makes your hunger bar drain slower --");
                lore.add(ChatColor.GRAY + "each level rolls a chance to skip the");
                lore.add(ChatColor.GRAY + "next point of hunger you'd otherwise lose.");
                lore.add("");
                lore.add(ChatColor.GREEN + "Effect right now: " + String.format("%.0f%%", chance * 100) + " slower drain");
            }
            case OXYGEN -> {
                double chance = vitals.oxygenNegateChance(id);
                double multiplier = chance >= 1.0 ? 20.0 : 1.0 / (1.0 - chance);
                lore.add(ChatColor.GRAY + "Makes your air bar drain slower underwater --");
                lore.add(ChatColor.GRAY + "each level rolls a chance to skip the");
                lore.add(ChatColor.GRAY + "next point of air you'd otherwise lose.");
                lore.add("");
                lore.add(ChatColor.GREEN + "Effect right now: roughly " + String.format("%.1fx", multiplier) + " longer underwater");
            }
        }

        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buyButton(Player player, VitalsManager.VitalType type, int level, int max) {
        boolean disabled = !vitals.enabled(type);
        boolean maxed = level >= max;
        double cost = vitals.nextLevelCost(player.getUniqueId(), type);

        ItemStack item = new ItemStack(disabled || maxed ? Material.BARRIER : Material.EMERALD);
        ItemMeta meta = item.getItemMeta();
        List<String> lore = new ArrayList<>();

        if (disabled) {
            meta.setDisplayName(ChatColor.RED + "" + ChatColor.BOLD + "Upgrades Disabled");
            lore.add(ChatColor.GRAY + "This upgrade track is turned off on this server.");
        } else if (maxed) {
            meta.setDisplayName(ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "Maxed Out!");
            lore.add(ChatColor.GRAY + "You already own every level of this upgrade.");
        } else {
            boolean afford = vitals.canAffordNext(player.getUniqueId(), type);
            meta.setDisplayName((afford ? ChatColor.GREEN : ChatColor.RED) + "" + ChatColor.BOLD + "Buy Level " + (level + 1));
            lore.add(ChatColor.GRAY + "Cost: $" + String.format("%,.2f", cost));
            if (!afford) lore.add(ChatColor.RED + "You can't afford this yet.");
            lore.add("");
            lore.add(ChatColor.DARK_GRAY + "Click to purchase.");
            meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, "buy");
        }

        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack backButton() {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.YELLOW + "Back to Categories");
        meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, "back");
        item.setItemMeta(meta);
        return item;
    }

    private String trim(double value) {
        // Whole numbers print clean ("4" not "4.0"); anything fractional (e.g. a
        // non-integer hp-per-level in config) still shows one decimal place.
        if (value == Math.rint(value)) return String.valueOf((long) value);
        return String.format("%.1f", value);
    }
}
