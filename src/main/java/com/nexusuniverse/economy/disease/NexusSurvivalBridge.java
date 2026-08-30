package com.nexusuniverse.economy.disease;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

/**
 * Soft, reflection-based link to NexusSurvival -- a separate, independently-built plugin -- same
 * pattern as NexusEnchantsBridge/NexusAccessoriesBridge/NexusViceBridge (Class.forName + Bukkit's
 * ServicesManager, no compile-time dependency between the two projects). Lets the shop's
 * "Disease" tab list every disease NexusSurvival defines and mint real, usable Pathogen Vials of
 * them for purchase -- drinking one runs the exact same infect() path NexusSurvival's own
 * /nexussurvival give command uses.
 *
 * If NexusSurvival isn't installed (or hasn't enabled yet), isConnected() just stays false and
 * ShopManager omits the Disease tab entirely -- same graceful-degradation behavior as every other
 * integration tab here.
 */
public class NexusSurvivalBridge {

    private static final String API_CLASS_NAME = "com.nexusuniverse.survival.api.NexusSurvivalApi";

    private Object api;
    private Method allDiseaseIdsMethod;
    private Method diseaseDisplayNameMethod;
    private Method diseaseDescriptionMethod;
    private Method diseaseIsSevereMethod;
    private Method createDiseaseItemMethod;

    public boolean isConnected() {
        if (api == null) tryConnect(); // NexusSurvival might enable after NexusEconomy did -- keep retrying lazily
        return api != null;
    }

    private void tryConnect() {
        try {
            Class<?> cls = Class.forName(API_CLASS_NAME);
            RegisteredServiceProvider<?> provider = Bukkit.getServicesManager().getRegistration(cls);
            if (provider == null) return;

            this.api = provider.getProvider();
            this.allDiseaseIdsMethod = cls.getMethod("allDiseaseIds");
            this.diseaseDisplayNameMethod = cls.getMethod("diseaseDisplayName", String.class);
            this.diseaseDescriptionMethod = cls.getMethod("diseaseDescription", String.class);
            this.diseaseIsSevereMethod = cls.getMethod("diseaseIsSevere", String.class);
            this.createDiseaseItemMethod = cls.getMethod("createDiseaseItem", String.class);
        } catch (ReflectiveOperationException | NoClassDefFoundError ignored) {
            // NexusSurvival isn't installed -- stay disconnected
            this.api = null;
        }
    }

    @SuppressWarnings("unchecked")
    public List<String> allDiseaseIds() {
        if (!isConnected()) return Collections.emptyList();
        try {
            return (List<String>) allDiseaseIdsMethod.invoke(api);
        } catch (ReflectiveOperationException e) {
            return Collections.emptyList();
        }
    }

    public String diseaseDisplayName(String id) {
        return invokeString(diseaseDisplayNameMethod, id);
    }

    public String diseaseDescription(String id) {
        return invokeString(diseaseDescriptionMethod, id);
    }

    /** True for the diseases NexusSurvival itself treats as the nastiest (can deal real damage at Critical severity). */
    public boolean diseaseIsSevere(String id) {
        if (!isConnected()) return false;
        try {
            return (boolean) diseaseIsSevereMethod.invoke(api, id);
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    public ItemStack createDiseaseItem(String id) {
        return invokeItem(createDiseaseItemMethod, id);
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
