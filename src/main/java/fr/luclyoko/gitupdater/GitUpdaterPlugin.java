package fr.luclyoko.gitupdater;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

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
        GitUpdater updater = args.length == 0 ? null : find(args[0]);
        if (updater == null) {
            sender.sendMessage(ChatColor.RED + "Usage: /" + label + " <plugin> [check|update|branch|list|auto|info|token]");
            sender.sendMessage(ChatColor.GRAY + "Plugins: " + ChatColor.AQUA + String.join(", ", names()));
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
            return names().stream().filter(n -> n.toLowerCase().startsWith(args[0].toLowerCase())).collect(Collectors.toList());
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
