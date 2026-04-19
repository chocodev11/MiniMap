package epicc.dev.commands;

import epicc.dev.MiniMap;
import epicc.dev.service.HudService;
import epicc.dev.service.PackBuildService;
import epicc.dev.service.PackDeliveryService;
import epicc.dev.service.SessionService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public final class MinimapCommand implements CommandExecutor, TabCompleter {
    private final MiniMap plugin;
    private final PackBuildService packBuildService;
    private final PackDeliveryService packDeliveryService;
    private final SessionService sessionService;
    private final HudService hudService;

    public MinimapCommand(
            MiniMap plugin,
            PackBuildService packBuildService,
            PackDeliveryService packDeliveryService,
            SessionService sessionService,
            HudService hudService
    ) {
        this.plugin = plugin;
        this.packBuildService = packBuildService;
        this.packDeliveryService = packDeliveryService;
        this.sessionService = sessionService;
        this.hudService = hudService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("/minimap reload");
            sender.sendMessage("/minimap pack rebuild");
            sender.sendMessage("/minimap pack send <player|all>");
            sender.sendMessage("/minimap hud <on|off>");
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                if (!sender.hasPermission("minimap.admin")) {
                    sender.sendMessage("You do not have permission.");
                    return true;
                }

                this.plugin.reloadRuntime();
                sender.sendMessage("MiniMap reloaded.");
                return true;
            }
            case "pack" -> {
                if (!sender.hasPermission("minimap.admin")) {
                    sender.sendMessage("You do not have permission.");
                    return true;
                }

                if (args.length < 2) {
                    sender.sendMessage("Usage: /minimap pack <rebuild|send>");
                    return true;
                }

                String action = args[1].toLowerCase(Locale.ROOT);
                if ("rebuild".equals(action)) {
                    this.packBuildService.rebuildPack();
                    sender.sendMessage("Pack rebuilt.");
                    return true;
                }

                if ("send".equals(action)) {
                    if (args.length < 3) {
                        sender.sendMessage("Usage: /minimap pack send <player|all>");
                        return true;
                    }

                    if ("all".equalsIgnoreCase(args[2])) {
                        this.packDeliveryService.sendPackToAll();
                        sender.sendMessage("Pack sent to all online players.");
                        return true;
                    }

                    Player target = Bukkit.getPlayerExact(args[2]);
                    if (target == null) {
                        sender.sendMessage("Player not found.");
                        return true;
                    }

                    this.packDeliveryService.sendPack(target);
                    sender.sendMessage("Pack sent to " + target.getName() + ".");
                    return true;
                }

                sender.sendMessage("Usage: /minimap pack <rebuild|send>");
                return true;
            }
            case "hud" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Only players can use /minimap hud.");
                    return true;
                }

                if (!player.hasPermission("minimap.hud")) {
                    player.sendMessage("You do not have permission.");
                    return true;
                }

                if (args.length < 2) {
                    player.sendMessage("Usage: /minimap hud <on|off>");
                    return true;
                }

                String mode = args[1].toLowerCase(Locale.ROOT);
                if ("on".equals(mode)) {
                    this.sessionService.setHudEnabled(player.getUniqueId(), true);
                    this.hudService.showHud(player);
                    player.sendMessage("MiniMap HUD enabled.");
                    return true;
                }

                if ("off".equals(mode)) {
                    this.sessionService.setHudEnabled(player.getUniqueId(), false);
                    this.hudService.hideHud(player);
                    player.sendMessage("MiniMap HUD disabled.");
                    return true;
                }

                player.sendMessage("Usage: /minimap hud <on|off>");
                return true;
            }
            default -> {
                sender.sendMessage("Unknown subcommand.");
                return true;
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> suggestions = new ArrayList<>();

        if (args.length == 1) {
            addIfStartsWith(suggestions, args[0], "reload");
            addIfStartsWith(suggestions, args[0], "pack");
            addIfStartsWith(suggestions, args[0], "hud");
            return suggestions;
        }

        if (args.length == 2 && "pack".equalsIgnoreCase(args[0])) {
            addIfStartsWith(suggestions, args[1], "rebuild");
            addIfStartsWith(suggestions, args[1], "send");
            return suggestions;
        }

        if (args.length == 3 && "pack".equalsIgnoreCase(args[0]) && "send".equalsIgnoreCase(args[1])) {
            addIfStartsWith(suggestions, args[2], "all");
            for (Player player : Bukkit.getOnlinePlayers()) {
                addIfStartsWith(suggestions, args[2], player.getName());
            }
            return suggestions;
        }

        if (args.length == 2 && "hud".equalsIgnoreCase(args[0])) {
            addIfStartsWith(suggestions, args[1], "on");
            addIfStartsWith(suggestions, args[1], "off");
            return suggestions;
        }

        return suggestions;
    }

    private static void addIfStartsWith(List<String> suggestions, String input, String option) {
        if (option.toLowerCase(Locale.ROOT).startsWith(input.toLowerCase(Locale.ROOT))) {
            suggestions.add(option);
        }
    }
}
