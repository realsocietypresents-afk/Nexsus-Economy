package com.nexusuniverse.economy.vitals;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public class VitalsMenuHolder implements InventoryHolder {

    private final VitalsManager.VitalType type;
    private Inventory inventory;

    public VitalsMenuHolder(VitalsManager.VitalType type) {
        this.type = type;
    }

    public VitalsManager.VitalType type() {
        return type;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }
}
