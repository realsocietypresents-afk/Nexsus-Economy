package com.nexusuniverse.economy.shop;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/** Marker holder identifying the anvil-based text-input GUI opened by ShopSearchMenu -- see ShopSearchListener. */
public class ShopSearchAnvilHolder implements InventoryHolder {

    private Inventory inventory;

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }
}
