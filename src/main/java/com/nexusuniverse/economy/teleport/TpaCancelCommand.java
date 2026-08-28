package com.nexusuniverse.economy.teleport;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class TpaCancelCommand implements CommandExecutor {

    private final TpaManager tpa;

    public TpaCancelCommand(TpaManager tpa) {
        this.tpa = tpa;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player requester)) {
            sender.sendMessage("Players only.");
            return true;
        }

        if (tpa.cancel(requester)) {
            requester.sendMessage("§7Teleport request cancelled.");
        } else {
            requester.sendMessage("§cYou don't have a pending outgoing request.");
        }
        return true;
    }
}
