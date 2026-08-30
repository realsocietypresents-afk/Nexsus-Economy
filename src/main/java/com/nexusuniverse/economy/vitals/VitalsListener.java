package com.nexusuniverse.economy.vitals;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityAirChangeEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Makes purchased Hunger/Oxygen levels actually do something. Both bars are still the
 * plain vanilla 20-point food bar / 300-tick air supply (see VitalsManager's class
 * comment for why they can't be extended the way hearts can) -- what a level buys is a
 * rolled chance to silently keep the bar where it is instead of letting it tick down,
 * so the bar you already have visibly lasts longer the more levels you own.
 *
 * Also reapplies the Hearts attribute modifier on join -- defensive, in case another
 * plugin, a datapack, or a player-data edit ever strips it between sessions. Harmless
 * to call even if it was already correct: applyHeartsAttribute() always rebuilds from
 * the stored level rather than assuming anything about what's currently applied.
 */
public class VitalsListener implements Listener {

    private final VitalsManager vitals;

    public VitalsListener(VitalsManager vitals) {
        this.vitals = vitals;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        vitals.applyHeartsAttribute(event.getPlayer());
    }

    @EventHandler
    public void onFoodLevelChange(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (event.getFoodLevel() >= player.getFoodLevel()) return; // only slow drains -- never interfere with eating

        double chance = vitals.hungerNegateChance(player.getUniqueId());
        if (chance > 0 && ThreadLocalRandom.current().nextDouble() < chance) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onAirChange(EntityAirChangeEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (event.getAmount() >= player.getRemainingAir()) return; // only slow drains -- never interfere with refilling

        double chance = vitals.oxygenNegateChance(player.getUniqueId());
        if (chance > 0 && ThreadLocalRandom.current().nextDouble() < chance) {
            event.setCancelled(true);
        }
    }
}
