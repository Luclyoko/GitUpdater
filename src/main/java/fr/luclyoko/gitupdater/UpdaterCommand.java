package fr.luclyoko.gitupdater;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Sous-commandes de /gitupdater <plugin> pour un updater.
 */
class UpdaterCommand {

    private static final List<String> SUB = Arrays.asList("check", "update", "branch", "list", "auto", "info", "token");

    private final GitUpdater updater;
    private final UpdaterConfig config;

    private final String name;

    UpdaterCommand(GitUpdater updater) {
        this.updater = updater;
        this.config = updater.getConfig();
        this.name = updater.getPlugin().getName();
    }

    /** Préfixe chaque message du nom du plugin concerné. */
    private void send(CommandSender s, String message) {
        s.sendMessage(GitUpdater.prefix(name) + message);
    }

    void handle(CommandSender sender, String[] args) {
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

    List<String> complete(String[] args) {
        List<String> out = new ArrayList<>();
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
        String cmd = ChatColor.AQUA + "/gup " + name + " ";
        String sep = ChatColor.DARK_GRAY + " - " + ChatColor.GRAY;
        s.sendMessage(GitUpdater.prefix(name) + "Commandes :");
        s.sendMessage(cmd + "check" + sep + "vérifier les mises à jour");
        s.sendMessage(cmd + "update" + sep + "installer la dernière version");
        s.sendMessage(cmd + "branch <nom>" + sep + "changer de branche");
        s.sendMessage(cmd + "list" + sep + "voir les branches");
        s.sendMessage(cmd + "auto <on|off|interval <s>>" + sep + "mise à jour auto");
        s.sendMessage(cmd + "info" + sep + "état actuel");
        s.sendMessage(cmd + "token <token>" + sep + "token GitHub (dépôt privé)");
    }

    private String branchName() {
        return ChatColor.AQUA + config.getBranch() + ChatColor.GRAY;
    }

    private void check(CommandSender s) {
        updater.checkForUpdates().thenAccept(has -> send(s, has
                ? ChatColor.YELLOW + "Mise à jour disponible" + ChatColor.GRAY + " sur " + branchName() + ", faites " + ChatColor.AQUA + "/gup " + name + " update"
                : ChatColor.GREEN + "✓ Déjà à jour" + ChatColor.GRAY + " (" + branchName() + ")"));
    }

    /** /gup all check|update : vérifie chaque plugin, et avec update installe ceux qui ont une mise à jour. */
    void checkThenMaybeUpdate(CommandSender s, boolean install) {
        if (updater.isUpdating()) {
            send(s, ChatColor.YELLOW + "Mise à jour déjà en cours...");
            return;
        }
        updater.checkForUpdates().thenAccept(has -> {
            if (!has) {
                send(s, ChatColor.GREEN + "✓ Déjà à jour" + ChatColor.GRAY + " (" + branchName() + ")");
            } else if (install) {
                send(s, ChatColor.YELLOW + "Téléchargement..." + ChatColor.GRAY + " (" + branchName() + ")");
                updater.performUpdate();
            } else {
                send(s, ChatColor.YELLOW + "Mise à jour disponible" + ChatColor.GRAY + " (" + branchName() + ")");
            }
        });
    }

    private void update(CommandSender s) {
        if (updater.isUpdating()) {
            send(s, ChatColor.YELLOW + "Mise à jour déjà en cours...");
            return;
        }
        send(s, ChatColor.YELLOW + "Téléchargement..." + ChatColor.GRAY + " (" + branchName() + ")");
        updater.performUpdate();
    }

    private void branch(CommandSender s, String[] args) {
        if (args.length < 2) {
            send(s, "Branche actuelle : " + branchName() + ". Changer : " + ChatColor.AQUA + "/gup " + name + " branch <nom>");
            return;
        }
        if (updater.isUpdating()) {
            send(s, ChatColor.YELLOW + "Mise à jour déjà en cours...");
            return;
        }
        updater.switchBranch(args[1]).thenAccept(ok -> {
            if (ok) {
                send(s, "Branche " + ChatColor.AQUA + args[1] + ChatColor.GRAY + ", téléchargement...");
                updater.performUpdate();
            } else {
                send(s, ChatColor.RED + "✗ Branche " + args[1] + " introuvable" + ChatColor.GRAY + " (voir " + ChatColor.AQUA + "/gup " + name + " list" + ChatColor.GRAY + ")");
            }
        });
    }

    private void auto(CommandSender s, String[] args) {
        String action = args.length > 1 ? args[1].toLowerCase() : "";
        switch (action) {
            case "on":
                config.setAutoUpdateEnabled(true);
                updater.startAutoUpdate();
                send(s, "Mise à jour auto " + ChatColor.GREEN + "activée" + ChatColor.GRAY + ", toutes les " + GitUpdater.duration(config.getCheckInterval()));
                break;
            case "off":
                config.setAutoUpdateEnabled(false);
                updater.stopAutoUpdate();
                send(s, "Mise à jour auto " + ChatColor.RED + "désactivée");
                break;
            case "interval":
                try {
                    int seconds = Integer.parseInt(args[2]);
                    if (seconds < 60) {
                        send(s, ChatColor.RED + "✗ Minimum 60 secondes.");
                        return;
                    }
                    config.setCheckInterval(seconds);
                    if (config.isAutoUpdateEnabled()) {
                        updater.startAutoUpdate();
                    }
                    send(s, "Vérification toutes les " + ChatColor.AQUA + GitUpdater.duration(seconds));
                } catch (RuntimeException e) {
                    send(s, ChatColor.RED + "Usage : " + ChatColor.AQUA + "/gup " + name + " auto interval <secondes>");
                }
                break;
            default:
                send(s, "Mise à jour auto " + (config.isAutoUpdateEnabled() ? ChatColor.GREEN + "on" : ChatColor.RED + "off")
                        + ChatColor.GRAY + " (" + GitUpdater.duration(config.getCheckInterval()) + "). "
                        + ChatColor.AQUA + "/gup " + name + " auto <on|off|interval <s>>");
        }
    }

    private void list(CommandSender s) {
        updater.getAvailableBranches().thenAccept(branches -> {
            if (branches.isEmpty()) {
                send(s, ChatColor.RED + "✗ Aucune branche trouvée" + ChatColor.GRAY + " (voir console)");
                return;
            }
            String joined = branches.stream()
                    .map(b -> b.equals(config.getBranch()) ? ChatColor.GREEN + "► " + b + ChatColor.GRAY : ChatColor.WHITE + b + ChatColor.GRAY)
                    .collect(Collectors.joining(", "));
            send(s, "Branches : " + joined);
        });
    }

    private void info(CommandSender s) {
        String commit = config.getLastCommit();
        send(s, "Branche " + branchName()
                + (commit.isEmpty() ? "" : ChatColor.DARK_GRAY + " @ " + ChatColor.AQUA + GitUpdater.shortSha(commit) + ChatColor.GRAY)
                + ", auto " + (config.isAutoUpdateEnabled() ? ChatColor.GREEN + "on" : ChatColor.RED + "off")
                + ChatColor.GRAY + " (" + GitUpdater.duration(config.getCheckInterval()) + ")"
                + (updater.isUpdating() ? ChatColor.YELLOW + ", mise à jour en cours..." : ""));
        send(s, "Dépôt : " + ChatColor.WHITE + updater.getRepoUrl());
    }

    private void token(CommandSender s, String[] args) {
        if (args.length < 2) {
            send(s, "Usage : " + ChatColor.AQUA + "/gup " + name + " token <token>" + ChatColor.GRAY + " (fine-grained, limité au dépôt)");
            return;
        }
        config.setGitHubToken(args[1]);
        send(s, ChatColor.GREEN + "✓ Token enregistré." + ChatColor.GRAY + " Testez : " + ChatColor.AQUA + "/gup " + name + " check");
    }
}
