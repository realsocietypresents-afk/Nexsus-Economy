package com.nexusuniverse.economy.shop;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.List;

/**
 * Makes the anvil opened by ShopSearchMenu actually work as a search box.
 */
public class ShopSearchListener implements Listener {

    private final Plugin plugin;
    private final ShopManager shopManager;
    private final ShopSearchMenu searchMenu;

    public ShopSearchListener(Plugin plugin, ShopManager shopManager, ShopSearchMenu searchMenu) {
        this.plugin = plugin;
        this.shopManager = shopManager;
        this.searchMenu = searchMenu;
    }

    /**
     * Runs on every keystroke in the rename box. Vanilla anvil mechanics only populate the
     * output slot (and only for free) under specific combine conditions that don't really apply
     * here -- forcing the result every time means the output slot is always clickable and always
     * reflects exactly what's currently typed, and zeroing the repair cost means this never
     * costs (or requires) XP levels the way a real rename normally would.
     */
    @EventHandler
    public void onPrepare(PrepareAnvilEvent event) {
        if (!(event.getInventory().getHolder() instanceof ShopSearchAnvilHolder)) return;

        AnvilInventory anvil = event.getInventory();
        anvil.setRepairCost(0);

        ItemStack seed = anvil.getItem(0);
        if (seed == null) {
            event.setResult(null);
            return;
        }

        String typed = anvil.getRenameText();
        ItemStack result = seed.clone();
        ItemMeta meta = result.getItemMeta();
        meta.setDisplayName(ChatColor.RESET + (typed == null || typed.isBlank() ? ShopSearchMenu.PLACEHOLDER_TEXT : typed));
        result.setItemMeta(meta);
        event.setResult(result);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory topInventory = event.getView().getTopInventory();
        if (!(topInventory.getHolder() instanceof ShopSearchAnvilHolder)) return;

        // Never let the seed paper (or anything else) actually move -- this anvil only exists
        // to read the rename text box, not to hold or combine real items.
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null || event.getClickedInventory() != topInventory) return;
        if (event.getSlot() != 2) return; // only the output slot triggers a search

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;

        String query = ChatColor.stripColor(clicked.getItemMeta().getDisplayName());
        if (query == null || query.isBlank() || query.equals(ShopSearchMenu.PLACEHOLDER_TEXT)) {
            player.sendMessage(ChatColor.RED + "Type something in the anvil first, then click it to search.");
            return;
        }

        List<ShopItem> results = shopManager.searchAllItems(query);
        if (results.isEmpty()) {
            player.sendMessage(ChatColor.RED + "No shop items match \"" + query + "\" -- try a shorter or different term.");
            return;
        }

        player.closeInventory();
        player.openInventory(shopManager.buildSearchResultsPage(query, 0,
                shopManager.lastMode(player.getUniqueId()), shopManager.lastQuantity(player.getUniqueId())));
    }

    /**
     * A virtual anvil (one opened via createInventory rather than a real block) hands back
     * whatever's still sitting in its slots when it closes, same as any other inventory --
     * without this, closing out without ever clicking a search would leave the player holding a
     * stray "Search..." paper. One tick later (after Bukkit's own close handling has already run
     * and returned it) is the only reliable point to remove it again, so it's a same-session
     * cosmetic leftover at worst if that scheduling window is ever missed, never anything with
     * real value or economy impact.
     */
    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof ShopSearchAnvilHolder)) return;
        if (!(event.getPlayer() instanceof Player player)) return;

        Bukkit.getScheduler().runTask(plugin, () -> {
            ItemStack[] contents = player.getInventory().getContents();
            for (int i = 0; i < contents.length; i++) {
                ItemStack stack = contents[i];
                if (stack == null || !stack.hasItemMeta()) continue;
                Boolean isSeed = stack.getItemMeta().getPersistentDataContainer()
                        .get(searchMenu.seedTag(), PersistentDataType.BOOLEAN);
                if (Boolean.TRUE.equals(isSeed)) {
                    player.getInventory().setItem(i, null);
                }
            }
        });
    }
}
