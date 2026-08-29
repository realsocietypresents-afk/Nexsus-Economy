package com.nexusuniverse.economy.accessories;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public class AccessorySlotsMenuHolder implements InventoryHolder {

    public enum Type { PICKER, TYPE_PAGE }

    private final Type type;
    private final String slotTypeName; // null for PICKER, e.g. "RING" for TYPE_PAGE
    private Inventory inventory;

    public AccessorySlotsMenuHolder(Type type, String slotTypeName) {
        this.type = type;
        this.slotTypeName = slotTypeName;
    }

    public Type type() {
        return type;
    }

    public String slotTypeName() {
        return slotTypeName;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }
}
