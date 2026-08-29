package com.nexusuniverse.economy.teleport;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public class TpaTabCompleter implements TabCompleter {

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return List.of();

        String partial = args[0].toLowerCase();
        List<String> matches = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (sender instanceof Player self && online.getUniqueId().equals(self.getUniqueId())) continue;
            if (online.getName().toLowerCase().startsWith(partial)) matches.add(online.getName());
        }
        return matches;
    }
}
