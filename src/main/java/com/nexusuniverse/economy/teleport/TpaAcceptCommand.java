package com.nexusuniverse.economy.teleport;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class TpaAcceptCommand implements CommandExecutor {

    private final TpaManager tpa;

    public TpaAcceptCommand(TpaManager tpa) {
        this.tpa = tpa;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player target)) {
            sender.sendMessage("Players only.");
            return true;
        }

        switch (tpa.accept(target)) {
            case NO_REQUEST -> target.sendMessage("§cYou don't have a pending teleport request.");
            case REQUESTER_OFFLINE -> target.sendMessage("§cThat player logged off before you accepted.");
            case INSUFFICIENT_FUNDS -> {} // TpaManager already messaged both sides
            case OK -> {} // TpaManager already messaged both sides
        }
        return true;
    }
}
