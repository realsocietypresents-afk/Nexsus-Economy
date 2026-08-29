package com.nexusuniverse.economy.teleport;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

public class TpaQuitListener implements Listener {

    private final TpaManager tpa;

    public TpaQuitListener(TpaManager tpa) {
        this.tpa = tpa;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        tpa.clearForPlayer(event.getPlayer().getUniqueId());
    }
}
