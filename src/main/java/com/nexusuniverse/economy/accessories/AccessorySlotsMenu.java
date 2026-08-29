package com.nexusuniverse.economy.accessories;

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
 * Shop-side GUI for buying extra NexusAccessories slot capacity -- a small picker (Ring / Belt /
 * Cape / Charm) leading to a per-type buy page, same shape as NexusEconomy's own VitalsMenu for
 * Hearts/Hunger/Oxygen. Slot type names are plain Strings the whole way through (as
 * NexusAccessoriesBridge exposes them), since this class can't import NexusAccessories'
 * AccessorySlotType enum -- there's no compile-time dependency between the two plugins.
 */
public class AccessorySlotsMenu {

    private static final List<String> SLOT_TYPES = List.of("RING", "BELT", "CAPE", "CHARM");

    private final Plugin plugin;
    private final NexusAccessoriesBridge bridge;
    private final NamespacedKey actionKey;

    public AccessorySlotsMenu(Plugin plugin, NexusAccessoriesBridge bridge) {
        this.plugin = plugin;
        this.bridge = bridge;
        this.actionKey = new NamespacedKey(plugin, "accessory_slots_action");
    }

    public NamespacedKey actionKey() {
        return actionKey;
    }

    public Inventory buildPicker(Player player) {
        AccessorySlotsMenuHolder holder = new AccessorySlotsMenuHolder(AccessorySlotsMenuHolder.Type.PICKER, null);
        Inventory inv = Bukkit.createInventory(holder, 27, ChatColor.DARK_AQUA + "" + ChatColor.BOLD + "Accessory Slots");
        holder.setInventory(inv);

        int[] slots = {10, 12, 14, 16};
        for (int i = 0; i < SLOT_TYPES.size(); i++) {
            inv.setItem(slots[i], pickerButton(player, SLOT_TYPES.get(i)));
        }
        inv.setItem(22, backButton());

        return inv;
    }

    public Inventory buildTypePage(Player player, String slotTypeName) {
        AccessorySlotsMenuHolder holder = new AccessorySlotsMenuHolder(AccessorySlotsMenuHolder.Type.TYPE_PAGE, slotTypeName);
        Inventory inv = Bukkit.createInventory(holder, 27, ChatColor.DARK_AQUA + "" + ChatColor.BOLD + displayName(slotTypeName) + " Slots");
        holder.setInventory(inv);

        UUID id = player.getUniqueId();
        int owned = bridge.ownedSlotCount(id, slotTypeName);
        int max = bridge.maxSlotCount(slotTypeName);

        inv.setItem(13, statusItem(slotTypeName, owned, max));
        inv.setItem(22, buyButton(player, slotTypeName, owned, max));
        inv.setItem(18, backToPickerButton());

        return inv;
    }

    private ItemStack pickerButton(Player player, String slotTypeName) {
        int owned = bridge.ownedSlotCount(player.getUniqueId(), slotTypeName);
        int max = bridge.maxSlotCount(slotTypeName);

        ItemStack item = new ItemStack(icon(slotTypeName));
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(color(slotTypeName) + "" + ChatColor.BOLD + displayName(slotTypeName) + " Slots");
        meta.setLore(List.of(
                ChatColor.GRAY + "You own " + owned + " / " + max + ".",
                ChatColor.DARK_GRAY + "Click to view/buy the next one."
        ));
        meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, "open:" + slotTypeName);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack statusItem(String slotTypeName, int owned, int max) {
        ItemStack item = new ItemStack(icon(slotTypeName));
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(color(slotTypeName) + "" + ChatColor.BOLD + owned + " / " + max + " Slots Owned");
        meta.setLore(List.of(
                ChatColor.GRAY + "Every player starts with the base amount free.",
                ChatColor.GRAY + "Extra slots let you wear more than one",
                ChatColor.GRAY + displayName(slotTypeName).toLowerCase() + " accessory at once."
        ));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buyButton(Player player, String slotTypeName, int owned, int max) {
        boolean maxed = owned >= max;
        double cost = bridge.nextSlotCost(player.getUniqueId(), slotTypeName);

        ItemStack item = new ItemStack(maxed ? Material.BARRIER : Material.EMERALD);
        ItemMeta meta = item.getItemMeta();
        List<String> lore = new ArrayList<>();

        if (maxed || cost < 0) {
            meta.setDisplayName(ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "Maxed Out!");
            lore.add(ChatColor.GRAY + "You already own every " + displayName(slotTypeName).toLowerCase() + " slot.");
        } else {
            // Whether the player can actually afford this is only checked at purchase time (see
            // AccessorySlotsMenuListener), not rendered here -- same as this button just always
            // showing green/buyable and letting the click handler reject an unaffordable buy.
            meta.setDisplayName(ChatColor.GREEN + "" + ChatColor.BOLD + "Buy Slot #" + (owned + 1));
            lore.add(ChatColor.GRAY + "Cost: $" + String.format("%,.2f", cost));
            lore.add("");
            lore.add(ChatColor.DARK_GRAY + "Click to purchase.");
            meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, "buy:" + slotTypeName);
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

    private ItemStack backToPickerButton() {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.YELLOW + "Back");
        meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, "back-to-picker");
        item.setItemMeta(meta);
        return item;
    }

    private String displayName(String slotTypeName) {
        String raw = slotTypeName.toLowerCase();
        return Character.toUpperCase(raw.charAt(0)) + raw.substring(1);
    }

    private ChatColor color(String slotTypeName) {
        return switch (slotTypeName) {
            case "RING" -> ChatColor.LIGHT_PURPLE;
            case "BELT" -> ChatColor.GOLD;
            case "CAPE" -> ChatColor.AQUA;
            case "CHARM" -> ChatColor.GREEN;
            default -> ChatColor.WHITE;
        };
    }

    private Material icon(String slotTypeName) {
        return switch (slotTypeName) {
            case "RING" -> Material.GOLD_NUGGET;
            case "BELT" -> Material.LEATHER;
            case "CAPE" -> Material.PHANTOM_MEMBRANE;
            case "CHARM" -> Material.RABBIT_FOOT;
            default -> Material.PAPER;
        };
    }
}
