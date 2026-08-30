package com.nexusuniverse.economy.shop;

import com.nexusuniverse.economy.AccountManager;
import com.nexusuniverse.economy.accessories.NexusAccessoriesBridge;
import com.nexusuniverse.economy.enchants.NexusEnchantsBridge;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Loads shop-items.yml into in-memory categories, builds the
 * category-list and per-category paginated GUIs, and runs every
 * buy/sell transaction against the Bank (AccountManager) -- nothing
 * here keeps its own separate money.
 *
 * shop-items.yml ships as a small, original starter catalog (a simple
 * five-tier rarity formula, not data from any other plugin) -- meant to
 * be edited and expanded directly, which is also why buy/sell here are
 * plain per-item numbers rather than anything derived from a third
 * party's configuration.
 *
 * On top of that hand-written catalog, {@link #generateAutoCatalog()}
 * walks every {@link Material} the server knows about and adds every
 * remaining obtainable block AND item, sorted across the shop's tabs by
 * name pattern, so the shop always covers the full item list for whatever
 * Minecraft version the server is running -- instead of a hand-typed
 * list that would go stale (and be error-prone) the moment a new
 * version adds blocks/items. {@link #generateSpawnEggs()} does the same
 * for spawn eggs specifically, in their own dedicated tab with their own
 * danger/rarity-based pricing.
 */
public class ShopManager {

    private static final int ITEMS_PER_PAGE = 45;
    // The search button pinned to slot 0 of the category menu -- ShopListener recognizes this
    // key string and opens the anvil-based search input (ShopSearchMenu) instead of a normal
    // item page. Not a real ShopCategory entry, same pattern as HEARTS_KEY/HUNGER_KEY/OXYGEN_KEY.
    private static final String SEARCH_KEY = "Search";
    private static final String CUSTOM_KEY = "Custom";
    private static final String ENCHANT_KEY = "Enchantments";
    // The NexusEnchants integration tab -- only shown/populated when NexusEnchantsBridge
    // reports a live connection. See generateNexusEnchantItems() and buildNexusEnchantPage().
    private static final String NEXUS_ENCHANT_KEY = "NexusEnchants";
    // The NexusAccessories integration -- buyable accessory items (its own pinned tab) and a
    // second pinned button opening the slot-capacity picker (see AccessorySlotsMenu, a separate
    // class NexusEconomyPlugin wires directly into ShopListener, since it isn't a flat item list
    // the way every other pinned tab here is). Both only show up once the bridge is connected.
    private static final String ACCESSORIES_KEY = "Accessories";
    private static final String ACCESSORY_SLOTS_KEY = "AccessorySlots";
    // The Potions tab -- see generatePotions() and buildPotionsPage(). Always shown (not
    // gated behind a soft-integration bridge like NexusEnchants/Accessories are).
    private static final String POTIONS_KEY = "Potions";
    // Pinned category-menu buttons for the Vitals tabs -- see buildCategoryMenu() and
    // ShopListener#handleCategoryClick, which recognizes these three key strings and
    // opens VitalsMenu instead of a normal ITEM_PAGE. Not real ShopCategory entries.
    private static final String HEARTS_KEY = "Hearts";
    private static final String HUNGER_KEY = "Hunger";
    private static final String OXYGEN_KEY = "Oxygen";
    // Bumped whenever shop-items.yml's bundled content changes in a way that needs to reach
    // servers that already have an old copy on disk -- see the version check in load().
    // v5: the auto-catalog now covers every vanilla item (not just blocks) and adds the
    // Spawn Eggs tab -- see generateAutoCatalog()/generateSpawnEggs() below.
    private static final int CATALOG_VERSION = 5;

    private final Plugin plugin;
    private final AccountManager accounts;
    private final ShopRevenueRouter revenueRouter;
    private final NamespacedKey categoryKeyTag;
    private final Map<String, ShopCategory> categories = new LinkedHashMap<>();
    private final File customItemsFile;
    private final List<CustomShopEntry> customItems = new ArrayList<>();
    private final File enchantItemsFile;
    private final List<CustomShopEntry> enchantItems = new ArrayList<>();
    private final NexusEnchantsBridge enchantsBridge;
    private final List<CustomShopEntry> nexusEnchantItems = new ArrayList<>();
    private final NexusAccessoriesBridge accessoriesBridge;
    private final List<CustomShopEntry> accessoryItems = new ArrayList<>();
    private final List<CustomShopEntry> potionItems = new ArrayList<>();
    private final Map<UUID, ShopMenuHolder.Mode> lastMode = new HashMap<>();
    private final Map<UUID, Integer> lastQuantity = new HashMap<>();
    private final Map<String, double[]> pricingTiers = new LinkedHashMap<>();
    private final Map<Material, ShopItem> sellIndex = new HashMap<>();

    public ShopManager(Plugin plugin, AccountManager accounts, ShopRevenueRouter revenueRouter, NexusEnchantsBridge enchantsBridge, NexusAccessoriesBridge accessoriesBridge) {
        this.plugin = plugin;
        this.accounts = accounts;
        this.revenueRouter = revenueRouter;
        this.enchantsBridge = enchantsBridge;
        this.accessoriesBridge = accessoriesBridge;
        this.categoryKeyTag = new NamespacedKey(plugin, "shop_category_key");
        this.customItemsFile = new File(plugin.getDataFolder(), "custom-items.yml");
        this.enchantItemsFile = new File(plugin.getDataFolder(), "enchant-items.yml");
        loadPricingTiers();
        load();
        generateAutoCatalog();
        generateSpawnEggs();
        loadCustomItems();
        generateEnchantBooks();
        loadEnchantItems();
        generateNexusEnchantItems();
        generateAccessoryItems();
        generatePotions();
        buildSellIndex();
    }

    /** Remembers which side of the buy/sell toggle a player last had selected, so it carries over between menu opens. */
    public ShopMenuHolder.Mode lastMode(UUID uuid) {
        return lastMode.getOrDefault(uuid, ShopMenuHolder.Mode.BUY);
    }

    public void setLastMode(UUID uuid, ShopMenuHolder.Mode mode) {
        lastMode.put(uuid, mode);
    }

    /** Remembers which quantity (1/16/32/64) a player last had selected on a category page, so it carries over between menu opens. */
    public int lastQuantity(UUID uuid) {
        return lastQuantity.getOrDefault(uuid, 1);
    }

    public void setLastQuantity(UUID uuid, int quantity) {
        lastQuantity.put(uuid, quantity);
    }

    /** Cycles 1 -> 16 -> 32 -> 64 -> 1, for the quantity toggle button. */
    public static int nextQuantity(int current) {
        return switch (current) {
            case 1 -> 16;
            case 16 -> 32;
            case 32 -> 64;
            default -> 1;
        };
    }

    public NamespacedKey categoryKeyTag() {
        return categoryKeyTag;
    }

    /**
     * Loads the shared rarity/complexity price table from
     * shop.pricing-tiers in config.yml -- SCRAP through MYTHIC, each with
     * its own buy and sell price. Both shop-items.yml (via the "tier:"
     * field) and the auto-generated block catalog resolve their prices
     * from this one table, so retuning a tier in config.yml re-prices
     * every item that uses it at once instead of needing to hand-edit
     * hundreds of individual entries.
     *
     * Buy is priced well above sell on every tier -- deliberately, per
     * how this shop's meant to work: selling is where players make real
     * money (especially on the higher tiers), while buying something
     * outright instead of earning/finding it is a genuine money sink.
     */
    private void loadPricingTiers() {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("shop.pricing-tiers");
        if (section == null) {
            plugin.getLogger().warning("NexusEconomy: no shop.pricing-tiers in config.yml -- tiered items will fall back to $2/$1.");
            return;
        }
        for (String tierName : section.getKeys(false)) {
            ConfigurationSection tier = section.getConfigurationSection(tierName);
            if (tier == null) continue;
            double buy = tier.getDouble("buy", 2.00);
            double sell = tier.getDouble("sell", 1.00);
            pricingTiers.put(tierName.toUpperCase(java.util.Locale.ROOT), new double[]{buy, sell});
        }
    }

    /** Looks up a tier's {buy, sell} pair, warning and falling back to the cheapest sane default if the name doesn't exist. */
    private double[] resolveTier(String tierName) {
        double[] prices = pricingTiers.get(tierName == null ? null : tierName.toUpperCase(java.util.Locale.ROOT));
        if (prices == null) {
            plugin.getLogger().warning("NexusEconomy: unknown pricing tier \"" + tierName + "\", defaulting to $2/$1.");
            return new double[]{2.00, 1.00};
        }
        return prices;
    }

    private void load() {
        File file = new File(plugin.getDataFolder(), "shop-items.yml");
        if (!file.exists()) {
            plugin.saveResource("shop-items.yml", false);
        } else {
            // shop-items.yml isn't a Bukkit Configuration, so it doesn't get the automatic
            // copyDefaults() merge that config.yml gets in NexusEconomyPlugin#onEnable -- without
            // this check, a server that already has a copy on disk from an older plugin version
            // would keep using its old prices forever, even after an update changes the bundled
            // catalog (which is exactly what happened between the "tier:" pricing rework landing
            // in code and it actually reaching a live server). If the on-disk file is older than
            // what this build ships, back it up (nothing is silently lost) and pull the fresh one.
            YamlConfiguration onDisk = YamlConfiguration.loadConfiguration(file);
            int onDiskVersion = onDisk.getInt("catalog-version", 0);
            if (onDiskVersion < CATALOG_VERSION) {
                File backup = new File(plugin.getDataFolder(), "shop-items.yml.v" + onDiskVersion + ".bak");
                if (file.renameTo(backup)) {
                    plugin.getLogger().warning("NexusEconomy: shop-items.yml on disk was catalog version "
                            + onDiskVersion + ", this build ships version " + CATALOG_VERSION
                            + " -- backed up your old copy to " + backup.getName() + " and installed the new one. "
                            + "If you'd hand-edited shop-items.yml, re-apply those changes from the backup.");
                    plugin.saveResource("shop-items.yml", false);
                } else {
                    plugin.getLogger().warning("NexusEconomy: shop-items.yml on disk is catalog version "
                            + onDiskVersion + " but this build ships version " + CATALOG_VERSION
                            + ", and backing it up failed -- using the old file as-is. Delete or rename "
                            + "shop-items.yml and restart to pick up the new catalog.");
                }
            }
        }

        YamlConfiguration data = YamlConfiguration.loadConfiguration(file);
        double buyMultiplier = plugin.getConfig().getDouble("shop.buy-price-multiplier", 1.0);
        double sellMultiplier = plugin.getConfig().getDouble("shop.sell-price-multiplier", 1.0);

        ConfigurationSection categoriesSection = data.getConfigurationSection("categories");
        if (categoriesSection == null) return;

        for (String key : categoriesSection.getKeys(false)) {
            ConfigurationSection catSection = categoriesSection.getConfigurationSection(key);
            if (catSection == null) continue;

            String displayName = catSection.getString("display-name", key);
            Material icon = parseMaterial(catSection.getString("icon", "CHEST"));

            List<ShopItem> items = new ArrayList<>();
            for (Map<?, ?> map : catSection.getMapList("items")) {
                Material material = parseMaterial(String.valueOf(map.get("material")));
                if (material == null) continue;

                double buy;
                double sellRaw;
                String tier = map.get("tier") == null ? null : String.valueOf(map.get("tier"));
                if (tier != null) {
                    double[] prices = resolveTier(tier);
                    buy = prices[0];
                    sellRaw = prices[1];
                } else {
                    // No tier -- a genuine one-off price (trophies, buy-only specials like
                    // Nether Star/Beacon, or anything hand-priced outside the tier ladder).
                    buy = toDouble(map.get("buy"));
                    sellRaw = toDouble(map.get("sell"));
                }

                buy = round2(buy * buyMultiplier);
                double sell = sellRaw > 0 ? round2(sellRaw * sellMultiplier) : sellRaw;
                items.add(new ShopItem(material, buy, sell));
            }

            categories.put(key, new ShopCategory(key, displayName, icon != null ? icon : Material.CHEST, items));
        }

        plugin.getLogger().info("NexusEconomy: loaded " + categories.size() + " shop categories, "
                + categories.values().stream().mapToInt(c -> c.items().size()).sum() + " items total.");
    }

    private Material parseMaterial(String name) {
        try {
            return Material.valueOf(name);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("NexusEconomy: unknown material in shop-items.yml: " + name);
            return null;
        }
    }

    private double toDouble(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    public Map<String, ShopCategory> categories() {
        return categories;
    }

    /**
     * Adds every remaining obtainable block AND item in the game across the shop's
     * tabs -- Ores &amp; Minerals, Redstone, Wood, Stone, Glass, Wool, and so on for
     * blocks; Food, Armor, Tools, Weapons, Ranged, Utility, Mob Drops, Brewing,
     * Fishing, Music Discs, and Banner Patterns for non-block items -- with
     * "Miscellaneous" holding true catch-all leftovers. Anything already listed by
     * hand anywhere in shop-items.yml is skipped, so curated entries keep their own
     * tuned prices and nothing is duplicated.
     *
     * Version 0.8.0 dumped every auto-added block into one "Blocks" tab.
     * On a current Minecraft version that's genuinely ~900+ distinct block
     * materials once every wood species, stone type, and dye color is
     * counted separately -- not a bug, just how many blocks the game
     * actually has -- but a single tab that size is unusable and, worse,
     * one bad material in that huge a batch could throw an exception and
     * take the whole page down with it. {@link #classifyAutoMaterial(Material)}
     * sorts the list into the tabs above by name pattern, and every item
     * built in {@link #buildCategoryPage} is now wrapped so one broken
     * entry gets skipped (and logged) instead of breaking the page.
     *
     * A material only counts as "obtainable" if Bukkit reports isItem() -- that
     * naturally excludes fluids, fire, wall-attached variants (torches, heads, etc.
     * -- those are already covered by their item form), piston internals, and other
     * things that can't exist as a real ItemStack, whether they're a block or not.
     * isLegacy() is also excluded so old pre-1.13 aliases in the enum don't sneak in
     * as duplicates. Spawn eggs and enchanted books are excluded here even though
     * they pass that check -- they're handled by their own dedicated generators
     * ({@link #generateSpawnEggs()}, {@link #generateEnchantBooks()}) instead, each
     * with pricing that a plain Material-tier lookup can't express.
     *
     * A short default exclude list is applied on top of that -- blocks the person
     * running the server explicitly asked to leave out (respawn anchors and TNT),
     * technical/creative-mode blocks (command blocks, structure/jigsaw blocks,
     * barrier, spawner/trial spawner, vault, bedrock), and NBT/component-dependent
     * items that would be meaningless (or actively broken) as a plain, data-free
     * ItemStack -- potions, tipped arrows, written books, firework rockets/stars,
     * suspicious stew, goat horns, and similar. Giving those out would be a much
     * bigger "ruin the server" risk (or just a non-functional item) than most of
     * what's withheld here -- all of this is just config, so any of it can be
     * re-enabled or added to.
     *
     * The classifier is a best-effort heuristic based on material name
     * patterns, not a perfect ontology -- anything that lands in the wrong
     * tab can be pinned to a specific tab with
     * shop.auto-blocks.category-overrides in config.yml.
     *
     * Pricing for every auto-added block/item comes from the shared
     * shop.pricing-tiers table (see {@link #loadPricingTiers()}), resolved
     * in priority order: an exact $ pin in price-overrides, then a
     * per-material tier pin in tier-overrides, then the tab it landed in's
     * default tier in tab-default-tier, then COMMON if none of those match.
     */
    private void generateAutoCatalog() {
        if (!plugin.getConfig().getBoolean("shop.auto-blocks.enabled", true)) return;

        double buyMultiplier = plugin.getConfig().getDouble("shop.buy-price-multiplier", 1.0);
        double sellMultiplier = plugin.getConfig().getDouble("shop.sell-price-multiplier", 1.0);

        Set<Material> excluded = EnumSet.noneOf(Material.class);
        for (String name : plugin.getConfig().getStringList("shop.auto-blocks.excluded")) {
            Material material = parseMaterial(name.trim());
            if (material != null) excluded.add(material);
        }

        ConfigurationSection priceOverridesSection = plugin.getConfig().getConfigurationSection("shop.auto-blocks.price-overrides");
        ConfigurationSection tierOverridesSection = plugin.getConfig().getConfigurationSection("shop.auto-blocks.tier-overrides");
        ConfigurationSection categoryOverridesSection = plugin.getConfig().getConfigurationSection("shop.auto-blocks.category-overrides");
        ConfigurationSection tabDefaultTierSection = plugin.getConfig().getConfigurationSection("shop.auto-blocks.tab-default-tier");

        // Every material already hand-listed anywhere in shop-items.yml -- never duplicate these.
        Set<Material> alreadyListed = new HashSet<>();
        for (ShopCategory category : categories.values()) {
            for (ShopItem item : category.items()) {
                alreadyListed.add(item.material());
            }
        }

        Map<String, List<ShopItem>> buckets = new LinkedHashMap<>();
        int totalAdded = 0;

        for (Material material : Material.values()) {
            try {
                if (!material.isItem() || material.isLegacy()) continue;
                // Spawn eggs and enchanted books get their own dedicated generators
                // (see the class-level javadoc above) -- never double-list them here.
                if (material.name().endsWith("_SPAWN_EGG") || material == Material.ENCHANTED_BOOK) continue;
                if (excluded.contains(material) || alreadyListed.contains(material)) continue;

                String bucket = categoryOverridesSection != null
                        ? categoryOverridesSection.getString(material.name(), classifyAutoMaterial(material))
                        : classifyAutoMaterial(material);

                double buy;
                double sell;
                ConfigurationSection exact = priceOverridesSection == null ? null : priceOverridesSection.getConfigurationSection(material.name());
                if (exact != null) {
                    // An exact $ pin always wins -- for true anchors that don't fit a tier cleanly.
                    buy = exact.getDouble("buy", 2.00);
                    sell = exact.getDouble("sell", 1.00);
                } else {
                    String tier = tierOverridesSection != null ? tierOverridesSection.getString(material.name()) : null;
                    if (tier == null) {
                        tier = tabDefaultTierSection != null ? tabDefaultTierSection.getString(bucket) : null;
                    }
                    double[] prices = resolveTier(tier != null ? tier : "COMMON");
                    double[] varied = varyAutoPrice(material, bucket, prices[0], prices[1]);
                    buy = varied[0];
                    sell = varied[1];
                }

                buy = round2(buy * buyMultiplier);
                sell = sell > 0 ? round2(sell * sellMultiplier) : sell;

                buckets.computeIfAbsent(bucket, b -> new ArrayList<>()).add(new ShopItem(material, buy, sell));
                totalAdded++;
            } catch (Exception e) {
                // Same defensive principle as the GUI-building code below: one odd material
                // shouldn't stop the whole shop from loading.
                plugin.getLogger().log(Level.WARNING, "NexusEconomy: couldn't auto-catalog " + material + ", skipping it.", e);
            }
        }

        for (Map.Entry<String, List<ShopItem>> entry : buckets.entrySet()) {
            String bucketName = entry.getKey();
            List<ShopItem> autoItems = entry.getValue();
            autoItems.sort((a, b) -> a.material().name().compareTo(b.material().name()));

            ShopCategory existing = categories.get(bucketName);
            if (existing == null) {
                categories.put(bucketName, new ShopCategory(bucketName, bucketName, autoCategoryIcon(bucketName), autoItems));
            } else {
                List<ShopItem> merged = new ArrayList<>(existing.items());
                merged.addAll(autoItems);
                categories.put(bucketName, new ShopCategory(existing.key(), existing.displayName(), existing.icon(), merged));
            }
        }

        plugin.getLogger().info("NexusEconomy: auto-added " + totalAdded + " blocks/items across " + buckets.size()
                + " tabs (" + excluded.size() + " excluded, " + alreadyListed.size() + " already hand-listed elsewhere).");

        // A concrete, checkable line for the exact "is this actually the price I think it is"
        // question -- read straight from the finished catalog, not recomputed separately, so if
        // this log line shows the right numbers the shop GUI will too. If it's still showing old
        // numbers after a restart, the server is running an old jar, not new prices that failed.
        logSamplePrice("REDSTONE_LAMP");
        logSamplePrice("DAYLIGHT_DETECTOR");
        logSamplePrice("RAIL");
    }

    private void logSamplePrice(String materialName) {
        Material material = parseMaterial(materialName);
        if (material == null) return;
        for (ShopCategory category : categories.values()) {
            for (ShopItem item : category.items()) {
                if (item.material() == material) {
                    plugin.getLogger().info("NexusEconomy: sanity check -- " + materialName + " in \""
                            + category.displayName() + "\": buy $" + String.format("%.2f", item.buy())
                            + " / sell $" + String.format("%.2f", item.sell()));
                    return;
                }
            }
        }
        plugin.getLogger().info("NexusEconomy: sanity check -- " + materialName + " isn't in the shop at all right now.");
    }

    // --- Spawn Eggs: the shop's own dedicated tab, since there's no vanilla survival
    //     way to obtain a spawn egg at all -- this is genuinely the only source for one. ---

    // Passive/farm mobs -- easy to breed or find, cheapest tier.
    private static final java.util.Set<String> SPAWN_EGG_PASSIVE = java.util.Set.of(
            "COW", "PIG", "SHEEP", "CHICKEN", "RABBIT", "HORSE", "DONKEY", "MULE", "LLAMA",
            "TRADER_LLAMA", "CAT", "OCELOT", "PARROT", "TURTLE", "BEE", "FOX", "PANDA",
            "GOAT", "FROG", "TADPOLE", "AXOLOTL", "CAMEL", "SNIFFER", "ARMADILLO",
            "STRIDER", "SQUID", "GLOW_SQUID", "COD", "SALMON", "PUFFERFISH",
            "TROPICAL_FISH", "MOOSHROOM", "BAT", "ALLAY", "WOLF");

    // Neutral, situational, or structure/trade-bound mobs -- a real step above passive.
    private static final java.util.Set<String> SPAWN_EGG_NEUTRAL = java.util.Set.of(
            "POLAR_BEAR", "DOLPHIN", "VILLAGER", "WANDERING_TRADER", "IRON_GOLEM",
            "SNOW_GOLEM", "ZOMBIE_VILLAGER", "SKELETON_HORSE", "ZOMBIE_HORSE");

    // Ordinary overworld hostiles -- dangerous, but common and easy to farm.
    private static final java.util.Set<String> SPAWN_EGG_HOSTILE = java.util.Set.of(
            "ZOMBIE", "SKELETON", "SPIDER", "CREEPER", "HUSK", "DROWNED", "CAVE_SPIDER",
            "SILVERFISH", "SLIME", "ENDERMITE", "PHANTOM", "WITCH", "PILLAGER",
            "VINDICATOR", "BOGGED", "ZOMBIFIED_PIGLIN", "STRAY");

    // Genuinely dangerous or structure/dimension-locked mobs.
    private static final java.util.Set<String> SPAWN_EGG_DANGEROUS = java.util.Set.of(
            "BLAZE", "GHAST", "MAGMA_CUBE", "WITHER_SKELETON", "PIGLIN", "PIGLIN_BRUTE",
            "HOGLIN", "ZOGLIN", "ENDERMAN", "EVOKER", "RAVAGER", "VEX", "SHULKER",
            "GUARDIAN", "BREEZE");

    // The hardest, rarest mobs in the game to deal with -- deep dark exclusive, etc.
    private static final java.util.Set<String> SPAWN_EGG_BOSS_TIER = java.util.Set.of(
            "WARDEN", "ELDER_GUARDIAN");

    /**
     * The ONLY mobs sold as spawn eggs when shop.spawn-eggs.exclude-hostile is true (the
     * default) -- an explicit allowlist, not a blocklist. A previous version of this method
     * checked membership in SPAWN_EGG_HOSTILE/DANGEROUS/BOSS_TIER instead, which meant any
     * mob missing from all three sets fell through as "safe" -- exactly what happened with
     * the Ender Dragon, which was never added to any tier set at all. An allowlist can't have
     * that failure mode: anything not explicitly listed here -- a mob this code doesn't
     * classify, one that was simply missed, or a new hostile mob a future Minecraft version
     * adds -- is excluded by default instead of sold by default.
     *
     * Deliberately narrower than SPAWN_EGG_PASSIVE + SPAWN_EGG_NEUTRAL above: Zombie Villager
     * is left out even though it's priced at the NEUTRAL tier elsewhere, because in actual
     * gameplay it attacks players on sight exactly like a regular Zombie until someone cures
     * it -- "neutral" there was about price/rarity, not behavior.
     */
    private static final java.util.Set<String> SPAWN_EGG_SAFE = java.util.Set.of(
            "COW", "PIG", "SHEEP", "CHICKEN", "RABBIT", "HORSE", "DONKEY", "MULE", "LLAMA",
            "TRADER_LLAMA", "CAT", "OCELOT", "PARROT", "TURTLE", "BEE", "FOX", "PANDA",
            "GOAT", "FROG", "TADPOLE", "AXOLOTL", "CAMEL", "SNIFFER", "ARMADILLO",
            "STRIDER", "SQUID", "GLOW_SQUID", "COD", "SALMON", "PUFFERFISH",
            "TROPICAL_FISH", "MOOSHROOM", "BAT", "ALLAY", "WOLF",
            "POLAR_BEAR", "DOLPHIN", "VILLAGER", "WANDERING_TRADER", "IRON_GOLEM",
            "SNOW_GOLEM", "SKELETON_HORSE", "ZOMBIE_HORSE");

    /** Maps a *_SPAWN_EGG material to a shop.pricing-tiers entry by how dangerous/rare that mob actually is. */
    private String spawnEggTier(Material material) {
        String name = material.name();
        String mob = name.substring(0, name.length() - "_SPAWN_EGG".length());
        if (SPAWN_EGG_BOSS_TIER.contains(mob)) return "MYTHIC";
        if (SPAWN_EGG_DANGEROUS.contains(mob)) return "EXOTIC";
        if (SPAWN_EGG_HOSTILE.contains(mob)) return "PRECIOUS";
        if (SPAWN_EGG_NEUTRAL.contains(mob)) return "STANDARD";
        if (SPAWN_EGG_PASSIVE.contains(mob)) return "COMMON";
        return "STANDARD"; // a mob added by a newer MC version than this list knows about -- a safe, moderate default
    }

    /**
     * Auto-populates a dedicated "Spawn Eggs" tab with every *_SPAWN_EGG material the
     * server knows about. Buy-only -- there's no vanilla survival way to earn a spawn
     * egg to sell back in the first place, so a sell side would just be free money --
     * and priced by danger/rarity (see the tier sets above) through the same
     * shop.pricing-tiers ladder as everything else, so retuning a tier there re-prices
     * these too. Disable entirely, exclude specific mobs, or pin an individual mob to
     * a different tier via shop.spawn-eggs in config.yml.
     *
     * shop.spawn-eggs.exclude-hostile (default true) restricts the tab to ONLY the mobs in
     * SPAWN_EGG_SAFE above -- an allowlist, not a blocklist, specifically so an unclassified
     * or overlooked mob (the Ender Dragon, previously) can never slip through as sellable by
     * default. This is a hard restriction, checked before tier-overrides, so it can't be
     * re-added via an override; add a mob to SPAWN_EGG_SAFE in code if it genuinely needs to
     * be sellable again.
     */
    private void generateSpawnEggs() {
        if (!plugin.getConfig().getBoolean("shop.spawn-eggs.enabled", true)) return;
        if (categories.containsKey("Spawn Eggs")) return; // already hand-listed -- don't clobber it

        double buyMultiplier = plugin.getConfig().getDouble("shop.buy-price-multiplier", 1.0);
        double eggMultiplier = plugin.getConfig().getDouble("shop.spawn-eggs.price-multiplier", 1.0);
        boolean excludeHostile = plugin.getConfig().getBoolean("shop.spawn-eggs.exclude-hostile", true);

        Set<Material> excluded = EnumSet.noneOf(Material.class);
        for (String name : plugin.getConfig().getStringList("shop.spawn-eggs.excluded")) {
            Material material = parseMaterial(name.trim());
            if (material != null) excluded.add(material);
        }
        ConfigurationSection tierOverrides = plugin.getConfig().getConfigurationSection("shop.spawn-eggs.tier-overrides");

        List<ShopItem> eggs = new ArrayList<>();
        int hostileSkipped = 0;
        for (Material material : Material.values()) {
            try {
                if (material.isLegacy() || !material.isItem() || !material.name().endsWith("_SPAWN_EGG")) continue;
                if (excluded.contains(material)) continue;

                String mob = material.name().substring(0, material.name().length() - "_SPAWN_EGG".length());
                if (excludeHostile && !SPAWN_EGG_SAFE.contains(mob)) {
                    hostileSkipped++;
                    continue;
                }

                String tierName = tierOverrides != null ? tierOverrides.getString(material.name()) : null;
                if (tierName == null) tierName = spawnEggTier(material);
                double[] prices = resolveTier(tierName);

                double buy = round2(prices[0] * eggMultiplier * buyMultiplier);
                eggs.add(new ShopItem(material, buy, -1)); // buy-only -- see method comment above
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "NexusEconomy: couldn't auto-catalog spawn egg " + material + ", skipping it.", e);
            }
        }

        eggs.sort((a, b) -> a.material().name().compareTo(b.material().name()));
        if (!eggs.isEmpty()) {
            categories.put("Spawn Eggs", new ShopCategory("Spawn Eggs", "Spawn Eggs", Material.ZOMBIE_SPAWN_EGG, eggs));
        }
        plugin.getLogger().info("NexusEconomy: auto-added " + eggs.size() + " spawn egg(s)"
                + (excludeHostile ? " (" + hostileSkipped + " hostile mob(s) excluded)." : "."));
    }

    // Dedicated category classifier. Specific families are checked before broad
    // building-material families so (for example) stained glass goes to Glass,
    // carpets go to Carpets, and leaves/saplings never disappear into Wood.
    private static final List<String> WOOD_SPECIES = List.of(
            "OAK", "SPRUCE", "BIRCH", "JUNGLE", "ACACIA", "DARK_OAK", "MANGROVE", "CHERRY", "BAMBOO");

    private static final List<String> FLOWER_TOKENS = List.of(
            "DANDELION", "POPPY", "ORCHID", "ALLIUM", "AZURE_BLUET", "TULIP", "OXEYE_DAISY",
            "CORNFLOWER", "LILY_OF_THE_VALLEY", "WITHER_ROSE", "SUNFLOWER", "LILAC", "ROSE_BUSH",
            "PEONY", "TORCHFLOWER", "PITCHER_PLANT", "PINK_PETALS", "WILDFLOWERS", "EYEBLOSSOM");

    private static final List<String> PLANT_TOKENS = List.of(
            "GRASS", "FERN", "BUSH", "CACTUS", "SUGAR_CANE", "BAMBOO", "KELP", "SEAGRASS",
            "LILY_PAD", "VINE", "MOSS", "LICHEN", "DRIPLEAF", "ROOTS", "HANGING_ROOTS", "SPORE_BLOSSOM");

    private static final List<String> REDSTONE_TOKENS = List.of(
            "REDSTONE", "REPEATER", "COMPARATOR", "PISTON", "OBSERVER", "HOPPER", "DISPENSER",
            "DROPPER", "TARGET", "TRIPWIRE", "DAYLIGHT_DETECTOR", "RAIL", "LEVER", "PRESSURE_PLATE",
            "BUTTON", "LIGHTNING_ROD", "CRAFTER");

    private static final List<String> WORKSTATION_TOKENS = List.of(
            "CRAFTING_TABLE", "FURNACE", "SMOKER", "BLAST_FURNACE", "CARTOGRAPHY_TABLE",
            "FLETCHING_TABLE", "GRINDSTONE", "LOOM", "STONECUTTER", "BREWING_STAND", "COMPOSTER",
            "SMITHING_TABLE", "ENCHANTING_TABLE", "ANVIL", "LECTERN");

    private static final List<String> STORAGE_TOKENS = List.of(
            "CHEST", "BARREL", "SHULKER_BOX", "BUNDLE", "ENDER_CHEST", "DECORATED_POT");

    private static final List<String> LIGHTING_TOKENS = List.of(
            "TORCH", "LANTERN", "CANDLE", "GLOWSTONE", "SEA_LANTERN", "SHROOMLIGHT", "FROGLIGHT",
            "END_ROD", "OCHRE_FROGLIGHT", "PEARLESCENT_FROGLIGHT", "VERDANT_FROGLIGHT");

    private static final List<String> TERRAIN_TOKENS = List.of(
            "DIRT", "GRASS_BLOCK", "PATH", "FARMLAND", "PODZOL", "MYCELIUM", "CLAY", "SAND",
            "GRAVEL", "SNOW", "ICE", "SPONGE", "SLIME", "HONEY", "HONEYCOMB", "MUD");

    private static boolean hasToken(String name, String token) {
        return name.equals(token) || name.startsWith(token + "_") || name.endsWith("_" + token) || name.contains("_" + token + "_");
    }

    private static boolean hasAnyToken(String name, List<String> tokens) {
        for (String token : tokens) if (hasToken(name, token)) return true;
        return false;
    }

    // Non-block items that don't fit a clean name-pattern rule and would otherwise
    // just fall into Miscellaneous -- routed to whichever existing hand-curated tab
    // fits them best. Anything NOT listed here still gets added (via Miscellaneous),
    // it just isn't specifically sorted -- see classifyAutoItem().
    private static final java.util.Set<String> BREWING_ITEMS = java.util.Set.of(
            "BLAZE_POWDER", "MAGMA_CREAM", "FERMENTED_SPIDER_EYE", "SPIDER_EYE",
            "GLISTERING_MELON_SLICE", "GOLDEN_CARROT", "RABBIT_FOOT", "PUFFERFISH",
            "DRAGON_BREATH", "GLASS_BOTTLE");

    private static final java.util.Set<String> MOB_DROP_ITEMS = java.util.Set.of(
            "INK_SAC", "GLOW_INK_SAC", "BONE_MEAL", "FEATHER", "LEATHER", "RABBIT_HIDE",
            "PHANTOM_MEMBRANE", "SHULKER_SHELL", "NAUTILUS_SHELL", "HEART_OF_THE_SEA",
            "SCUTE", "ARMADILLO_SCUTE", "SLIME_BALL", "PRISMARINE_SHARD",
            "PRISMARINE_CRYSTALS", "ECHO_SHARD", "BREEZE_ROD", "RESIN_CLUMP");

    private static final java.util.Set<String> FOOD_ITEMS = java.util.Set.of(
            "BEEF", "CHICKEN", "PORKCHOP", "MUTTON", "RABBIT", "COD", "SALMON", "EGG",
            "MILK_BUCKET", "HONEY_BOTTLE", "HONEYCOMB", "PUMPKIN_PIE", "COOKIE", "CAKE",
            "MUSHROOM_STEW", "RABBIT_STEW", "BEETROOT_SOUP", "BAKED_POTATO",
            "DRIED_KELP", "GLOW_BERRIES", "SWEET_BERRIES", "CHORUS_FRUIT");

    private static final java.util.Set<String> UTILITY_ITEMS = java.util.Set.of(
            "COMPASS", "RECOVERY_COMPASS", "CLOCK", "MAP", "SPYGLASS", "LEAD", "NAME_TAG",
            "SADDLE", "ELYTRA", "BUCKET", "WATER_BUCKET", "LAVA_BUCKET",
            "POWDER_SNOW_BUCKET", "BUNDLE", "BRUSH", "SHEARS", "FLINT_AND_STEEL",
            "CARROT_ON_A_STICK", "WARPED_FUNGUS_ON_A_STICK", "TOTEM_OF_UNDYING",
            "TRIAL_KEY", "OMINOUS_TRIAL_KEY");

    /**
     * Classifies non-block items only -- checked before any of the block-oriented
     * rules below run, so an item name that happens to contain a block-ish substring
     * (e.g. a hypothetical "STONE_HOE") can never get miscategorized by them. Returns
     * null for anything that doesn't match a specific family, which falls back to
     * Miscellaneous in {@link #classifyAutoMaterial}.
     */
    private String classifyAutoItem(String name) {
        if (name.equals("FISHING_ROD")) return "Fishing";
        if (name.equals("ARROW") || name.equals("SPECTRAL_ARROW")) return "Ranged";
        if (name.equals("MACE")) return "Weapons";
        if (name.endsWith("_HORSE_ARMOR")) return "Armor";
        if (name.startsWith("MUSIC_DISC_")) return "Music Discs";
        if (name.endsWith("_BANNER_PATTERN")) return "Banner Patterns";
        if (name.endsWith("_NUGGET")) return "Ores & Minerals";
        if (name.contains("MINECART") || name.endsWith("_BOAT") || name.endsWith("_CHEST_BOAT")) return "Utility";
        if (BREWING_ITEMS.contains(name)) return "Brewing";
        if (MOB_DROP_ITEMS.contains(name)) return "Mob Drops";
        if (FOOD_ITEMS.contains(name)) return "Food";
        if (UTILITY_ITEMS.contains(name)) return "Utility";
        return null;
    }

    private String classifyAutoMaterial(Material material) {
        String name = material.name();

        if (!material.isBlock()) {
            String itemBucket = classifyAutoItem(name);
            return itemBucket != null ? itemBucket : "Miscellaneous";
        }

        if (name.contains("GLASS")) return "Glass";
        if (name.endsWith("_CARPET") || name.equals("MOSS_CARPET")) return "Carpets";
        if (name.endsWith("_WOOL")) return "Wool";
        if (name.endsWith("_CONCRETE_POWDER")) return "Concrete Powder";
        if (name.endsWith("_CONCRETE")) return "Concrete";
        if (name.endsWith("_GLAZED_TERRACOTTA")) return "Glazed Terracotta";
        if (name.endsWith("_TERRACOTTA") || name.equals("TERRACOTTA")) return "Terracotta";

        if (name.endsWith("_LEAVES") || name.endsWith("_SAPLING") || name.equals("MANGROVE_PROPAGULE")
                || name.contains("AZALEA") || name.equals("COCOA") || name.equals("COCOA_BEANS")) return "Tree Products";
        if (hasAnyToken(name, FLOWER_TOKENS)) return "Flowers";
        if (name.contains("MUSHROOM") || name.endsWith("_FUNGUS") || name.contains("MUSHROOM_BLOCK")) return "Mushrooms";
        if (hasAnyToken(name, PLANT_TOKENS)) return "Plants";

        if (name.startsWith("NETHER_") || name.contains("NETHERRACK") || name.contains("SOUL_")
                || name.contains("BASALT") || name.contains("BLACKSTONE") || name.contains("NYLIUM")
                || name.contains("CRIMSON") || name.contains("WARPED") || name.contains("MAGMA")) return "Nether";
        if (name.startsWith("END_") || name.contains("PURPUR") || name.contains("CHORUS") || name.equals("DRAGON_EGG")) return "End";

        if (name.endsWith("_ORE") || (name.endsWith("_BLOCK") && ORE_METAL_PREFIXES.contains(name.substring(0, name.length() - 6)))) return "Ores & Minerals";
        if (name.endsWith("_DOOR") || name.endsWith("_TRAPDOOR")) return "Doors & Trapdoors";
        if (name.endsWith("_FENCE") || name.endsWith("_FENCE_GATE") || name.endsWith("_WALL") || name.equals("IRON_BARS")) return "Fences & Walls";
        if (hasAnyToken(name, STORAGE_TOKENS)) return "Storage";
        if (hasAnyToken(name, WORKSTATION_TOKENS)) return "Workstations";
        if (hasAnyToken(name, LIGHTING_TOKENS)) return "Lighting";
        if (hasAnyToken(name, REDSTONE_TOKENS)) return "Redstone";

        if (name.contains("BRICK") || name.contains("PRISMARINE") || name.contains("QUARTZ")
                || name.contains("TILE") || name.contains("CHISELED")) return "Bricks & Masonry";
        if (WOOD_SPECIES.stream().anyMatch(species -> hasToken(name, species)) || name.contains("PLANKS")
                || name.contains("LOG") || name.contains("WOOD") || name.contains("STEM") || name.contains("HYPHAE")
                || name.contains("SIGN") || name.equals("LADDER") || name.equals("SCAFFOLDING")) return "Wood";
        if (name.contains("STONE") || name.contains("COBBLE") || name.contains("DEEPSLATE") || name.contains("TUFF")
                || name.contains("GRANITE") || name.contains("DIORITE") || name.contains("ANDESITE")
                || name.contains("CALCITE") || name.contains("DRIPSTONE")) return "Stone";
        if (hasAnyToken(name, TERRAIN_TOKENS)) return "Terrain";
        if (name.contains("CORAL") || name.contains("POT") || name.contains("BANNER") || name.contains("BED")
                || name.contains("SKULL") || name.contains("HEAD") || name.equals("CHAIN") || name.equals("PAINTING")
                || name.contains("ITEM_FRAME") || name.equals("ARMOR_STAND") || name.equals("JUKEBOX")) return "Decoration";

        return "Miscellaneous";
    }

    /**
     * Gives every auto-catalog item a stable, individual dollars-and-cents price.
     * The category tier supplies the economic scale; crafting shape, processing,
     * rarity signals, and a tiny deterministic material offset stop hundreds of
     * unrelated blocks from sharing one identical price.
     */
    private double[] varyAutoPrice(Material material, String category, double baseBuy, double baseSell) {
        String name = material.name();
        double effort = 1.0;

        if (name.endsWith("_SLAB")) effort *= 0.58;
        else if (name.endsWith("_STAIRS")) effort *= 0.86;
        else if (name.endsWith("_WALL")) effort *= 0.72;
        else if (name.endsWith("_FENCE")) effort *= 0.78;
        else if (name.endsWith("_FENCE_GATE")) effort *= 1.18;
        else if (name.endsWith("_DOOR")) effort *= 1.35;
        else if (name.endsWith("_TRAPDOOR")) effort *= 1.12;
        else if (name.endsWith("_PANE")) effort *= 0.46;
        else if (name.endsWith("_CARPET")) effort *= 0.42;

        if (name.contains("POLISHED") || name.contains("CUT_") || name.contains("CHISELED")) effort *= 1.18;
        if (name.contains("BRICK") || name.contains("TILE")) effort *= 1.28;
        if (name.contains("WAXED")) effort *= 1.22;
        if (name.contains("OXIDIZED") || name.contains("WEATHERED")) effort *= 1.30;
        if (name.contains("GLAZED")) effort *= 1.42;
        if (name.contains("INFESTED")) effort *= 1.55;
        if (name.contains("SCULK") || name.contains("AMETHYST")) effort *= 1.70;
        if (category.equals("Tree Products") || category.equals("Plants") || category.equals("Flowers")) effort *= 0.62;
        if (category.equals("Nether")) effort *= 1.30;
        if (category.equals("End")) effort *= 1.65;

        int hash = Math.floorMod(name.hashCode(), 1000);
        double individual = 0.91 + (hash / 1000.0) * 0.18; // stable 0.91 .. 1.08982
        double buy = Math.max(0.01, round2(baseBuy * effort * individual + (hash % 97) / 100.0));

        // Selling remains intentionally far below buying. Harder categories get
        // a slightly better return, rewarding gathering without enabling flips.
        double returnFactor = switch (category) {
            case "Ores & Minerals", "Nether", "End" -> 1.08;
            case "Redstone", "Workstations", "Storage" -> 1.03;
            case "Plants", "Flowers", "Tree Products", "Terrain" -> 0.92;
            default -> 1.0;
        };
        double sell = Math.max(0.01, round2(baseSell * effort * individual * returnFactor + (hash % 43) / 100.0));
        if (sell >= buy) sell = round2(Math.max(0.01, buy * 0.22));
        return new double[]{buy, sell};
    }

    private static final Set<String> ORE_METAL_PREFIXES = Set.of(
            "COAL", "IRON", "GOLD", "DIAMOND", "EMERALD", "LAPIS", "COPPER", "NETHERITE",
            "QUARTZ", "AMETHYST", "RAW_IRON", "RAW_GOLD", "RAW_COPPER");

    private Material autoCategoryIcon(String bucketName) {
        return switch (bucketName) {
            case "Glass" -> Material.GLASS;
            case "Carpets" -> Material.RED_CARPET;
            case "Wool" -> Material.WHITE_WOOL;
            case "Concrete" -> Material.WHITE_CONCRETE;
            case "Concrete Powder" -> Material.WHITE_CONCRETE_POWDER;
            case "Terracotta" -> Material.TERRACOTTA;
            case "Glazed Terracotta" -> Material.WHITE_GLAZED_TERRACOTTA;
            case "Tree Products" -> Material.OAK_SAPLING;
            case "Flowers" -> Material.POPPY;
            case "Plants" -> Material.FERN;
            case "Mushrooms" -> Material.RED_MUSHROOM;
            case "Wood" -> Material.OAK_PLANKS;
            case "Doors & Trapdoors" -> Material.OAK_DOOR;
            case "Fences & Walls" -> Material.OAK_FENCE;
            case "Stone" -> Material.STONE_BRICKS;
            case "Bricks & Masonry" -> Material.BRICKS;
            case "Ores & Minerals" -> Material.DIAMOND_ORE;
            case "Redstone" -> Material.REDSTONE;
            case "Lighting" -> Material.LANTERN;
            case "Storage" -> Material.CHEST;
            case "Workstations" -> Material.CRAFTING_TABLE;
            case "Decoration" -> Material.PAINTING;
            case "Nether" -> Material.NETHERRACK;
            case "End" -> Material.END_STONE;
            case "Terrain" -> Material.GRASS_BLOCK;
            case "Music Discs" -> Material.JUKEBOX;
            case "Banner Patterns" -> Material.LOOM;
            default -> Material.CHEST;
        };
    }

    // --- Custom items (admin-added, buy-only, arbitrary ItemStacks with their own NBT) ---

    private void loadCustomItems() {
        if (!customItemsFile.exists()) return;
        YamlConfiguration data = YamlConfiguration.loadConfiguration(customItemsFile);
        ConfigurationSection section = data.getConfigurationSection("items");
        if (section == null) return;

        for (String key : section.getKeys(false)) {
            ItemStack item = section.getItemStack(key + ".item");
            if (item == null) continue;
            double buy = section.getDouble(key + ".buy");
            customItems.add(new CustomShopEntry(item, buy));
        }
    }

    private void saveCustomItems() {
        YamlConfiguration data = new YamlConfiguration();
        for (int i = 0; i < customItems.size(); i++) {
            CustomShopEntry entry = customItems.get(i);
            data.set("items." + i + ".item", entry.item());
            data.set("items." + i + ".buy", entry.buy());
        }
        try {
            data.save(customItemsFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "NexusEconomy: failed to save custom-items.yml", e);
        }
    }

    /** Registers whatever's in the admin's hand as a new Custom-tab entry. */
    public void addCustomItem(ItemStack item, double buy) {
        customItems.add(new CustomShopEntry(item.clone(), buy));
        saveCustomItems();
    }

    public boolean removeCustomItem(int index) {
        if (index < 0 || index >= customItems.size()) return false;
        customItems.remove(index);
        saveCustomItems();
        return true;
    }

    public List<CustomShopEntry> customItems() {
        return customItems;
    }

    // --- Enchantments tab (auto-generated vanilla books + admin-added custom enchant items,
    //     e.g. NexusEnchants' own custom boot enchants -- both buy-only, same reasoning as
    //     Custom above: NBT/level combinations aren't something the plain Material-keyed
    //     ShopItem model can price individually) ---

    private void loadEnchantItems() {
        if (!enchantItemsFile.exists()) return;
        YamlConfiguration data = YamlConfiguration.loadConfiguration(enchantItemsFile);
        ConfigurationSection section = data.getConfigurationSection("items");
        if (section == null) return;

        for (String key : section.getKeys(false)) {
            ItemStack item = section.getItemStack(key + ".item");
            if (item == null) continue;
            double buy = section.getDouble(key + ".buy");
            enchantItems.add(new CustomShopEntry(item, buy));
        }
    }

    private void saveEnchantItems() {
        YamlConfiguration data = new YamlConfiguration();
        for (int i = 0; i < enchantItems.size(); i++) {
            CustomShopEntry entry = enchantItems.get(i);
            data.set("items." + i + ".item", entry.item());
            data.set("items." + i + ".buy", entry.buy());
        }
        try {
            data.save(enchantItemsFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "NexusEconomy: failed to save enchant-items.yml", e);
        }
    }

    /**
     * Registers whatever's in the admin's hand as a new Enchantments-tab entry -- meant for
     * NexusEnchants' own custom boot enchants (Lava Walker, Tide Walker, etc.), which this
     * plugin has no other way to reproduce correctly: those are specific PDC-tagged ItemStacks
     * another plugin creates, not something buildable from a Material + Enchantment alone.
     * Hold the actual item (e.g. from /nexusenchants give to yourself) and run
     * /shop addenchant <price> -- same pattern as /shop addcustom.
     */
    public void addEnchantItem(ItemStack item, double buy) {
        enchantItems.add(new CustomShopEntry(item.clone(), buy));
        saveEnchantItems();
    }

    public boolean removeEnchantItem(int index) {
        if (index < 0 || index >= enchantItems.size()) return false;
        enchantItems.remove(index);
        saveEnchantItems();
        return true;
    }

    public List<CustomShopEntry> enchantItems() {
        return enchantItems;
    }

    /**
     * Auto-populates the Enchantments tab with a maxed-level enchanted book for every vanilla
     * Enchantment the server knows about -- these are meant as genuine end-game money sinks, not
     * something a new player stumbles into affording, so pricing scales sharply with how strong
     * the enchantment actually is (its own max level) and whether it's treasure/curse-only
     * (both meaningfully harder to get any other way than paying for it here). Regenerated fresh
     * every startup -- these never get persisted to enchant-items.yml, only admin-added custom
     * entries do, so retuning shop.enchant-books in config.yml takes effect on the next restart
     * without needing to touch or wipe that file.
     */
    private void generateEnchantBooks() {
        if (!plugin.getConfig().getBoolean("shop.enchant-books.enabled", true)) return;

        double basePrice = plugin.getConfig().getDouble("shop.enchant-books.base-price", 15000.0);
        double perLevelExponent = plugin.getConfig().getDouble("shop.enchant-books.per-level-exponent", 1.4);
        double treasureMultiplier = plugin.getConfig().getDouble("shop.enchant-books.treasure-multiplier", 2.5);
        double curseMultiplier = plugin.getConfig().getDouble("shop.enchant-books.curse-multiplier", 1.8);

        for (Enchantment enchantment : org.bukkit.Registry.ENCHANTMENT) {
            try {
                int maxLevel = enchantment.getMaxLevel();
                ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
                ItemMeta meta = book.getItemMeta();
                if (!(meta instanceof EnchantmentStorageMeta storageMeta)) continue;
                storageMeta.addStoredEnchant(enchantment, maxLevel, true);
                storageMeta.setDisplayName(ChatColor.LIGHT_PURPLE + prettyEnchantName(enchantment) + " "
                        + toRoman(maxLevel));
                book.setItemMeta(storageMeta);

                double price = basePrice * Math.pow(maxLevel, perLevelExponent);
                if (enchantment.isTreasure()) price *= treasureMultiplier;
                if (enchantment.isCursed()) price *= curseMultiplier;
                // Stable per-enchant variation so two enchantments with identical max level don't
                // land on the exact same price -- same pattern as varyAutoPrice's hash use below.
                int hash = Math.floorMod(enchantment.getKey().getKey().hashCode(), 1000);
                price *= 0.9 + (hash / 1000.0) * 0.2; // 0.90 .. 1.10
                double buy = Math.max(basePrice, Math.round(price / 100.0) * 100.0); // round to nearest $100

                enchantItems.add(new CustomShopEntry(book, buy));
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "NexusEconomy: couldn't build a shop entry for enchantment "
                        + enchantment.getKey() + ", skipping it.", e);
            }
        }
    }

    /**
     * Cross-plugin tab: one max-level tome per NexusEnchants custom enchant (184 as of
     * v0.11.1), plus the four Lava/Tide Walker items (boots + scroll for each element),
     * all pulled live through {@link NexusEnchantsBridge} -- nothing about another
     * plugin's specific enchants is hardcoded here, so this keeps working if NexusEnchants
     * adds more later.
     *
     * Pricing is deliberately wild and per-item unique, per how this tab was asked for:
     * base-price * category-multiplier * maxLevel^per-level-exponent * (curse ?
     * curse-multiplier : 1) * a stable-but-scattered per-id variance roll (variance-min..
     * variance-max), then rounded to the nearest round-to. The category multiplier alone
     * spans an 8x range (Lead/Spyglass novelty stuff vs. UNIVERSAL enchants like Soulbound,
     * which work on literally anything you own), the exponent means a max-level-1 curse and
     * a max-level-3 UNIVERSAL enchant aren't even in the same galaxy price-wise, and the
     * variance roll means no two enchants -- even two identical-category, identical-max-level
     * ones -- land on the same number. Regenerated fresh every startup, same as
     * generateEnchantBooks() above -- never persisted, so retuning shop.nexus-enchants in
     * config.yml takes effect on the next restart.
     *
     * If NexusEnchantsBridge isn't connected (NexusEnchants isn't installed, or hasn't
     * enabled yet), this silently leaves nexusEnchantItems empty and buildCategoryMenu()
     * simply omits the NexusEnchants button -- same graceful-degradation behavior as the
     * NexusSeasons integration.
     */
    private void generateNexusEnchantItems() {
        if (!plugin.getConfig().getBoolean("shop.nexus-enchants.enabled", true)) return;
        if (!enchantsBridge.isConnected()) return;

        double basePrice = plugin.getConfig().getDouble("shop.nexus-enchants.base-price", 30000.0);
        double perLevelExponent = plugin.getConfig().getDouble("shop.nexus-enchants.per-level-exponent", 1.6);
        double curseMultiplier = plugin.getConfig().getDouble("shop.nexus-enchants.curse-multiplier", 0.4);
        double varianceMin = plugin.getConfig().getDouble("shop.nexus-enchants.variance-min", 0.4);
        double varianceMax = plugin.getConfig().getDouble("shop.nexus-enchants.variance-max", 3.2);
        double roundTo = plugin.getConfig().getDouble("shop.nexus-enchants.round-to", 137.0);
        double specialBasePrice = plugin.getConfig().getDouble("shop.nexus-enchants.special-base-price", 500000.0);
        double specialScrollMultiplier = plugin.getConfig().getDouble("shop.nexus-enchants.special-scroll-multiplier", 0.35);

        ConfigurationSection categorySection = plugin.getConfig().getConfigurationSection("shop.nexus-enchants.category-multipliers");

        for (String id : enchantsBridge.allEnchantIds()) {
            try {
                String displayName = enchantsBridge.displayName(id);
                String category = enchantsBridge.categoryName(id);
                if (displayName == null || category == null) continue;
                int maxLevel = Math.max(1, enchantsBridge.maxLevel(id));
                boolean curse = enchantsBridge.isCurse(id);

                ItemStack tome = enchantsBridge.createTome(id, maxLevel);
                if (tome == null || !tome.hasItemMeta()) continue;

                double categoryMultiplier = categorySection != null
                        ? categorySection.getDouble(category, defaultCategoryMultiplier(category))
                        : defaultCategoryMultiplier(category);

                double price = basePrice * categoryMultiplier * Math.pow(maxLevel, perLevelExponent);
                if (curse) price *= curseMultiplier;

                // Stable-but-scattered per-id variance -- see the method doc above for why this
                // is deliberately wide (0.4x..3.2x by default), unlike the tighter 0.9-1.1 roll
                // generateEnchantBooks() uses for vanilla enchants.
                int hash = Math.floorMod(id.hashCode(), 10007);
                double variance = varianceMin + (hash / 10007.0) * (varianceMax - varianceMin);
                price *= variance;

                double buy = Math.max(1000.0, Math.round(price / roundTo) * roundTo);

                ItemMeta meta = tome.getItemMeta();
                List<String> lore = new ArrayList<>(meta.hasLore() && meta.getLore() != null ? meta.getLore() : List.of());
                lore.add("");
                lore.add(ChatColor.DARK_AQUA + "NexusEnchants" + ChatColor.GRAY + " -- " + prettyCategoryName(category));
                if (curse) lore.add(ChatColor.RED + "Curse");
                meta.setLore(lore);
                tome.setItemMeta(meta);

                nexusEnchantItems.add(new CustomShopEntry(tome, buy));
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "NexusEconomy: couldn't build a shop entry for NexusEnchants enchant \""
                        + id + "\", skipping it.", e);
            }
        }

        addSpecialWalkerItem(enchantsBridge.createLavaWalkerBoots(), specialBasePrice, "lava_walker_boots", roundTo);
        addSpecialWalkerItem(enchantsBridge.createTideWalkerBoots(), specialBasePrice, "tide_walker_boots", roundTo);
        addSpecialWalkerItem(enchantsBridge.createLavaWalkerScroll(), specialBasePrice * specialScrollMultiplier, "lava_walker_scroll", roundTo);
        addSpecialWalkerItem(enchantsBridge.createTideWalkerScroll(), specialBasePrice * specialScrollMultiplier, "tide_walker_scroll", roundTo);
    }

    /** Lava/Tide Walker boots + scrolls -- priced as top-end one-of-a-kind items, not run through the per-enchant formula above. */
    private void addSpecialWalkerItem(ItemStack item, double baseValue, String varianceSeed, double roundTo) {
        if (item == null || !item.hasItemMeta()) return;
        try {
            int hash = Math.floorMod(varianceSeed.hashCode(), 10007);
            double variance = 0.7 + (hash / 10007.0) * 0.9; // 0.70 .. 1.60 -- narrower than the enchant roll above, these already start at a top-end base price
            double buy = Math.max(1000.0, Math.round((baseValue * variance) / roundTo) * roundTo);
            nexusEnchantItems.add(new CustomShopEntry(item, buy));
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "NexusEconomy: couldn't build a NexusEnchants shop entry for " + varianceSeed, e);
        }
    }

    private double defaultCategoryMultiplier(String category) {
        return switch (category) {
            case "WEAPON" -> 3.5;
            case "ARMOR" -> 3.2;
            case "TOOL" -> 2.0;
            case "BOW" -> 3.0;
            case "FISHING_ROD" -> 1.6;
            case "SHIELD" -> 2.4;
            case "TRIDENT" -> 4.0;
            case "SHEARS" -> 1.2;
            case "ELYTRA" -> 5.0;
            case "MACE" -> 4.5;
            case "HORSE_ARMOR" -> 1.8;
            case "COMPASS" -> 1.4;
            case "TOTEM" -> 6.0;
            case "SPYGLASS" -> 1.1;
            case "CARVED_PUMPKIN" -> 1.3;
            case "FIREWORK_ROCKET" -> 1.5;
            case "LEAD" -> 1.0;
            case "CROSSBOW" -> 3.0;
            case "UNIVERSAL" -> 8.0;
            default -> 2.0;
        };
    }

    private String prettyCategoryName(String category) {
        String raw = category.toLowerCase().replace('_', ' ');
        StringBuilder sb = new StringBuilder();
        for (String word : raw.split(" ")) {
            if (!word.isEmpty()) sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1)).append(' ');
        }
        return sb.toString().trim();
    }

    /**
     * Same startup-race problem as ensureAccessoryItemsLoaded() below, and the same fix: without
     * this, if NexusEnchants hadn't finished registering its service by the exact instant
     * NexusEconomy's constructor called generateNexusEnchantItems(), nexusEnchantItems was left
     * permanently empty and the "NexusEnchants" tab would never show up, even after the bridge
     * finished connecting moments later -- nothing ever tried building the list again.
     */
    private void ensureNexusEnchantItemsLoaded() {
        if (nexusEnchantItems.isEmpty() && enchantsBridge.isConnected()) {
            generateNexusEnchantItems();
        }
    }

    public List<CustomShopEntry> nexusEnchantItems() {
        ensureNexusEnchantItemsLoaded();
        return nexusEnchantItems;
    }

    /**
     * Cross-plugin tab: every accessory NexusAccessories knows about (Rings/Belts/Capes/Charms),
     * pulled live through {@link NexusAccessoriesBridge} -- same reasoning as
     * generateNexusEnchantItems() above. Priced by tier through its own dedicated
     * shop.accessories config block rather than the plain shop.pricing-tiers table: these are
     * permanent, stacking power upgrades in the same league as enchant tomes, not everyday
     * sellable blocks, so they get their own (much higher) price scale -- base-price *
     * tier-multiplier * a per-accessory variance roll, rounded to shop.accessories.round-to.
     *
     * If NexusAccessoriesBridge isn't connected, this leaves accessoryItems empty and
     * buildCategoryMenu() omits both the Accessories tab and the Accessory Slots button.
     */
    private void generateAccessoryItems() {
        if (!plugin.getConfig().getBoolean("shop.accessories.enabled", true)) return;
        if (!accessoriesBridge.isConnected()) return;

        double basePrice = plugin.getConfig().getDouble("shop.accessories.base-price", 50000.0);
        double varianceMin = plugin.getConfig().getDouble("shop.accessories.variance-min", 0.6);
        double varianceMax = plugin.getConfig().getDouble("shop.accessories.variance-max", 1.6);
        double roundTo = plugin.getConfig().getDouble("shop.accessories.round-to", 111.0);
        ConfigurationSection tierSection = plugin.getConfig().getConfigurationSection("shop.accessories.tier-multipliers");

        for (String id : accessoriesBridge.allAccessoryIds()) {
            try {
                String tier = accessoriesBridge.tierName(id);
                if (tier == null) continue;

                ItemStack item = accessoriesBridge.createItem(id);
                if (item == null || !item.hasItemMeta()) continue;

                double tierMultiplier = tierSection != null
                        ? tierSection.getDouble(tier, defaultAccessoryTierMultiplier(tier))
                        : defaultAccessoryTierMultiplier(tier);

                int hash = Math.floorMod(id.hashCode(), 10007);
                double variance = varianceMin + (hash / 10007.0) * (varianceMax - varianceMin);
                double price = basePrice * tierMultiplier * variance;
                double buy = Math.max(1000.0, Math.round(price / roundTo) * roundTo);

                accessoryItems.add(new CustomShopEntry(item, buy));
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "NexusEconomy: couldn't build a shop entry for NexusAccessories item \""
                        + id + "\", skipping it.", e);
            }
        }
    }

    private double defaultAccessoryTierMultiplier(String tier) {
        return switch (tier) {
            case "SCRAP" -> 1.0;
            case "COMMON" -> 2.0;
            case "STANDARD" -> 4.0;
            case "REFINED" -> 8.0;
            case "PRECIOUS" -> 16.0;
            case "EXOTIC" -> 32.0;
            case "MYTHIC" -> 64.0;
            default -> 2.0;
        };
    }

    /**
     * generateAccessoryItems() above ran exactly once, at construction time (see the
     * constructor), which raced NexusAccessories' own startup: softdepend only guarantees load
     * *order*, not that NexusAccessories has finished calling Bukkit.getServicesManager().register()
     * by the instant NexusEconomy's constructor runs. If that race was lost, accessoriesBridge
     * wasn't connected yet, generateAccessoryItems() returned immediately, and accessoryItems
     * was left permanently empty -- even after the bridge finishes connecting moments later --
     * because nothing ever called it again. That's why "Accessory Slots" (which calls
     * accessoriesBridge.isConnected() fresh every time buildCategoryMenu() runs) could work
     * while "Accessories" (reading this stale one-time list) never showed up at all.
     *
     * This retries the one-time generation lazily: the first time anything actually needs the
     * list and finds it empty while the bridge reports connected, it tries again right then.
     * Once it succeeds once, accessoryItems is non-empty and this becomes a no-op forever after.
     */
    private void ensureAccessoryItemsLoaded() {
        if (accessoryItems.isEmpty() && accessoriesBridge.isConnected()) {
            generateAccessoryItems();
        }
    }

    public List<CustomShopEntry> accessoryItems() {
        ensureAccessoryItemsLoaded();
        return accessoryItems;
    }

    // --- Potions: a dedicated tab covering every brewable potion effect, buy-only, priced
    //     deliberately low -- these are everyday consumables a beginner/mid-game player
    //     should be able to afford, not another money sink like Enchantments/NexusEnchants. ---

    // Base (non-effect) potion types -- Water Bottle, and the brewing-stand intermediates
    // (Mundane/Thick/Awkward). Nobody buys these for their effect because they don't have
    // one; skipped entirely rather than listed at a token price.
    private static final Set<String> POTION_NO_EFFECT = Set.of("WATER", "MUNDANE", "THICK", "AWKWARD", "UNCRAFTABLE");

    // Cheap, everyday utility/negative effects -- common early-game buys.
    private static final Set<String> POTION_TIER_CHEAP = Set.of(
            "NIGHT_VISION", "INVISIBILITY", "WATER_BREATHING", "SLOWNESS", "WEAKNESS", "SLOW_FALLING", "LEAPING");

    // Genuinely strong combat/utility effects -- still meant to be affordable mid-game, just
    // priced a step above the cheap tier.
    private static final Set<String> POTION_TIER_PREMIUM = Set.of(
            "STRENGTH", "REGENERATION", "HEALING", "TURTLE_MASTER", "LUCK");

    // Everything else (Swiftness, Fire Resistance, Poison, Harming, and any effect a future
    // Minecraft version adds that isn't in either list above) lands here by default.

    private static String potionTier(String rootEffectName) {
        if (POTION_TIER_CHEAP.contains(rootEffectName)) return "CHEAP";
        if (POTION_TIER_PREMIUM.contains(rootEffectName)) return "PREMIUM";
        return "MODERATE";
    }

    /**
     * Auto-populates a dedicated "Potions" tab with every real PotionType the server's API
     * knows about -- Potion, Splash Potion, and Lingering Potion for each one, plus the
     * Bottle o' Enchanting -- by iterating {@link PotionType#values()} live rather than
     * hand-listing effect names, so this keeps working (and picks up any new brewable
     * effect) on a newer Minecraft version without code changes, same philosophy as
     * generateSpawnEggs()/generateEnchantBooks() above.
     *
     * Deliberately priced low: shop.potions.cheap/moderate/premium-base-price default to
     * $15/$30/$55 for a plain drinkable Potion, since these are meant to be affordable
     * beginner-to-mid-game buys, nowhere near the Enchantments tab's scale. Splash and
     * Lingering variants cost more on top of that (splash-multiplier/lingering-multiplier,
     * default 1.6x/2.5x, roughly mirroring the extra gunpowder/dragon's breath a player
     * would otherwise need to craft them), and the "II"/extended-duration variants cost a
     * bit more too (strong-multiplier/long-multiplier, default 1.5x/1.25x). Buy-only, same
     * reasoning as Spawn Eggs/Enchantments: no clean, non-exploitable way to price a sell-back
     * on something a player could otherwise brew for the cost of raw ingredients.
     */
    private void generatePotions() {
        if (!plugin.getConfig().getBoolean("shop.potions.enabled", true)) return;

        double cheapBase = plugin.getConfig().getDouble("shop.potions.cheap-base-price", 15.0);
        double moderateBase = plugin.getConfig().getDouble("shop.potions.moderate-base-price", 30.0);
        double premiumBase = plugin.getConfig().getDouble("shop.potions.premium-base-price", 55.0);
        double splashMultiplier = plugin.getConfig().getDouble("shop.potions.splash-multiplier", 1.6);
        double lingeringMultiplier = plugin.getConfig().getDouble("shop.potions.lingering-multiplier", 2.5);
        double longMultiplier = plugin.getConfig().getDouble("shop.potions.long-multiplier", 1.25);
        double strongMultiplier = plugin.getConfig().getDouble("shop.potions.strong-multiplier", 1.5);
        double bottleOfEnchantingPrice = plugin.getConfig().getDouble("shop.potions.bottle-of-enchanting-price", 8.0);

        Set<String> excluded = new HashSet<>();
        for (String name : plugin.getConfig().getStringList("shop.potions.excluded")) {
            excluded.add(name.trim().toUpperCase(Locale.ROOT));
        }

        int added = 0;
        int effectCount = 0;
        for (PotionType type : PotionType.values()) {
            String name = type.name();
            if (POTION_NO_EFFECT.contains(name) || excluded.contains(name)) continue;
            try {
                boolean strong = name.startsWith("STRONG_");
                boolean extended = name.startsWith("LONG_");
                String rootEffectName = strong ? name.substring("STRONG_".length())
                        : extended ? name.substring("LONG_".length()) : name;

                double base = switch (potionTier(rootEffectName)) {
                    case "CHEAP" -> cheapBase;
                    case "PREMIUM" -> premiumBase;
                    default -> moderateBase;
                };
                double strengthMultiplier = strong ? strongMultiplier : extended ? longMultiplier : 1.0;
                double basePrice = round2(base * strengthMultiplier);

                addPotionEntry(Material.POTION, type, basePrice, "Potion");
                addPotionEntry(Material.SPLASH_POTION, type, round2(basePrice * splashMultiplier), "Splash Potion");
                addPotionEntry(Material.LINGERING_POTION, type, round2(basePrice * lingeringMultiplier), "Lingering Potion");
                added += 3;
                effectCount++;
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "NexusEconomy: couldn't build a shop entry for potion type "
                        + type + ", skipping it.", e);
            }
        }

        if (!excluded.contains("EXPERIENCE_BOTTLE")) {
            ItemStack xpBottle = new ItemStack(Material.EXPERIENCE_BOTTLE);
            ItemMeta meta = xpBottle.getItemMeta();
            meta.setDisplayName(ChatColor.GREEN + "Bottle o' Enchanting");
            xpBottle.setItemMeta(meta);
            potionItems.add(new CustomShopEntry(xpBottle, round2(bottleOfEnchantingPrice)));
            added++;
        }

        plugin.getLogger().info("NexusEconomy: auto-added " + added + " potion item(s) covering "
                + effectCount + " brewable effect(s) (Potion/Splash/Lingering each), plus the Bottle o' Enchanting.");
    }

    private void addPotionEntry(Material material, PotionType type, double price, String formLabel) {
        ItemStack item = new ItemStack(material);
        ItemMeta rawMeta = item.getItemMeta();
        if (!(rawMeta instanceof PotionMeta meta)) return;
        meta.setBasePotionType(type);
        meta.setDisplayName(ChatColor.AQUA + formLabel + ": " + ChatColor.WHITE + prettyPotionName(type));
        item.setItemMeta(meta);
        potionItems.add(new CustomShopEntry(item, price));
    }

    /** e.g. STRONG_HEALING -> "Healing II", LONG_NIGHT_VISION -> "Night Vision (Extended)". */
    private String prettyPotionName(PotionType type) {
        String name = type.name();
        boolean strong = name.startsWith("STRONG_");
        boolean extended = name.startsWith("LONG_");
        String root = strong ? name.substring("STRONG_".length()) : extended ? name.substring("LONG_".length()) : name;

        StringBuilder sb = new StringBuilder();
        for (String word : root.toLowerCase(Locale.ROOT).split("_")) {
            if (word.isEmpty()) continue;
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1)).append(' ');
        }
        String pretty = sb.toString().trim();
        if (strong) pretty += " II";
        if (extended) pretty += " (Extended)";
        return pretty;
    }

    public List<CustomShopEntry> potionItems() {
        return potionItems;
    }

    private String prettyEnchantName(Enchantment enchantment) {
        String raw = enchantment.getKey().getKey().toLowerCase().replace('_', ' ');
        StringBuilder sb = new StringBuilder();
        for (String word : raw.split(" ")) {
            if (!word.isEmpty()) sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1)).append(' ');
        }
        return sb.toString().trim();
    }

    private String toRoman(int number) {
        String[] romans = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return number > 0 && number < romans.length ? romans[number] : String.valueOf(number);
    }

    // --- Sell index (Material -> the ShopItem that buys it back), used by /sell hand and /sell all
    //     so those commands don't need to search every category on every use ---

    private void buildSellIndex() {
        sellIndex.clear();
        for (ShopCategory category : categories.values()) {
            for (ShopItem item : category.items()) {
                if (item.sellable()) sellIndex.put(item.material(), item);
            }
        }
    }

    /** The ShopItem the shop will buy this material back as, or null if nothing in the shop buys it. */
    public ShopItem sellableItemFor(Material material) {
        return sellIndex.get(material);
    }

    public record SellAllResult(int distinctMaterials, int totalItems, double totalPayout) {
    }

    /** Sells everything in the player's inventory that the shop buys back, one material at a time. */
    public SellAllResult sellAll(Player player) {
        Map<Material, Integer> counts = new LinkedHashMap<>();
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack == null || stack.getType().isAir()) continue;
            if (!sellIndex.containsKey(stack.getType())) continue;
            counts.merge(stack.getType(), stack.getAmount(), Integer::sum);
        }

        int distinctMaterials = 0;
        int totalItems = 0;
        double totalPayout = 0;
        for (Map.Entry<Material, Integer> entry : counts.entrySet()) {
            ShopItem item = sellIndex.get(entry.getKey());
            int sold = sell(player, item, entry.getValue());
            if (sold > 0) {
                distinctMaterials++;
                totalItems += sold;
                totalPayout += round2(sold * item.sell());
            }
        }
        return new SellAllResult(distinctMaterials, totalItems, round2(totalPayout));
    }

    // --- GUI building ---

    public Inventory buildCategoryMenu() {
        ensureAccessoryItemsLoaded();
        boolean showNexusEnchants = !nexusEnchantItems.isEmpty();
        boolean showAccessories = !accessoryItems.isEmpty();
        boolean showAccessorySlots = accessoriesBridge.isConnected();
        int totalButtons = categories.size() + 7 + (showNexusEnchants ? 1 : 0) + (showAccessories ? 1 : 0) + (showAccessorySlots ? 1 : 0);
        // +1 Search, +1 Custom, +1 Enchantments, +1 Potions, +3 Vitals (Hearts/Hunger/Oxygen), +1 NexusEnchants/+1 Accessories/+1 Accessory Slots if connected
        int size = Math.min(54, Math.max(9, ((totalButtons + 8) / 9) * 9));
        ShopMenuHolder holder = new ShopMenuHolder(ShopMenuHolder.Type.CATEGORY_LIST, null, 0);
        Inventory inv = Bukkit.createInventory(holder, size, ChatColor.DARK_GREEN + "" + ChatColor.BOLD + "Shop Categories");
        holder.setInventory(inv);

        // Pinned to slot 0 -- literally the top-left of the menu, so it's the first thing anyone
        // sees -- rather than added via addItem() like everything else here, which would let it
        // land anywhere depending on how many categories/tabs came before it.
        ItemStack searchIcon = new ItemStack(Material.COMPASS);
        ItemMeta searchMeta = searchIcon.getItemMeta();
        searchMeta.setDisplayName(ChatColor.YELLOW + "" + ChatColor.BOLD + "Search");
        searchMeta.setLore(List.of(ChatColor.GRAY + "Look up an item by name.",
                ChatColor.DARK_GRAY + "Type it in the anvil that opens, then click the result."));
        searchMeta.getPersistentDataContainer().set(categoryKeyTag, PersistentDataType.STRING, SEARCH_KEY);
        searchIcon.setItemMeta(searchMeta);
        inv.setItem(0, searchIcon);

        for (ShopCategory category : categories.values()) {
            ItemStack icon = new ItemStack(category.icon());
            ItemMeta meta = icon.getItemMeta();
            meta.setDisplayName(ChatColor.GREEN + category.displayName());
            meta.setLore(List.of(ChatColor.GRAY + "" + category.items().size() + " items"));
            meta.getPersistentDataContainer().set(categoryKeyTag, PersistentDataType.STRING, category.key());
            icon.setItemMeta(meta);
            inv.addItem(icon);
        }

        ItemStack customIcon = new ItemStack(Material.NETHER_STAR);
        ItemMeta customMeta = customIcon.getItemMeta();
        customMeta.setDisplayName(ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "Custom");
        customMeta.setLore(List.of(ChatColor.GRAY + "" + customItems.size() + " items", ChatColor.DARK_GRAY + "Server-added specials"));
        customMeta.getPersistentDataContainer().set(categoryKeyTag, PersistentDataType.STRING, CUSTOM_KEY);
        customIcon.setItemMeta(customMeta);
        inv.addItem(customIcon);

        ItemStack enchantIcon = new ItemStack(Material.ENCHANTED_BOOK);
        ItemMeta enchantMeta = enchantIcon.getItemMeta();
        enchantMeta.setDisplayName(ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "Enchantments");
        enchantMeta.setLore(List.of(ChatColor.GRAY + "" + enchantItems.size() + " items",
                ChatColor.DARK_GRAY + "End-game gear. Priced accordingly."));
        enchantMeta.getPersistentDataContainer().set(categoryKeyTag, PersistentDataType.STRING, ENCHANT_KEY);
        enchantIcon.setItemMeta(enchantMeta);
        inv.addItem(enchantIcon);

        ItemStack potionsIcon = new ItemStack(Material.BREWING_STAND);
        ItemMeta potionsMeta = potionsIcon.getItemMeta();
        potionsMeta.setDisplayName(ChatColor.AQUA + "" + ChatColor.BOLD + "Potions");
        potionsMeta.setLore(List.of(ChatColor.GRAY + "" + potionItems.size() + " items",
                ChatColor.DARK_GRAY + "Every brewable potion, fairly priced for early/mid game."));
        potionsMeta.getPersistentDataContainer().set(categoryKeyTag, PersistentDataType.STRING, POTIONS_KEY);
        potionsIcon.setItemMeta(potionsMeta);
        inv.addItem(potionsIcon);

        // The NexusEnchants integration tab -- only shown once that plugin's bridge is
        // actually connected and has produced at least one entry; a server without
        // NexusEnchants installed (or a fresh restart racing its enable) just never sees
        // this button, rather than showing a permanently-empty tab.
        if (showNexusEnchants) {
            ItemStack nexusEnchantIcon = new ItemStack(Material.KNOWLEDGE_BOOK);
            ItemMeta nexusEnchantMeta = nexusEnchantIcon.getItemMeta();
            nexusEnchantMeta.setDisplayName(ChatColor.DARK_AQUA + "" + ChatColor.BOLD + "NexusEnchants");
            nexusEnchantMeta.setLore(List.of(ChatColor.GRAY + "" + nexusEnchantItems.size() + " items",
                    ChatColor.DARK_GRAY + "Custom enchants from NexusEnchants.",
                    ChatColor.DARK_GRAY + "Every single one is priced differently."));
            nexusEnchantMeta.getPersistentDataContainer().set(categoryKeyTag, PersistentDataType.STRING, NEXUS_ENCHANT_KEY);
            nexusEnchantIcon.setItemMeta(nexusEnchantMeta);
            inv.addItem(nexusEnchantIcon);
        }

        // The NexusAccessories integration -- an "Accessories" tab (buy Rings/Belts/Capes/
        // Charms) and a separate "Accessory Slots" button (buy extra slot capacity per type,
        // see AccessorySlotsMenu). Same connected-or-omit behavior as NexusEnchants above.
        if (showAccessories) {
            ItemStack accessoriesIcon = new ItemStack(Material.RABBIT_FOOT);
            ItemMeta accessoriesMeta = accessoriesIcon.getItemMeta();
            accessoriesMeta.setDisplayName(ChatColor.GREEN + "" + ChatColor.BOLD + "Accessories");
            accessoriesMeta.setLore(List.of(ChatColor.GRAY + "" + accessoryItems.size() + " items",
                    ChatColor.DARK_GRAY + "Rings, belts, capes, and charms from NexusAccessories."));
            accessoriesMeta.getPersistentDataContainer().set(categoryKeyTag, PersistentDataType.STRING, ACCESSORIES_KEY);
            accessoriesIcon.setItemMeta(accessoriesMeta);
            inv.addItem(accessoriesIcon);
        }
        if (showAccessorySlots) {
            ItemStack slotsIcon = new ItemStack(Material.LEATHER_CHESTPLATE);
            ItemMeta slotsMeta = slotsIcon.getItemMeta();
            slotsMeta.setDisplayName(ChatColor.GREEN + "" + ChatColor.BOLD + "Accessory Slots");
            slotsMeta.setLore(List.of(ChatColor.GRAY + "Buy extra Ring/Belt/Cape/Charm slots",
                    ChatColor.DARK_GRAY + "so you can wear more than one of each."));
            slotsMeta.getPersistentDataContainer().set(categoryKeyTag, PersistentDataType.STRING, ACCESSORY_SLOTS_KEY);
            slotsIcon.setItemMeta(slotsMeta);
            inv.addItem(slotsIcon);
        }

        // Vitals: three pinned buttons (not real ShopCategory entries -- ShopListener
        // recognizes these three key strings and opens VitalsMenu instead of a normal
        // item page) for the permanent Hearts/Hunger/Oxygen upgrade tracks.
        ItemStack heartsIcon = new ItemStack(Material.GOLDEN_APPLE);
        ItemMeta heartsMeta = heartsIcon.getItemMeta();
        heartsMeta.setDisplayName(ChatColor.RED + "" + ChatColor.BOLD + "Hearts");
        heartsMeta.setLore(List.of(ChatColor.GRAY + "Permanently buy more max health.",
                ChatColor.DARK_GRAY + "A real, stacking attribute boost."));
        heartsMeta.getPersistentDataContainer().set(categoryKeyTag, PersistentDataType.STRING, HEARTS_KEY);
        heartsIcon.setItemMeta(heartsMeta);
        inv.addItem(heartsIcon);

        ItemStack hungerIcon = new ItemStack(Material.COOKED_BEEF);
        ItemMeta hungerMeta = hungerIcon.getItemMeta();
        hungerMeta.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + "Hunger");
        hungerMeta.setLore(List.of(ChatColor.GRAY + "Buy a slower-draining hunger bar.",
                ChatColor.DARK_GRAY + "Your food lasts longer, level by level."));
        hungerMeta.getPersistentDataContainer().set(categoryKeyTag, PersistentDataType.STRING, HUNGER_KEY);
        hungerIcon.setItemMeta(hungerMeta);
        inv.addItem(hungerIcon);

        ItemStack oxygenIcon = new ItemStack(Material.TURTLE_HELMET);
        ItemMeta oxygenMeta = oxygenIcon.getItemMeta();
        oxygenMeta.setDisplayName(ChatColor.AQUA + "" + ChatColor.BOLD + "Oxygen");
        oxygenMeta.setLore(List.of(ChatColor.GRAY + "Buy a slower-draining air bar.",
                ChatColor.DARK_GRAY + "Stay underwater longer, level by level."));
        oxygenMeta.getPersistentDataContainer().set(categoryKeyTag, PersistentDataType.STRING, OXYGEN_KEY);
        oxygenIcon.setItemMeta(oxygenMeta);
        inv.addItem(oxygenIcon);

        return inv;
    }

    public Inventory buildCategoryPage(String categoryKey, int page) {
        return buildCategoryPage(categoryKey, page, ShopMenuHolder.Mode.BUY, 1);
    }

    public Inventory buildCategoryPage(String categoryKey, int page, ShopMenuHolder.Mode mode) {
        return buildCategoryPage(categoryKey, page, mode, 1);
    }

    public Inventory buildCategoryPage(String categoryKey, int page, ShopMenuHolder.Mode mode, int quantity) {
        ShopCategory category = categories.get(categoryKey);
        if (category == null) return null;

        List<ShopItem> items = category.items();
        int totalPages = Math.max(1, (items.size() + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE);
        page = Math.max(0, Math.min(page, totalPages - 1));
        if (mode == null) mode = ShopMenuHolder.Mode.BUY;
        if (quantity <= 0) quantity = 1;

        ShopMenuHolder holder = new ShopMenuHolder(ShopMenuHolder.Type.ITEM_PAGE, categoryKey, page, mode, quantity);
        Inventory inv = Bukkit.createInventory(holder, 54, ChatColor.DARK_GREEN + "" + ChatColor.BOLD + category.displayName()
                + ChatColor.GRAY + " (" + (page + 1) + "/" + totalPages + ")");
        holder.setInventory(inv);

        int start = page * ITEMS_PER_PAGE;
        int end = Math.min(items.size(), start + ITEMS_PER_PAGE);
        for (int i = start; i < end; i++) {
            ShopItem shopItem = items.get(i);
            try {
                ItemStack display = new ItemStack(shopItem.material());
                ItemMeta meta = display.getItemMeta();
                meta.setDisplayName(ChatColor.WHITE + prettyName(shopItem.material()));

                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.GREEN + "Buy x" + quantity + ": $" + String.format("%.2f", shopItem.buy() * quantity)
                        + ChatColor.GRAY + (mode == ShopMenuHolder.Mode.BUY ? " (click)" : ""));
                if (shopItem.sellable()) {
                    lore.add(ChatColor.GOLD + "Sell x" + quantity + ": $" + String.format("%.2f", shopItem.sell() * quantity)
                            + ChatColor.GRAY + (mode == ShopMenuHolder.Mode.SELL ? " (click)" : " (right-click)"));
                } else {
                    lore.add(ChatColor.DARK_GRAY + "Not sellable");
                }
                lore.add(ChatColor.DARK_GRAY + "Shift-click for a full stack (64)");
                meta.setLore(lore);
                display.setItemMeta(meta);
                inv.setItem(i - start, display);
            } catch (Exception e) {
                // One bad material shouldn't take the whole page down -- skip it and log
                // instead, so the tab still opens for everyone even if a future MC version
                // adds a material that doesn't build cleanly as an ItemStack.
                plugin.getLogger().log(Level.WARNING, "NexusEconomy: couldn't build shop tile for "
                        + shopItem.material() + " in \"" + categoryKey + "\", skipping it.", e);
            }
        }

        if (page > 0) inv.setItem(45, navItem(Material.ARROW, "Previous Page"));
        inv.setItem(49, navItem(Material.BARRIER, "Back to Categories"));
        if (page < totalPages - 1) inv.setItem(52, navItem(Material.ARROW, "Next Page"));
        inv.setItem(51, quantityToggleItem(quantity));
        inv.setItem(53, modeToggleItem(mode));

        return inv;
    }

    /**
     * Every ShopItem across every real (non-pinned) category whose name matches this query,
     * sorted alphabetically by display name. Matches against both the pretty display name
     * ("Redstone Lamp") and the raw material name ("REDSTONE_LAMP"), so either "redstone lamp"
     * or "redstone_lamp" finds it. Deliberately doesn't reach into the buy-only pinned tabs
     * (Custom/Enchantments/NexusEnchants/Accessories/Potions) -- those are one-off/unique
     * items rather than the plain browse-by-material catalog this is meant to search.
     */
    public List<ShopItem> searchAllItems(String query) {
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) return List.of();
        String normalizedUnderscored = normalized.replace(' ', '_');

        List<ShopItem> matches = new ArrayList<>();
        for (ShopCategory category : categories.values()) {
            for (ShopItem item : category.items()) {
                String pretty = prettyName(item.material()).toLowerCase(Locale.ROOT);
                String raw = item.material().name().toLowerCase(Locale.ROOT);
                if (pretty.contains(normalized) || raw.contains(normalizedUnderscored)) {
                    matches.add(item);
                }
            }
        }
        matches.sort((a, b) -> prettyName(a.material()).compareTo(prettyName(b.material())));
        return matches;
    }

    /** Same layout/behavior as buildCategoryPage (buy/sell, quantity toggle, pagination) -- just backed by a search query instead of one fixed category. */
    public Inventory buildSearchResultsPage(String query, int page, ShopMenuHolder.Mode mode, int quantity) {
        List<ShopItem> items = searchAllItems(query);
        int totalPages = Math.max(1, (items.size() + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE);
        page = Math.max(0, Math.min(page, totalPages - 1));
        if (mode == null) mode = ShopMenuHolder.Mode.BUY;
        if (quantity <= 0) quantity = 1;

        ShopMenuHolder holder = new ShopMenuHolder(ShopMenuHolder.Type.SEARCH_RESULTS, query, page, mode, quantity);
        Inventory inv = Bukkit.createInventory(holder, 54, ChatColor.DARK_AQUA + "" + ChatColor.BOLD + "Search: "
                + ChatColor.WHITE + query + ChatColor.GRAY + " (" + items.size() + " found, page " + (page + 1) + "/" + totalPages + ")");
        holder.setInventory(inv);

        if (items.isEmpty()) {
            ItemStack none = new ItemStack(Material.BARRIER);
            ItemMeta noneMeta = none.getItemMeta();
            noneMeta.setDisplayName(ChatColor.RED + "No items found for \"" + query + "\"");
            noneMeta.setLore(List.of(ChatColor.GRAY + "Try a shorter or different search term."));
            none.setItemMeta(noneMeta);
            inv.setItem(22, none);
        }

        int start = page * ITEMS_PER_PAGE;
        int end = Math.min(items.size(), start + ITEMS_PER_PAGE);
        for (int i = start; i < end; i++) {
            ShopItem shopItem = items.get(i);
            try {
                ItemStack display = new ItemStack(shopItem.material());
                ItemMeta meta = display.getItemMeta();
                meta.setDisplayName(ChatColor.WHITE + prettyName(shopItem.material()));

                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.GREEN + "Buy x" + quantity + ": $" + String.format("%.2f", shopItem.buy() * quantity)
                        + ChatColor.GRAY + (mode == ShopMenuHolder.Mode.BUY ? " (click)" : ""));
                if (shopItem.sellable()) {
                    lore.add(ChatColor.GOLD + "Sell x" + quantity + ": $" + String.format("%.2f", shopItem.sell() * quantity)
                            + ChatColor.GRAY + (mode == ShopMenuHolder.Mode.SELL ? " (click)" : " (right-click)"));
                } else {
                    lore.add(ChatColor.DARK_GRAY + "Not sellable");
                }
                lore.add(ChatColor.DARK_GRAY + "Shift-click for a full stack (64)");
                meta.setLore(lore);
                display.setItemMeta(meta);
                inv.setItem(i - start, display);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "NexusEconomy: couldn't build search-result tile for "
                        + shopItem.material() + ", skipping it.", e);
            }
        }

        if (page > 0) inv.setItem(45, navItem(Material.ARROW, "Previous Page"));
        inv.setItem(49, navItem(Material.BARRIER, "Back to Categories"));
        if (page < totalPages - 1) inv.setItem(52, navItem(Material.ARROW, "Next Page"));
        inv.setItem(51, quantityToggleItem(quantity));
        inv.setItem(53, modeToggleItem(mode));

        return inv;
    }

    /**
     * The buy/sell toggle in the bottom-right corner. A plain click on any
     * item in the grid always performs whatever mode this is currently
     * set to -- meant for controller/Xbox players, who only have one click
     * and can't right-click to sell. Right-click on an item still always
     * sells directly, unaffected by this, so mouse players keep that
     * shortcut too.
     */
    private ItemStack modeToggleItem(ShopMenuHolder.Mode mode) {
        boolean buying = mode == ShopMenuHolder.Mode.BUY;
        ItemStack item = new ItemStack(buying ? Material.LIME_DYE : Material.RED_DYE);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName((buying ? ChatColor.GREEN : ChatColor.GOLD) + "" + ChatColor.BOLD
                + "Mode: " + (buying ? "BUYING" : "SELLING"));
        meta.setLore(List.of(
                ChatColor.GRAY + "Click an item to " + (buying ? "buy" : "sell") + " it.",
                ChatColor.GRAY + "Click here to switch to " + (buying ? "selling" : "buying") + "."
        ));
        item.setItemMeta(meta);
        return item;
    }

    /**
     * The quantity toggle next to the buy/sell toggle -- cycles 1 -> 16 -> 32 -> 64 -> 1. A plain
     * click on any item in the grid transacts this many; shift-click is unaffected and always
     * transacts a full stack (64), same shortcut as before this existed.
     */
    private ItemStack quantityToggleItem(int quantity) {
        ItemStack item = new ItemStack(Material.PAPER, Math.min(64, Math.max(1, quantity)));
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.AQUA + "" + ChatColor.BOLD + "Quantity: x" + quantity);
        meta.setLore(List.of(
                ChatColor.GRAY + "A plain click buys/sells " + quantity + " at a time.",
                ChatColor.GRAY + "Click here to cycle to x" + nextQuantity(quantity) + "."
        ));
        item.setItemMeta(meta);
        return item;
    }

    public Inventory buildCustomPage(int page) {
        int totalPages = Math.max(1, (customItems.size() + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE);
        page = Math.max(0, Math.min(page, totalPages - 1));

        ShopMenuHolder holder = new ShopMenuHolder(ShopMenuHolder.Type.CUSTOM_PAGE, CUSTOM_KEY, page);
        Inventory inv = Bukkit.createInventory(holder, 54, ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "Custom"
                + ChatColor.GRAY + " (" + (page + 1) + "/" + totalPages + ")");
        holder.setInventory(inv);

        int start = page * ITEMS_PER_PAGE;
        int end = Math.min(customItems.size(), start + ITEMS_PER_PAGE);
        for (int i = start; i < end; i++) {
            CustomShopEntry entry = customItems.get(i);
            try {
                ItemStack display = entry.item().clone();
                ItemMeta meta = display.getItemMeta();

                List<String> lore = new ArrayList<>(meta.hasLore() && meta.getLore() != null ? meta.getLore() : List.of());
                lore.add("");
                lore.add(ChatColor.GREEN + "Buy: $" + String.format("%.2f", entry.buy()) + ChatColor.GRAY + " (left-click)");
                lore.add(ChatColor.DARK_GRAY + "Not sellable back to the shop");
                meta.setLore(lore);
                display.setItemMeta(meta);
                inv.setItem(i - start, display);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "NexusEconomy: couldn't build Custom tab tile #" + i + ", skipping it.", e);
            }
        }

        if (page > 0) inv.setItem(45, navItem(Material.ARROW, "Previous Page"));
        inv.setItem(49, navItem(Material.BARRIER, "Back to Categories"));
        if (page < totalPages - 1) inv.setItem(53, navItem(Material.ARROW, "Next Page"));

        return inv;
    }

    public Inventory buildEnchantPage(int page) {
        int totalPages = Math.max(1, (enchantItems.size() + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE);
        page = Math.max(0, Math.min(page, totalPages - 1));

        ShopMenuHolder holder = new ShopMenuHolder(ShopMenuHolder.Type.ENCHANT_PAGE, ENCHANT_KEY, page);
        Inventory inv = Bukkit.createInventory(holder, 54, ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "Enchantments"
                + ChatColor.GRAY + " (" + (page + 1) + "/" + totalPages + ")");
        holder.setInventory(inv);

        int start = page * ITEMS_PER_PAGE;
        int end = Math.min(enchantItems.size(), start + ITEMS_PER_PAGE);
        for (int i = start; i < end; i++) {
            CustomShopEntry entry = enchantItems.get(i);
            try {
                ItemStack display = entry.item().clone();
                ItemMeta meta = display.getItemMeta();

                List<String> lore = new ArrayList<>(meta.hasLore() && meta.getLore() != null ? meta.getLore() : List.of());
                lore.add("");
                lore.add(ChatColor.GOLD + "" + ChatColor.BOLD + "Buy: $" + String.format("%,.2f", entry.buy()) + ChatColor.GRAY + " (left-click)");
                lore.add(ChatColor.DARK_GRAY + "Not sellable back to the shop");
                meta.setLore(lore);
                display.setItemMeta(meta);
                inv.setItem(i - start, display);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "NexusEconomy: couldn't build Enchantments tab tile #" + i + ", skipping it.", e);
            }
        }

        if (page > 0) inv.setItem(45, navItem(Material.ARROW, "Previous Page"));
        inv.setItem(49, navItem(Material.BARRIER, "Back to Categories"));
        if (page < totalPages - 1) inv.setItem(53, navItem(Material.ARROW, "Next Page"));

        return inv;
    }

    /** Same shape as buildEnchantPage() above, backed by potionItems instead. */
    public Inventory buildPotionsPage(int page) {
        int totalPages = Math.max(1, (potionItems.size() + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE);
        page = Math.max(0, Math.min(page, totalPages - 1));

        ShopMenuHolder holder = new ShopMenuHolder(ShopMenuHolder.Type.POTION_PAGE, POTIONS_KEY, page);
        Inventory inv = Bukkit.createInventory(holder, 54, ChatColor.AQUA + "" + ChatColor.BOLD + "Potions"
                + ChatColor.GRAY + " (" + (page + 1) + "/" + totalPages + ")");
        holder.setInventory(inv);

        int start = page * ITEMS_PER_PAGE;
        int end = Math.min(potionItems.size(), start + ITEMS_PER_PAGE);
        for (int i = start; i < end; i++) {
            CustomShopEntry entry = potionItems.get(i);
            try {
                ItemStack display = entry.item().clone();
                ItemMeta meta = display.getItemMeta();

                List<String> lore = new ArrayList<>(meta.hasLore() && meta.getLore() != null ? meta.getLore() : List.of());
                lore.add("");
                lore.add(ChatColor.GREEN + "Buy: $" + String.format("%.2f", entry.buy()) + ChatColor.GRAY + " (left-click)");
                lore.add(ChatColor.DARK_GRAY + "Not sellable back to the shop");
                meta.setLore(lore);
                display.setItemMeta(meta);
                inv.setItem(i - start, display);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "NexusEconomy: couldn't build Potions tab tile #" + i + ", skipping it.", e);
            }
        }

        if (page > 0) inv.setItem(45, navItem(Material.ARROW, "Previous Page"));
        inv.setItem(49, navItem(Material.BARRIER, "Back to Categories"));
        if (page < totalPages - 1) inv.setItem(53, navItem(Material.ARROW, "Next Page"));

        return inv;
    }

    /** Same shape as buildEnchantPage() above, backed by nexusEnchantItems instead. */
    public Inventory buildNexusEnchantPage(int page) {
        int totalPages = Math.max(1, (nexusEnchantItems.size() + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE);
        page = Math.max(0, Math.min(page, totalPages - 1));

        ShopMenuHolder holder = new ShopMenuHolder(ShopMenuHolder.Type.NEXUS_ENCHANT_PAGE, NEXUS_ENCHANT_KEY, page);
        Inventory inv = Bukkit.createInventory(holder, 54, ChatColor.DARK_AQUA + "" + ChatColor.BOLD + "NexusEnchants"
                + ChatColor.GRAY + " (" + (page + 1) + "/" + totalPages + ")");
        holder.setInventory(inv);

        int start = page * ITEMS_PER_PAGE;
        int end = Math.min(nexusEnchantItems.size(), start + ITEMS_PER_PAGE);
        for (int i = start; i < end; i++) {
            CustomShopEntry entry = nexusEnchantItems.get(i);
            try {
                ItemStack display = entry.item().clone();
                ItemMeta meta = display.getItemMeta();

                List<String> lore = new ArrayList<>(meta.hasLore() && meta.getLore() != null ? meta.getLore() : List.of());
                lore.add("");
                lore.add(ChatColor.DARK_AQUA + "" + ChatColor.BOLD + "Buy: $" + String.format("%,.2f", entry.buy()) + ChatColor.GRAY + " (left-click)");
                lore.add(ChatColor.DARK_GRAY + "Not sellable back to the shop");
                meta.setLore(lore);
                display.setItemMeta(meta);
                inv.setItem(i - start, display);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "NexusEconomy: couldn't build NexusEnchants tab tile #" + i + ", skipping it.", e);
            }
        }

        if (page > 0) inv.setItem(45, navItem(Material.ARROW, "Previous Page"));
        inv.setItem(49, navItem(Material.BARRIER, "Back to Categories"));
        if (page < totalPages - 1) inv.setItem(53, navItem(Material.ARROW, "Next Page"));

        return inv;
    }

    /** Same shape as buildEnchantPage()/buildNexusEnchantPage() above, backed by accessoryItems instead. */
    public Inventory buildAccessoryPage(int page) {
        ensureAccessoryItemsLoaded();
        int totalPages = Math.max(1, (accessoryItems.size() + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE);
        page = Math.max(0, Math.min(page, totalPages - 1));

        ShopMenuHolder holder = new ShopMenuHolder(ShopMenuHolder.Type.ACCESSORY_PAGE, ACCESSORIES_KEY, page);
        Inventory inv = Bukkit.createInventory(holder, 54, ChatColor.GREEN + "" + ChatColor.BOLD + "Accessories"
                + ChatColor.GRAY + " (" + (page + 1) + "/" + totalPages + ")");
        holder.setInventory(inv);

        int start = page * ITEMS_PER_PAGE;
        int end = Math.min(accessoryItems.size(), start + ITEMS_PER_PAGE);
        for (int i = start; i < end; i++) {
            CustomShopEntry entry = accessoryItems.get(i);
            try {
                ItemStack display = entry.item().clone();
                ItemMeta meta = display.getItemMeta();

                List<String> lore = new ArrayList<>(meta.hasLore() && meta.getLore() != null ? meta.getLore() : List.of());
                lore.add("");
                lore.add(ChatColor.GREEN + "" + ChatColor.BOLD + "Buy: $" + String.format("%,.2f", entry.buy()) + ChatColor.GRAY + " (left-click)");
                lore.add(ChatColor.DARK_GRAY + "Not sellable back to the shop");
                meta.setLore(lore);
                display.setItemMeta(meta);
                inv.setItem(i - start, display);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "NexusEconomy: couldn't build Accessories tab tile #" + i + ", skipping it.", e);
            }
        }

        if (page > 0) inv.setItem(45, navItem(Material.ARROW, "Previous Page"));
        inv.setItem(49, navItem(Material.BARRIER, "Back to Categories"));
        if (page < totalPages - 1) inv.setItem(53, navItem(Material.ARROW, "Next Page"));

        return inv;
    }

    private ItemStack navItem(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.YELLOW + name);
        item.setItemMeta(meta);
        return item;
    }

    private String prettyName(Material material) {
        String raw = material.name().toLowerCase().replace('_', ' ');
        StringBuilder sb = new StringBuilder();
        for (String word : raw.split(" ")) {
            if (!word.isEmpty()) sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1)).append(' ');
        }
        return sb.toString().trim();
    }

    // --- Transactions ---

    public boolean buy(Player player, ShopItem item, int quantity) {
        double cost = round2(item.buy() * quantity);
        if (!accounts.has(player.getUniqueId(), cost)) return false;
        if (!hasRoomFor(player, item.material())) return false;

        accounts.withdraw(player.getUniqueId(), cost);
        revenueRouter.creditPurchase(cost);
        player.getInventory().addItem(new ItemStack(item.material(), quantity));
        return true;
    }

    public boolean buyCustom(Player player, CustomShopEntry entry, int quantity) {
        double cost = round2(entry.buy() * quantity);
        if (!accounts.has(player.getUniqueId(), cost)) return false;

        ItemStack toGive = entry.item().clone();
        toGive.setAmount(quantity);
        var leftover = player.getInventory().addItem(toGive);
        if (!leftover.isEmpty()) {
            player.getInventory().removeItem(toGive); // undo the partial add -- all or nothing
            return false;
        }

        accounts.withdraw(player.getUniqueId(), cost);
        revenueRouter.creditPurchase(cost);
        return true;
    }

    public int sell(Player player, ShopItem item, int quantity) {
        if (!item.sellable()) return 0;
        int available = countInInventory(player, item.material());
        int toSell = Math.min(available, quantity);
        if (toSell <= 0) return 0;

        removeFromInventory(player, item.material(), toSell);
        double payout = round2(item.sell() * toSell);
        accounts.createAccount(player.getUniqueId());
        accounts.deposit(player.getUniqueId(), payout);
        return toSell;
    }

    private boolean hasRoomFor(Player player, Material material) {
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack == null || stack.getType().isAir()) return true;
            if (stack.getType() == material && stack.getAmount() < stack.getMaxStackSize()) return true;
        }
        return false;
    }

    private int countInInventory(Player player, Material material) {
        int count = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack != null && stack.getType() == material) count += stack.getAmount();
        }
        return count;
    }

    private void removeFromInventory(Player player, Material material, int amount) {
        ItemStack[] contents = player.getInventory().getStorageContents();
        int remaining = amount;
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack stack = contents[i];
            if (stack == null || stack.getType() != material) continue;
            int take = Math.min(remaining, stack.getAmount());
            stack.setAmount(stack.getAmount() - take);
            if (stack.getAmount() <= 0) contents[i] = null;
            remaining -= take;
        }
        player.getInventory().setStorageContents(contents);
    }
}
