package com.nexusuniverse.economy.accessories;

import com.nexusuniverse.economy.AccountManager;
import com.nexusuniverse.economy.shop.ShopManager;
import com.nexusuniverse.economy.shop.ShopRevenueRouter;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;

/**
 * Handles the Accessory Slots picker/buy-page clicks (see AccessorySlotsMenu). This is the one
 * place NexusEconomy actually charges a player for accessory slot capacity: check the cost
 * through the bridge, withdraw it from their bank balance, then ask NexusAccessories to actually
 * grant the slot -- refunding if that last step somehow fails, so a player is never charged for
 * a slot they didn't get.
 */
public class AccessorySlotsMenuListener implements Listener {

    private final AccessorySlotsMenu menu;
    private final NexusAccessoriesBridge bridge;
    private final AccountManager accounts;
    private final ShopManager shopManager;
    private final ShopRevenueRouter revenueRouter;

    public AccessorySlotsMenuListener(AccessorySlotsMenu menu, NexusAccessoriesBridge bridge, AccountManager accounts, ShopManager shopManager, ShopRevenueRouter revenueRouter) {
        this.menu = menu;
        this.bridge = bridge;
        this.accounts = accounts;
        this.shopManager = shopManager;
        this.revenueRouter = revenueRouter;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory topInventory = event.getView().getTopInventory();
        if (!(topInventory.getHolder() instanceof AccessorySlotsMenuHolder)) return;

        event.setCancelled(true); // button-based menu -- never let items move in/out
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null || event.getClickedInventory() != topInventory) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir() || !clicked.hasItemMeta()) return;

        String action = clicked.getItemMeta().getPersistentDataContainer().get(menu.actionKey(), PersistentDataType.STRING);
        if (action == null) return;

        if (action.equals("back")) {
            player.openInventory(shopManager.buildCategoryMenu());
            return;
        }
        if (action.equals("back-to-picker")) {
            player.openInventory(menu.buildPicker(player));
            return;
        }
        if (action.startsWith("open:")) {
            player.openInventory(menu.buildTypePage(player, action.substring("open:".length())));
            return;
        }
        if (action.startsWith("buy:")) {
            handleBuy(player, action.substring("buy:".length()));
        }
    }

    private void handleBuy(Player player, String slotTypeName) {
        UUID id = player.getUniqueId();
        double cost = bridge.nextSlotCost(id, slotTypeName);
        if (cost < 0) {
            player.sendMessage(ChatColor.RED + "You're already maxed out on " + slotTypeName.toLowerCase() + " slots.");
            player.openInventory(menu.buildTypePage(player, slotTypeName));
            return;
        }
        if (!accounts.has(id, cost)) {
            player.sendMessage(ChatColor.RED + "You can't afford that yet -- it costs $" + String.format("%,.2f", cost) + ".");
            return;
        }

        accounts.withdraw(id, cost);
        boolean granted = bridge.grantExtraSlot(id, slotTypeName);
        if (!granted) {
            // Shouldn't normally happen (we just confirmed they weren't maxed), but refund rather
            // than leave a player charged for nothing if NexusAccessories rejects the grant anyway
            // (e.g. it disconnected mid-purchase).
            accounts.deposit(id, cost);
            player.sendMessage(ChatColor.RED + "Something went wrong granting that slot -- you weren't charged.");
            return;
        }

        revenueRouter.creditPurchase(cost);
        player.sendMessage(ChatColor.GREEN + "Bought a new " + slotTypeName.toLowerCase() + " slot for $"
                + String.format("%,.2f", cost) + "! Check your Accessory Pouch.");
        player.openInventory(menu.buildTypePage(player, slotTypeName)); // refresh so the new count shows immediately
    }
}
