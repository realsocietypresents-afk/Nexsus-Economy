package com.nexusuniverse.economy.vitals;

import com.nexusuniverse.economy.shop.ShopManager;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

public class VitalsMenuListener implements Listener {

    private final VitalsMenu vitalsMenu;
    private final VitalsManager vitals;
    private final ShopManager shopManager;

    public VitalsMenuListener(VitalsMenu vitalsMenu, VitalsManager vitals, ShopManager shopManager) {
        this.vitalsMenu = vitalsMenu;
        this.vitals = vitals;
        this.shopManager = shopManager;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory topInventory = event.getView().getTopInventory();
        if (!(topInventory.getHolder() instanceof VitalsMenuHolder holder)) return;

        event.setCancelled(true); // never let items move in/out of this GUI
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null || event.getClickedInventory() != topInventory) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir() || !clicked.hasItemMeta()) return;

        String action = clicked.getItemMeta().getPersistentDataContainer().get(vitalsMenu.actionKey(), PersistentDataType.STRING);
        if (action == null) return;

        if (action.equals("back")) {
            player.openInventory(shopManager.buildCategoryMenu());
            return;
        }
        if (action.equals("buy")) {
            VitalsManager.VitalType type = holder.type();
            boolean bought = vitals.purchaseNextLevel(player, type);
            if (bought) {
                player.sendMessage(ChatColor.GREEN + "Upgraded " + typeLabel(type) + " to level "
                        + vitals.level(player.getUniqueId(), type) + "!");
            } else {
                player.sendMessage(ChatColor.RED + "You can't afford that, it's disabled, or you're already maxed out.");
            }
            player.openInventory(vitalsMenu.build(player, type)); // refresh so the new level shows immediately
        }
    }

    private String typeLabel(VitalsManager.VitalType type) {
        return switch (type) {
            case HEARTS -> "Hearts";
            case HUNGER -> "Hunger";
            case OXYGEN -> "Oxygen";
        };
    }
}
