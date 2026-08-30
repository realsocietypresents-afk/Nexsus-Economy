package com.nexusuniverse.economy;

import com.nexusuniverse.economy.vitals.VitalsManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public class EconomyAdminCommand implements CommandExecutor {

    private final AccountManager accounts;
    private final VitalsManager vitals;

    public EconomyAdminCommand(AccountManager accounts, VitalsManager vitals) {
        this.accounts = accounts;
        this.vitals = vitals;
    }

    private static final String USAGE = "Usage: /economyadmin <set|add|remove> <player> <amount>, "
            + "or /economyadmin vitals <player> <hearts|hunger|oxygen> <level>";

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 1) {
            sender.sendMessage(ChatColor.RED + USAGE);
            return true;
        }

        if (args[0].equalsIgnoreCase("vitals")) {
            handleVitals(sender, args);
            return true;
        }

        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + USAGE);
            return true;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        double amount;
        try {
            amount = Double.parseDouble(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "That's not a valid amount.");
            return true;
        }

        accounts.createAccount(target.getUniqueId());

        switch (args[0].toLowerCase()) {
            case "set" -> {
                accounts.setBalance(target.getUniqueId(), amount);
                sender.sendMessage(ChatColor.GREEN + target.getName() + "'s balance set to $" + String.format("%.2f", amount) + ".");
            }
            case "add" -> {
                accounts.deposit(target.getUniqueId(), amount);
                sender.sendMessage(ChatColor.GREEN + "Added $" + String.format("%.2f", amount) + " to " + target.getName() + ".");
            }
            case "remove" -> {
                if (!accounts.withdraw(target.getUniqueId(), amount)) {
                    sender.sendMessage(ChatColor.RED + target.getName() + " doesn't have that much.");
                    return true;
                }
                sender.sendMessage(ChatColor.GREEN + "Removed $" + String.format("%.2f", amount) + " from " + target.getName() + ".");
            }
            default -> sender.sendMessage(ChatColor.RED + USAGE);
        }
        return true;
    }

    /**
     * Support/moderation override for the shop's Hearts/Hunger/Oxygen tabs -- sets a
     * player's level directly, no cost charged, clamped to that vital's configured
     * max-level. Same nexuseconomy.admin permission as the rest of /economyadmin
     * (enforced by Bukkit before onCommand even runs, per plugin.yml).
     */
    private void handleVitals(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(ChatColor.RED + "Usage: /economyadmin vitals <player> <hearts|hunger|oxygen> <level>");
            return;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        VitalsManager.VitalType type;
        try {
            type = VitalsManager.VitalType.valueOf(args[2].toUpperCase());
        } catch (IllegalArgumentException e) {
            sender.sendMessage(ChatColor.RED + "That's not a valid vital -- use hearts, hunger, or oxygen.");
            return;
        }

        int level;
        try {
            level = Integer.parseInt(args[3]);
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "Level must be a whole number.");
            return;
        }

        vitals.setLevelAdmin(target, type, level);
        sender.sendMessage(ChatColor.GREEN + "Set " + target.getName() + "'s " + args[2].toLowerCase()
                + " level to " + vitals.level(target.getUniqueId(), type) + " (max " + vitals.maxLevel(type) + ").");
    }
}
