package com.nexusuniverse.economy.teleport;

import com.nexusuniverse.economy.AccountManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * /tpa: request-to-teleport with a real, escalating cost. A player sends a
 * request, the target has to /tpaccept or /tpdeny it (nobody gets pulled
 * to a stranger without saying yes), and accepting charges the requester
 * -- not the target -- straight out of their NexusEconomy balance.
 *
 * The cost isn't flat: every accepted teleport in a rolling time window
 * (teleport.escalation.window-minutes) makes the NEXT one within that
 * same window more expensive, by a configurable percentage. Ten
 * teleports back-to-back inside the window costs a lot more than one
 * teleport a day -- the window resets itself naturally as old teleports
 * age out of it, with no separate "cooldown" state to manage.
 */
public class TpaManager {

    private final JavaPlugin plugin;
    private final AccountManager accounts;

    /** One inbound request per target at a time -- a newer request to the same target replaces the older one. */
    private final Map<UUID, TpaRequest> pendingByTarget = new HashMap<>();

    /** Timestamps (epoch millis) of each requester's own recent accepted teleports, oldest first, used to price the next one. */
    private final Map<UUID, Deque<Long>> recentTeleports = new HashMap<>();

    public TpaManager(JavaPlugin plugin, AccountManager accounts) {
        this.plugin = plugin;
        this.accounts = accounts;

        // Sweeps out expired requests on its own so a request nobody ever answers doesn't
        // just sit there forever -- both sides get told once, when it actually expires.
        Bukkit.getScheduler().runTaskTimer(plugin, this::expireStale, 20L * 10, 20L * 10);
    }

    // --- config-driven knobs, re-read live so /economyadmin-style config reloads apply immediately ---

    private boolean enabled() {
        return plugin.getConfig().getBoolean("teleport.enabled", true);
    }

    private double baseCost() {
        return plugin.getConfig().getDouble("teleport.base-cost", 10.0);
    }

    private long requestExpirySeconds() {
        return plugin.getConfig().getLong("teleport.request-expiry-seconds", 60);
    }

    private long windowMillis() {
        return plugin.getConfig().getLong("teleport.escalation.window-minutes", 10) * 60_000L;
    }

    private double growthPercent() {
        return plugin.getConfig().getDouble("teleport.escalation.growth-percent", 35.0);
    }

    private double maxCost() {
        return plugin.getConfig().getDouble("teleport.escalation.max-cost", 500.0);
    }

    private boolean payToTarget() {
        return plugin.getConfig().getBoolean("teleport.pay-to-target", false);
    }

    private boolean bypassChargeEnabled() {
        return plugin.getConfig().getBoolean("teleport.bypass.charge-enabled", false);
    }

    private double bypassCost() {
        return plugin.getConfig().getDouble("teleport.bypass.cost", 50.0);
    }

    // --- pricing ---

    /** Prices what the requester's NEXT teleport would cost right now, based on how many they've already made in the current window. Doesn't record anything. */
    public double previewCost(UUID requesterId) {
        int usesInWindow = pruneAndCount(requesterId);
        double growthMultiplier = Math.pow(1.0 + (growthPercent() / 100.0), usesInWindow);
        double cost = baseCost() * growthMultiplier;
        double cap = maxCost();
        return cap > 0 ? Math.min(cost, cap) : cost;
    }

    private int pruneAndCount(UUID requesterId) {
        Deque<Long> history = recentTeleports.get(requesterId);
        if (history == null) return 0;
        long cutoff = System.currentTimeMillis() - windowMillis();
        Iterator<Long> it = history.iterator();
        while (it.hasNext()) {
            if (it.next() < cutoff) it.remove(); else break; // oldest-first, so we can stop at the first still-valid entry
        }
        return history.size();
    }

    private void recordTeleport(UUID requesterId) {
        recentTeleports.computeIfAbsent(requesterId, id -> new ArrayDeque<>()).addLast(System.currentTimeMillis());
    }

    // --- admin bypass: skips the request/accept flow entirely, and is free by default ---

    public enum AdminResult {SELF, TARGET_OFFLINE, INSUFFICIENT_FUNDS, OK}

    /**
     * Immediate teleport for permission holders (see nexuseconomy.tpa.bypass
     * in plugin.yml) -- no request, no accept/deny, no waiting. Free unless
     * teleport.bypass.charge-enabled is turned on in config.yml, in which
     * case it charges the flat teleport.bypass.cost every time (not the
     * escalating price ordinary players pay, and it doesn't count toward
     * their own escalation history either -- this is a separate, simpler
     * path on purpose).
     */
    public AdminResult adminTeleport(Player requester, Player target) {
        if (requester.getUniqueId().equals(target.getUniqueId())) return AdminResult.SELF;
        if (!target.isOnline()) return AdminResult.TARGET_OFFLINE;

        double cost = bypassChargeEnabled() ? bypassCost() : 0.0;
        if (cost > 0) {
            if (!accounts.has(requester.getUniqueId(), cost)) {
                requester.sendMessage("§cYou can't afford the $" + String.format("%,.2f", cost) + " admin teleport fee.");
                return AdminResult.INSUFFICIENT_FUNDS;
            }
            accounts.withdraw(requester.getUniqueId(), cost);
            if (payToTarget()) accounts.deposit(target.getUniqueId(), cost);
        }

        // If this same pair already had an ordinary request pending (e.g. sent just before this
        // player got bypass permission), clear it -- they're already there, so a stale
        // /tpaccept later shouldn't try to charge/teleport them again. Leaves anyone ELSE's
        // pending request to the target alone.
        TpaRequest existing = pendingByTarget.get(target.getUniqueId());
        if (existing != null && existing.requesterId().equals(requester.getUniqueId())) {
            pendingByTarget.remove(target.getUniqueId());
        }

        requester.teleport(target.getLocation());
        if (cost > 0) {
            requester.sendMessage("§aTeleported to " + target.getName() + " for §7$" + String.format("%,.2f", cost) + "§a.");
        } else {
            requester.sendMessage("§aTeleported to " + target.getName() + ".");
        }
        target.sendMessage("§7" + requester.getName() + " teleported to you.");
        return AdminResult.OK;
    }

