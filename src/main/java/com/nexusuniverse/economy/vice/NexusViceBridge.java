package com.nexusuniverse.economy.vice;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

/**
 * Soft, reflection-based link to NexusVice -- a separate, independently-built plugin -- same
 * pattern as NexusEnchantsBridge/NexusAccessoriesBridge (Class.forName + Bukkit's
 * ServicesManager, no compile-time dependency between the two projects). Lets the shop's "Vice"
 * tab list every one of NexusVice's substances and alcohol brands and mint real, usable copies
 * of them for purchase.
 *
 * If NexusVice isn't installed (or hasn't enabled yet), isConnected() just stays false and
 * ShopManager omits the Vice tab entirely -- same graceful-degradation behavior as every other
 * integration tab here.
 */
public class NexusViceBridge {

    private static final String API_CLASS_NAME = "com.nexusuniverse.vice.NexusViceAPI";

    private Object api;
    private Method allSubstanceIdsMethod;
    private Method substanceDisplayNameMethod;
    private Method substanceCategoryNameMethod;
    private Method substanceHasOverdoseRiskMethod;
    private Method substanceOverdoseThresholdMethod;
    private Method createSubstanceItemMethod;
    private Method allAlcoholBrandIdsMethod;
    private Method alcoholDisplayNameMethod;
    private Method alcoholTypeNameMethod;
    private Method alcoholQualityNameMethod;
    private Method createAlcoholItemMethod;

    public boolean isConnected() {
        if (api == null) tryConnect(); // NexusVice might enable after NexusEconomy did -- keep retrying lazily
        return api != null;
    }

    private void tryConnect() {
        try {
            Class<?> cls = Class.forName(API_CLASS_NAME);
            RegisteredServiceProvider<?> provider = Bukkit.getServicesManager().getRegistration(cls);
            if (provider == null) return;

            this.api = provider.getProvider();
            this.allSubstanceIdsMethod = cls.getMethod("allSubstanceIds");
            this.substanceDisplayNameMethod = cls.getMethod("substanceDisplayName", String.class);
            this.substanceCategoryNameMethod = cls.getMethod("substanceCategoryName", String.class);
            this.substanceHasOverdoseRiskMethod = cls.getMethod("substanceHasOverdoseRisk", String.class);
            this.substanceOverdoseThresholdMethod = cls.getMethod("substanceOverdoseThreshold", String.class);
            this.createSubstanceItemMethod = cls.getMethod("createSubstanceItem", String.class);
            this.allAlcoholBrandIdsMethod = cls.getMethod("allAlcoholBrandIds");
            this.alcoholDisplayNameMethod = cls.getMethod("alcoholDisplayName", String.class);
            this.alcoholTypeNameMethod = cls.getMethod("alcoholTypeName", String.class);
            this.alcoholQualityNameMethod = cls.getMethod("alcoholQualityName", String.class);
            this.createAlcoholItemMethod = cls.getMethod("createAlcoholItem", String.class);
        } catch (ReflectiveOperationException | NoClassDefFoundError ignored) {
            // NexusVice isn't installed -- stay disconnected
            this.api = null;
        }
    }

    @SuppressWarnings("unchecked")
    public List<String> allSubstanceIds() {
        if (!isConnected()) return Collections.emptyList();
        try {
            return (List<String>) allSubstanceIdsMethod.invoke(api);
        } catch (ReflectiveOperationException e) {
            return Collections.emptyList();
        }
    }

    public String substanceDisplayName(String id) {
        return invokeString(substanceDisplayNameMethod, id);
    }

    /** e.g. "DEPRESSANT", "STIMULANT" -- treat as an opaque grouping key, not a Bukkit type. */
    public String substanceCategoryName(String id) {
        return invokeString(substanceCategoryNameMethod, id);
    }

    public boolean substanceHasOverdoseRisk(String id) {
        if (!isConnected()) return false;
        try {
            return (boolean) substanceHasOverdoseRiskMethod.invoke(api, id);
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    public double substanceOverdoseThreshold(String id) {
        if (!isConnected()) return 0;
        try {
            return (double) substanceOverdoseThresholdMethod.invoke(api, id);
        } catch (ReflectiveOperationException e) {
            return 0;
        }
    }

    public ItemStack createSubstanceItem(String id) {
        return invokeItem(createSubstanceItemMethod, id);
    }

    @SuppressWarnings("unchecked")
    public List<String> allAlcoholBrandIds() {
        if (!isConnected()) return Collections.emptyList();
        try {
            return (List<String>) allAlcoholBrandIdsMethod.invoke(api);
        } catch (ReflectiveOperationException e) {
            return Collections.emptyList();
        }
    }

    public String alcoholDisplayName(String id) {
        return invokeString(alcoholDisplayNameMethod, id);
    }

    /** e.g. "BEER", "WINE", "LIQUOR". */
    public String alcoholTypeName(String id) {
        return invokeString(alcoholTypeNameMethod, id);
    }

    /** e.g. "BOTTOM_SHELF", "STANDARD", "TOP_SHELF". */
    public String alcoholQualityName(String id) {
        return invokeString(alcoholQualityNameMethod, id);
    }

    public ItemStack createAlcoholItem(String id) {
        return invokeItem(createAlcoholItemMethod, id);
    }

    private String invokeString(Method method, String id) {
        if (!isConnected()) return null;
        try {
            return (String) method.invoke(api, id);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private ItemStack invokeItem(Method method, String id) {
        if (!isConnected()) return null;
        try {
            return (ItemStack) method.invoke(api, id);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }
}
