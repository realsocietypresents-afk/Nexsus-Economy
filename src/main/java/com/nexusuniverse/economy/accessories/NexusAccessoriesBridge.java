package com.nexusuniverse.economy.accessories;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Soft, reflection-based link to NexusAccessories -- a separate, independently-built plugin --
 * same pattern as SeasonPoller (NexusSeasons) and NexusEnchantsBridge (NexusEnchants). Lets the
 * shop sell every accessory NexusAccessories knows about (its own "Accessories" tab) and lets
 * players buy extra Ring/Belt/Cape/Charm slot capacity (see AccessorySlotsMenu), all without a
 * compile-time dependency between the two projects.
 *
 * NexusAccessories itself has no concept of money -- it only tracks who owns what. This bridge
 * is where the actual purchase flow lives on the economy side: check nextSlotCost, charge the
 * player through AccountManager, then call grantExtraSlot to actually hand over the slot. See
 * AccessorySlotsMenuListener for exactly that sequence.
 *
 * If NexusAccessories isn't installed (or hasn't enabled yet), isConnected() stays false and
 * ShopManager omits the Accessories tab and the accessory-slot pinned buttons entirely -- same
 * graceful-degradation behavior as every other soft integration in this plugin.
 */
public class NexusAccessoriesBridge {

    private static final String API_CLASS_NAME = "com.nexusuniverse.accessories.NexusAccessoriesAPI";

    private Object api;
    private Method allAccessoryIdsMethod;
    private Method displayNameMethod;
    private Method slotTypeNameMethod;
    private Method tierNameMethod;
    private Method descriptionMethod;
    private Method createItemMethod;
    private Method maxSlotCountMethod;
    private Method ownedSlotCountMethod;
    private Method nextSlotCostMethod;
    private Method grantExtraSlotMethod;

    public boolean isConnected() {
        if (api == null) tryConnect(); // NexusAccessories might enable after NexusEconomy did -- keep retrying lazily
        return api != null;
    }

    private void tryConnect() {
        try {
            Class<?> cls = Class.forName(API_CLASS_NAME);
            RegisteredServiceProvider<?> provider = Bukkit.getServicesManager().getRegistration(cls);
            if (provider == null) return;

            this.api = provider.getProvider();
            this.allAccessoryIdsMethod = cls.getMethod("allAccessoryIds");
            this.displayNameMethod = cls.getMethod("displayName", String.class);
            this.slotTypeNameMethod = cls.getMethod("slotTypeName", String.class);
            this.tierNameMethod = cls.getMethod("tierName", String.class);
            this.descriptionMethod = cls.getMethod("description", String.class);
            this.createItemMethod = cls.getMethod("createItem", String.class);
            this.maxSlotCountMethod = cls.getMethod("maxSlotCount", String.class);
            this.ownedSlotCountMethod = cls.getMethod("ownedSlotCount", UUID.class, String.class);
            this.nextSlotCostMethod = cls.getMethod("nextSlotCost", UUID.class, String.class);
            this.grantExtraSlotMethod = cls.getMethod("grantExtraSlot", UUID.class, String.class);
        } catch (ReflectiveOperationException | NoClassDefFoundError ignored) {
            // NexusAccessories isn't installed -- stay disconnected
            this.api = null;
        }
    }

    @SuppressWarnings("unchecked")
    public List<String> allAccessoryIds() {
        if (!isConnected()) return Collections.emptyList();
        try {
            return (List<String>) allAccessoryIdsMethod.invoke(api);
        } catch (ReflectiveOperationException e) {
            return Collections.emptyList();
        }
    }

    public String displayName(String id) {
        return invokeString(displayNameMethod, id);
    }

    /** e.g. "RING", "BELT", "CAPE", "CHARM". */
    public String slotTypeName(String id) {
        return invokeString(slotTypeNameMethod, id);
    }

    /** e.g. "SCRAP" .. "MYTHIC". */
    public String tierName(String id) {
        return invokeString(tierNameMethod, id);
    }

    public String description(String id) {
        return invokeString(descriptionMethod, id);
    }

    public ItemStack createItem(String id) {
        if (!isConnected()) return null;
        try {
            return (ItemStack) createItemMethod.invoke(api, id);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    public int maxSlotCount(String slotTypeName) {
        if (!isConnected()) return 0;
        try {
            return (int) maxSlotCountMethod.invoke(api, slotTypeName);
        } catch (ReflectiveOperationException e) {
            return 0;
        }
    }

    public int ownedSlotCount(UUID playerId, String slotTypeName) {
        if (!isConnected()) return 0;
        try {
            return (int) ownedSlotCountMethod.invoke(api, playerId, slotTypeName);
        } catch (ReflectiveOperationException e) {
            return 0;
        }
    }

    /** Cost for this player's next slot of this type, or -1 if maxed/invalid/not connected. */
    public double nextSlotCost(UUID playerId, String slotTypeName) {
        if (!isConnected()) return -1;
        try {
            return (double) nextSlotCostMethod.invoke(api, playerId, slotTypeName);
        } catch (ReflectiveOperationException e) {
            return -1;
        }
    }

    /** Actually grants the slot -- call only after successfully charging the player. */
    public boolean grantExtraSlot(UUID playerId, String slotTypeName) {
        if (!isConnected()) return false;
        try {
            return (boolean) grantExtraSlotMethod.invoke(api, playerId, slotTypeName);
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    private String invokeString(Method method, String id) {
        if (!isConnected()) return null;
        try {
            return (String) method.invoke(api, id);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }
}