    // --- request lifecycle ---

    public enum SendResult {DISABLED, SELF, TARGET_OFFLINE, ALREADY_PENDING_SAME, OK}

    public SendResult sendRequest(Player requester, Player target) {
        if (!enabled()) return SendResult.DISABLED;
        if (requester.getUniqueId().equals(target.getUniqueId())) return SendResult.SELF;
        if (!target.isOnline()) return SendResult.TARGET_OFFLINE;

        TpaRequest existing = pendingByTarget.get(target.getUniqueId());
        if (existing != null && existing.requesterId().equals(requester.getUniqueId()) && !existing.isExpired(System.currentTimeMillis())) {
            return SendResult.ALREADY_PENDING_SAME;
        }

        long expiresAt = System.currentTimeMillis() + requestExpirySeconds() * 1000L;
        pendingByTarget.put(target.getUniqueId(),
                new TpaRequest(requester.getUniqueId(), requester.getName(), target.getUniqueId(), expiresAt));
        return SendResult.OK;
    }

    public enum ResolveResult {NO_REQUEST, REQUESTER_OFFLINE, INSUFFICIENT_FUNDS, OK}

    /** Accepts whatever request (if any) is currently pending against this target, charges the requester, and teleports them in. */
    public ResolveResult accept(Player target) {
        TpaRequest request = pendingByTarget.get(target.getUniqueId());
        if (request == null || request.isExpired(System.currentTimeMillis())) {
            pendingByTarget.remove(target.getUniqueId());
            return ResolveResult.NO_REQUEST;
        }

        Player requester = Bukkit.getPlayer(request.requesterId());
        if (requester == null || !requester.isOnline()) {
            pendingByTarget.remove(target.getUniqueId());
            return ResolveResult.REQUESTER_OFFLINE;
        }

        double cost = previewCost(requester.getUniqueId());
        if (!accounts.has(requester.getUniqueId(), cost)) {
            pendingByTarget.remove(target.getUniqueId());
            requester.sendMessage("§c" + target.getName() + " accepted, but you can't afford the $"
                    + String.format("%,.2f", cost) + " trip -- the request was cancelled.");
            target.sendMessage("§7" + requester.getName() + " couldn't afford the teleport, so nothing happened.");
            return ResolveResult.INSUFFICIENT_FUNDS;
        }

        accounts.withdraw(requester.getUniqueId(), cost);
        if (payToTarget()) accounts.deposit(target.getUniqueId(), cost);
        recordTeleport(requester.getUniqueId());
        pendingByTarget.remove(target.getUniqueId());

        requester.teleport(target.getLocation());
        requester.sendMessage("§aTeleported to " + target.getName() + " for §7$" + String.format("%,.2f", cost) + "§a.");
        target.sendMessage("§7" + requester.getName() + " teleported to you.");
        return ResolveResult.OK;
    }

    /** Denies whatever request is currently pending against this target. */
    public boolean deny(Player target) {
        TpaRequest request = pendingByTarget.remove(target.getUniqueId());
        if (request == null) return false;

        Player requester = Bukkit.getPlayer(request.requesterId());
        if (requester != null && requester.isOnline()) {
            requester.sendMessage("§c" + target.getName() + " denied your teleport request.");
        }
        return true;
    }

    /** Cancels whatever outgoing request this player currently has pending, wherever it's pointed. */
    public boolean cancel(Player requester) {
        Iterator<Map.Entry<UUID, TpaRequest>> it = pendingByTarget.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, TpaRequest> entry = it.next();
            if (entry.getValue().requesterId().equals(requester.getUniqueId())) {
                it.remove();
                Player target = Bukkit.getPlayer(entry.getKey());
                if (target != null && target.isOnline()) {
                    target.sendMessage("§7" + requester.getName() + " cancelled their teleport request.");
                }
                return true;
            }
        }
        return false;
    }

    /** Drops any pending request involving a player who just left, in either direction -- nothing lingers past the session. */
    public void clearForPlayer(UUID playerId) {
        pendingByTarget.remove(playerId);
        pendingByTarget.values().removeIf(r -> r.requesterId().equals(playerId));
        recentTeleports.remove(playerId);
    }

    private void expireStale() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, TpaRequest>> it = pendingByTarget.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, TpaRequest> entry = it.next();
            if (!entry.getValue().isExpired(now)) continue;
            it.remove();

            Player target = Bukkit.getPlayer(entry.getKey());
            if (target != null) target.sendMessage("§7A teleport request to you expired.");
            Player requester = Bukkit.getPlayer(entry.getValue().requesterId());
            if (requester != null) requester.sendMessage("§7Your teleport request expired.");
        }
    }
}
