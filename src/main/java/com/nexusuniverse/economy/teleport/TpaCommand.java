package com.nexusuniverse.economy.teleport;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class TpaCommand implements CommandExecutor {

    private final TpaManager tpa;

    public TpaCommand(TpaManager tpa) {
        this.tpa = tpa;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player requester)) {
            sender.sendMessage("Players only.");
            return true;
        }
        if (args.length < 1) {
            requester.sendMessage("§cUsage: /tpa <player>");
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            requester.sendMessage("§cNo player named '" + args[0] + "' is online.");
            return true;
        }

        if (requester.hasPermission("nexuseconomy.tpa.bypass")) {
            switch (tpa.adminTeleport(requester, target)) {
                case SELF -> requester.sendMessage("§cYou can't teleport to yourself.");
                case TARGET_OFFLINE -> requester.sendMessage("§cNo player named '" + args[0] + "' is online.");
                case INSUFFICIENT_FUNDS -> {} // TpaManager already messaged the requester
                case OK -> {} // TpaManager already messaged both sides
            }
            return true;
        }

        double previewCost = tpa.previewCost(requester.getUniqueId());

        switch (tpa.sendRequest(requester, target)) {
            case DISABLED -> requester.sendMessage("§cTeleport requests are currently disabled.");
            case SELF -> requester.sendMessage("§cYou can't send a teleport request to yourself.");
            case TARGET_OFFLINE -> requester.sendMessage("§cNo player named '" + args[0] + "' is online.");
            case ALREADY_PENDING_SAME -> requester.sendMessage("§7You already have a pending request to " + target.getName() + ".");
            case OK -> {
                requester.sendMessage("§aTeleport request sent to " + target.getName()
                        + ". §7If accepted, it'll cost you §f$" + String.format("%,.2f", previewCost) + "§7.");
                target.sendMessage("§e" + requester.getName() + " wants to teleport to you. §7/tpaccept or /tpdeny");
            }
        }
        return true;
    }
}
