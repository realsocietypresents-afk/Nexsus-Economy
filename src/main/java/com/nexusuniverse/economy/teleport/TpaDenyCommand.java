package com.nexusuniverse.economy.teleport;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class TpaDenyCommand implements CommandExecutor {

    private final TpaManager tpa;

    public TpaDenyCommand(TpaManager tpa) {
        this.tpa = tpa;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player target)) {
            sender.sendMessage("Players only.");
            return true;
        }

        if (tpa.deny(target)) {
            target.sendMessage("§7Request denied.");
        } else {
            target.sendMessage("§cYou don't have a pending teleport request.");
        }
        return true;
    }
}
