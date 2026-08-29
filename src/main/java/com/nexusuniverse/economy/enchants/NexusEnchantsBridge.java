package com.nexusuniverse.economy.enchants;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

/**
 * Soft, reflection-based link to NexusEnchants -- a separate,
 * independently-built plugin -- same pattern as SeasonPoller's link to
 * NexusSeasons (see com.nexusuniverse.economy.credit.SeasonPoller). Lets
 * the shop's "NexusEnchants" tab list every one of that plugin's custom
 * enchants (plus Lava/Tide Walker) and mint tomes/boots/scrolls for
 * purchase, all without a compile-time dependency between the two
 * projects -- neither is published to a shared Maven repository, so
 * reflection + Bukkit's ServicesManager is what makes this work without
 * one.
 *
 * If NexusEnchants isn't installed (or hasn't enabled yet), isConnected()
 * just stays false and ShopManager omits the NexusEnchants tab entirely
 * -- same graceful-degradation behavior as the NexusSeasons integration.
 */
public class NexusEnchantsBridge {

    private static final String API_CLASS_NAME = "com.nexusuniverse.enchants.NexusEnchantsAPI";

    private Object api;
    private Method allEnchantIdsMethod;
    private Method enchantCountMethod;
    private Method displayNameMethod;
    private Method categoryNameMethod;
    private Method maxLevelMethod;
    private Method isCurseMethod;
    private Method descriptionMethod;
    private Method createTomeMethod;
    private Method createLavaWalkerBootsMethod;
    private Method createTideWalkerBootsMethod;
    private Method createLavaWalkerScrollMethod;
    private Method createTideWalkerScrollMethod;

    public boolean isConnected() {
        if (api == null) tryConnect(); // NexusEnchants might enable after NexusEconomy did -- keep retrying lazily
        return api != null;
    }

    private void tryConnect() {
        try {
            Class<?> cls = Class.forName(API_CLASS_NAME);
            RegisteredServiceProvider<?> provider = Bukkit.getServicesManager().getRegistration(cls);
            if (provider == null) return;

            this.api = provider.getProvider();
            this.allEnchantIdsMethod = cls.getMethod("allEnchantIds");
            this.enchantCountMethod = cls.getMethod("enchantCount");
            this.displayNameMethod = cls.getMethod("displayName", String.class);
            this.categoryNameMethod = cls.getMethod("categoryName", String.class);
            this.maxLevelMethod = cls.getMethod("maxLevel", String.class);
            this.isCurseMethod = cls.getMethod("isCurse", String.class);
            this.descriptionMethod = cls.getMethod("description", String.class);
            this.createTomeMethod = cls.getMethod("createTome", String.class, int.class);
            this.createLavaWalkerBootsMethod = cls.getMethod("createLavaWalkerBoots");
            this.createTideWalkerBootsMethod = cls.getMethod("createTideWalkerBoots");
            this.createLavaWalkerScrollMethod = cls.getMethod("createLavaWalkerScroll");
            this.createTideWalkerScrollMethod = cls.getMethod("createTideWalkerScroll");
        } catch (ReflectiveOperationException | NoClassDefFoundError ignored) {
            // NexusEnchants isn't installed -- stay disconnected
            this.api = null;
        }
    }

    @SuppressWarnings("unchecked")
    public List<String> allEnchantIds() {
        if (!isConnected()) return Collections.emptyList();
        try {
            return (List<String>) allEnchantIdsMethod.invoke(api);
        } catch (ReflectiveOperationException e) {
            return Collections.emptyList();
        }
    }

    /** Custom enchant count only -- doesn't include Lava/Tide Walker, which the shop lists separately. */
    public int enchantCount() {
        if (!isConnected()) return 0;
        try {
            return (int) enchantCountMethod.invoke(api);
        } catch (ReflectiveOperationException e) {
            return 0;
        }
    }

    public String displayName(String id) {
        return invokeString(displayNameMethod, id);
    }

    /** e.g. "WEAPON", "UNIVERSAL" -- treat as an opaque grouping key, not a Bukkit type. */
    public String categoryName(String id) {
        return invokeString(categoryNameMethod, id);
    }

    public int maxLevel(String id) {
        if (!isConnected()) return 0;
        try {
            return (int) maxLevelMethod.invoke(api, id);
        } catch (ReflectiveOperationException e) {
            return 0;
        }
    }

    public boolean isCurse(String id) {
        if (!isConnected()) return false;
        try {
            return (boolean) isCurseMethod.invoke(api, id);
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    public String description(String id) {
        return invokeString(descriptionMethod, id);
    }

    public ItemStack createTome(String id, int level) {
        if (!isConnected()) return null;
        try {
            return (ItemStack) createTomeMethod.invoke(api, id, level);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    public ItemStack createLavaWalkerBoots() {
        return invokeItem(createLavaWalkerBootsMethod);
    }

    public ItemStack createTideWalkerBoots() {
        return invokeItem(createTideWalkerBootsMethod);
    }

    public ItemStack createLavaWalkerScroll() {
        return invokeItem(createLavaWalkerScrollMethod);
    }

    public ItemStack createTideWalkerScroll() {
        return invokeItem(createTideWalkerScrollMethod);
    }

    private String invokeString(Method method, String id) {
        if (!isConnected()) return null;
        try {
            return (String) method.invoke(api, id);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private ItemStack invokeItem(Method method) {
        if (!isConnected()) return null;
        try {
            return (ItemStack) method.invoke(api);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }
}
