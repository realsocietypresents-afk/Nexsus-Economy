package com.nexusuniverse.economy.shop;

import com.nexusuniverse.economy.AccountManager;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

import java.util.UUID;

/**
 * Routes every dollar spent buying something off the shop -- the main /shop menu, the
 * Custom/Enchantments/NexusEnchants/Accessories/Potions pinned tabs, the Hearts/Hunger/Oxygen
 * vitals upgrades, Accessory Slot purchases, and the credit-card shop -- to one fixed recipient
 * account, instead of the withdrawn money simply vanishing (which is how every one of those
 * purchases worked before: a plain accounts.withdraw() with nothing on the other side of it, a
 * straightforward money sink). Selling TO the shop is untouched -- that's the shop paying the
 * player, not a purchase, so it's not rerouted.
 *
 * The recipient is configured by player name (shop.revenue-recipient in config.yml) rather than
 * by UUID, since that's what an admin can actually type. The name -> UUID lookup happens once,
 * the first time a sale is credited, and is cached from then on -- resolving it on every single
 * purchase would mean every purchase click risks a blocking Mojang lookup on the main thread if
 * the name isn't already in Bukkit's local player cache. If the configured name can't be
 * resolved (nobody by that name has ever played on this server), the deposit is skipped -- with
 * one warning logged, not one per purchase -- rather than crashing a purchase; money still
 * leaves the buyer's account exactly like before, it just doesn't have anywhere confirmed to
 * land yet. Fix shop.revenue-recipient (or have that player join once) and restart to retry.
 *
 * If the configured recipient is the one making the purchase, this nets out to a free
 * purchase for them (withdrawn from their own account, then deposited straight back into it) --
 * an expected, harmless side effect of "all shop revenue is mine" rather than a bug.
 */
public class ShopRevenueRouter {

    private final Plugin plugin;
    private final AccountManager accounts;

    private boolean resolved = false;
    private UUID recipientId;
    private boolean warnedOnce = false;

    public ShopRevenueRouter(Plugin plugin, AccountManager accounts) {
        this.plugin = plugin;
        this.accounts = accounts;
    }

    /** Deposits a completed purchase's total into the configured shop revenue recipient's account. */
    public void creditPurchase(double amount) {
        if (amount <= 0) return;
        UUID id = resolveRecipient();
        if (id == null) return;
        accounts.deposit(id, amount);
    }

    private UUID resolveRecipient() {
        if (resolved) return recipientId;
        resolved = true;

        String recipientName = plugin.getConfig().getString("shop.revenue-recipient", "RealSociety5107");
        if (recipientName == null || recipientName.isBlank()) {
            recipientId = null;
            return null;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(recipientName);
        if (target.hasPlayedBefore() || target.isOnline()) {
            recipientId = target.getUniqueId();
            accounts.createAccount(recipientId);
            plugin.getLogger().info("NexusEconomy: shop revenue is being routed to \"" + recipientName + "\" ("
                    + recipientId + ").");
            return recipientId;
        }

        recipientId = null;
        if (!warnedOnce) {
            plugin.getLogger().warning("NexusEconomy: shop.revenue-recipient is set to \"" + recipientName
                    + "\", but nobody by that name has ever played on this server -- shop purchase money is NOT "
                    + "being routed anywhere right now (it's just being withdrawn like before, same as pre-patch). "
                    + "Fix the name in config.yml, or have that player join once, then restart the server to retry.");
            warnedOnce = true;
        }
        return null;
    }
}
