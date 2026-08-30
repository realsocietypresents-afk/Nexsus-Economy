package com.nexusuniverse.economy.shop;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Vanilla chest-style GUIs have no real text field -- an anvil is the one inventory type that
 * does (the rename box the client already renders for renaming items), so a "search bar" here
 * means opening a virtual anvil with a plain, disposable item in the input slot: the player
 * types a search term into that box exactly like renaming an item, and clicking the resulting
 * (renamed) item in the output slot runs the search. See ShopSearchListener for the
 * PrepareAnvilEvent/InventoryClickEvent handling that makes this actually work (zero repair
 * cost, output always reflects the currently-typed text, and a same-tick cleanup for the seed
 * item so it doesn't get handed back to the player when they close out without searching).
 */
public class ShopSearchMenu {

    public static final String PLACEHOLDER_TEXT = "Search...";

    private final Plugin plugin;
    private final NamespacedKey seedTag;

    public ShopSearchMenu(Plugin plugin) {
        this.plugin = plugin;
        this.seedTag = new NamespacedKey(plugin, "shop_search_seed");
    }

    public NamespacedKey seedTag() {
        return seedTag;
    }

    public Inventory buildAnvil(Player player) {
        ShopSearchAnvilHolder holder = new ShopSearchAnvilHolder();
        Inventory inv = plugin.getServer().createInventory(holder, InventoryType.ANVIL,
                ChatColor.DARK_AQUA + "" + ChatColor.BOLD + "Search the Shop");
        holder.setInventory(inv);

        ItemStack seed = new ItemStack(Material.PAPER);
        ItemMeta meta = seed.getItemMeta();
        meta.setDisplayName(ChatColor.RESET + PLACEHOLDER_TEXT);
        meta.getPersistentDataContainer().set(seedTag, PersistentDataType.BOOLEAN, true);
        seed.setItemMeta(meta);
        inv.setItem(0, seed);

        return inv;
    }
}
