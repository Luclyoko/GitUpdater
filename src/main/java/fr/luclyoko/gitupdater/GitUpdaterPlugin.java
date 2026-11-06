package fr.luclyoko.gitupdater;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Charge la lib depuis plugins/ pour que les autres plugins la partagent (compileOnly côté consommateur),
 * et porte la commande /gitupdater (alias /gup) {@code <plugin> <sous-commande>} pour tous les plugins qui l'utilisent.
 */
public class GitUpdaterPlugin extends JavaPlugin {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 2 && args[0].equalsIgnoreCase("all")
                && (args[1].equalsIgnoreCase("check") || args[1].equalsIgnoreCase("update"))) {
            boolean install = args[1].equalsIgnoreCase("update");
            List<String> all = names();
            sender.sendMessage(GitUpdater.prefix("GitUpdater") + (install ? "Mise à jour" : "Vérification") + " de " + all.size() + " plugin(s)...");
            for (String n : all) {
                new UpdaterCommand(find(n)).checkThenMaybeUpdate(sender, install);
            }
            return true;
        }
        GitUpdater updater = args.length == 0 ? null : find(args[0]);
        if (updater == null) {
            String p = GitUpdater.prefix("GitUpdater");
            if (args.length > 0 && !args[0].equalsIgnoreCase("all")) {
                sender.sendMessage(p + ChatColor.RED + "✗ Plugin " + args[0] + " inconnu.");
            }
            sender.sendMessage(p + "Plugins : " + ChatColor.AQUA + String.join(ChatColor.GRAY + ", " + ChatColor.AQUA, names()));
            sender.sendMessage(p + ChatColor.AQUA + "/" + label + " <plugin>" + ChatColor.GRAY + " pour l'aide d'un plugin, "
                    + ChatColor.AQUA + "/" + label + " all <check|update>" + ChatColor.GRAY + " pour tous.");
            return true;
        }
        new UpdaterCommand(updater).handle(sender, Arrays.copyOfRange(args, 1, args.length));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("gitupdater.admin")) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            List<String> first = new ArrayList<>(names());
            first.add("all");
            return first.stream().filter(n -> n.toLowerCase().startsWith(args[0].toLowerCase())).collect(Collectors.toList());
        }
        if (args[0].equalsIgnoreCase("all")) {
            return args.length == 2 ? Arrays.asList("check", "update").stream()
                    .filter(n -> n.startsWith(args[1].toLowerCase())).collect(Collectors.toList()) : Collections.emptyList();
        }
        GitUpdater updater = find(args[0]);
        return updater == null ? Collections.emptyList() : new UpdaterCommand(updater).complete(Arrays.copyOfRange(args, 1, args.length));
    }

    private static GitUpdater find(String name) {
        GitUpdater u = GitUpdater.REGISTRY.get(name);
        return u != null && u.getPlugin().isEnabled() ? u : null;
    }

    private static List<String> names() {
        return GitUpdater.REGISTRY.values().stream().filter(u -> u.getPlugin().isEnabled())
                .map(u -> u.getPlugin().getName()).collect(Collectors.toList());
    }
}
