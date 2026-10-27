package fr.luclyoko.gitupdater;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Logique de la commande d'admin, indépendante du framework de commandes:
 * appeler {@link #handle} / {@link #complete} depuis un CommandExecutor / TabCompleter / @Command.
 */
public class UpdaterCommand {

    private static final List<String> SUB = Arrays.asList("check", "update", "branch", "list", "auto", "info", "token");

    private final GitUpdater updater;
    private final UpdaterConfig config;

    public UpdaterCommand(GitUpdater updater) {
        this.updater = updater;
        this.config = updater.getConfig();
    }

    public void handle(CommandSender sender, String[] args) {
        if (sender instanceof Player && !sender.isOp()) {
            sender.sendMessage(ChatColor.RED + "Vous n'avez pas la permission d'utiliser cette commande.");
            return;
        }
        if (args.length == 0) {
            help(sender);
            return;
        }
        switch (args[0].toLowerCase()) {
            case "check": check(sender); break;
            case "update": update(sender); break;
            case "branch": branch(sender, args); break;
            case "auto": auto(sender, args); break;
            case "list": list(sender); break;
            case "info": info(sender); break;
            case "token": token(sender, args); break;
            default: help(sender);
        }
    }

    public List<String> complete(CommandSender sender, String[] args) {
        List<String> out = new ArrayList<>();
        if (sender instanceof Player && !sender.isOp()) {
            return out;
        }
        String last = args[args.length - 1].toLowerCase();
        if (args.length == 1) {
            out.addAll(SUB);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("auto")) {
            out.addAll(Arrays.asList("on", "off", "interval"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("branch")) {
            try {
                out.addAll(updater.getAvailableBranches().get(2, TimeUnit.SECONDS));
            } catch (Exception ignored) {
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("auto") && args[1].equalsIgnoreCase("interval")) {
            out.addAll(Arrays.asList("60", "300", "600", "1800", "3600"));
        }
        return out.stream().filter(s -> s.toLowerCase().startsWith(last)).sorted().collect(Collectors.toList());
    }

    private void help(CommandSender s) {
        s.sendMessage(ChatColor.GOLD + "========== " + ChatColor.YELLOW + "GitUpdate Help" + ChatColor.GOLD + " ==========");
        s.sendMessage(ChatColor.AQUA + "check" + ChatColor.GRAY + " - Vérifie s'il y a une mise à jour");
        s.sendMessage(ChatColor.AQUA + "update" + ChatColor.GRAY + " - Force une mise à jour");
        s.sendMessage(ChatColor.AQUA + "branch <nom>" + ChatColor.GRAY + " - Change de branche");
        s.sendMessage(ChatColor.AQUA + "list" + ChatColor.GRAY + " - Liste les branches");
        s.sendMessage(ChatColor.AQUA + "auto <on|off|interval <s>>" + ChatColor.GRAY + " - Mise à jour auto");
        s.sendMessage(ChatColor.AQUA + "info" + ChatColor.GRAY + " - Infos actuelles");
        s.sendMessage(ChatColor.AQUA + "token <token>" + ChatColor.GRAY + " - Token GitHub (dépôts privés)");
    }

    private void check(CommandSender s) {
        s.sendMessage(ChatColor.YELLOW + "Vérification en cours...");
        updater.checkForUpdates().thenAccept(has -> s.sendMessage(has
                ? ChatColor.GREEN + "✓ Mise à jour disponible sur " + ChatColor.AQUA + config.getBranch() + ChatColor.GREEN + ", faites update."
                : ChatColor.GREEN + "✓ À jour sur la branche " + ChatColor.AQUA + config.getBranch()))
                .exceptionally(t -> {
                    s.sendMessage(ChatColor.RED + "✗ Erreur: " + t.getMessage());
                    return null;
                });
    }

    private void update(CommandSender s) {
        if (updater.isUpdating()) {
            s.sendMessage(ChatColor.RED + "Une mise à jour est déjà en cours.");
            return;
        }
        s.sendMessage(ChatColor.YELLOW + "Démarrage de la mise à jour...");
        updater.performUpdate();
    }

    private void branch(CommandSender s, String[] args) {
        if (args.length < 2) {
            s.sendMessage(ChatColor.RED + "Usage: branch <nom> (actuelle: " + config.getBranch() + ")");
            return;
        }
        if (updater.isUpdating()) {
            s.sendMessage(ChatColor.RED + "Une mise à jour est déjà en cours.");
            return;
        }
        updater.switchBranch(args[1]).thenAccept(ok -> {
            if (ok) {
                s.sendMessage(ChatColor.GREEN + "✓ Branche " + ChatColor.AQUA + args[1] + ChatColor.GREEN + ", mise à jour...");
                updater.performUpdate();
            } else {
                s.sendMessage(ChatColor.RED + "✗ Branche introuvable ou erreur (voir logs).");
            }
        });
    }

    private void auto(CommandSender s, String[] args) {
        String action = args.length > 1 ? args[1].toLowerCase() : "";
        switch (action) {
            case "on":
                config.setAutoUpdateEnabled(true);
                updater.startAutoUpdate();
                s.sendMessage(ChatColor.GREEN + "✓ Mise à jour auto activée (" + config.getCheckInterval() + "s)");
                break;
            case "off":
                config.setAutoUpdateEnabled(false);
                updater.stopAutoUpdate();
                s.sendMessage(ChatColor.GREEN + "✓ Mise à jour auto désactivée");
                break;
            case "interval":
                try {
                    int seconds = Integer.parseInt(args[2]);
                    if (seconds < 60) {
                        s.sendMessage(ChatColor.RED + "L'intervalle minimum est de 60 secondes.");
                        return;
                    }
                    config.setCheckInterval(seconds);
                    if (config.isAutoUpdateEnabled()) {
                        updater.startAutoUpdate();
                    }
                    s.sendMessage(ChatColor.GREEN + "✓ Intervalle: " + seconds + "s");
                } catch (RuntimeException e) {
                    s.sendMessage(ChatColor.RED + "Usage: auto interval <secondes>");
                }
                break;
            default:
                s.sendMessage(ChatColor.RED + "Usage: auto <on|off|interval <secondes>> (actuel: "
                        + (config.isAutoUpdateEnabled() ? "on" : "off") + ")");
        }
    }

    private void list(CommandSender s) {
        updater.getAvailableBranches().thenAccept(branches -> {
            if (branches.isEmpty()) {
                s.sendMessage(ChatColor.RED + "Aucune branche trouvée.");
                return;
            }
            for (String b : branches) {
                s.sendMessage(b.equals(config.getBranch())
                        ? ChatColor.GREEN + "► " + ChatColor.AQUA + b + ChatColor.GRAY + " (actuelle)"
                        : ChatColor.GRAY + "  " + ChatColor.WHITE + b);
            }
        });
    }

    private void info(CommandSender s) {
        String commit = config.getLastCommit();
        s.sendMessage(ChatColor.GRAY + "Dépôt: " + ChatColor.AQUA + updater.getRepoUrl());
        s.sendMessage(ChatColor.GRAY + "Branche: " + ChatColor.AQUA + config.getBranch());
        s.sendMessage(ChatColor.GRAY + "Auto: " + (config.isAutoUpdateEnabled() ? ChatColor.GREEN + "on" : ChatColor.RED + "off")
                + ChatColor.GRAY + " (" + config.getCheckInterval() + "s)");
        if (!commit.isEmpty()) {
            s.sendMessage(ChatColor.GRAY + "Dernier commit: " + ChatColor.AQUA + commit.substring(0, Math.min(8, commit.length())));
        }
        s.sendMessage(ChatColor.GRAY + "Statut: " + (updater.isUpdating() ? ChatColor.YELLOW + "Mise à jour en cours..." : ChatColor.GREEN + "Prêt"));
    }

    private void token(CommandSender s, String[] args) {
        if (args.length < 2) {
            s.sendMessage(ChatColor.RED + "Usage: token <token> (https://github.com/settings/tokens, scope 'repo')");
            return;
        }
        config.setGitHubToken(args[1]);
        s.sendMessage(ChatColor.GREEN + "✓ Token enregistré. Testez avec: check");
    }
}
