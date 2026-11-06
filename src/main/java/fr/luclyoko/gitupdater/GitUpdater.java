package fr.luclyoko.gitupdater;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Mise à jour automatique d'un plugin depuis les releases / artifacts GitHub.
 * Usage: {@code GitUpdater updater = new GitUpdater(this, new UpdaterOptions(url, "Mon-Plugin_")); updater.start();}
 */
public class GitUpdater {

    /** Updaters démarrés, par nom de plugin, pour la commande /gitupdater <plugin>. */
    static final Map<String, GitUpdater> REGISTRY = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    private final JavaPlugin plugin;
    private final UpdaterOptions options;
    private final UpdaterConfig config;
    private final String apiBase;
    private final String userAgent;
    private BukkitTask updateTask;
    private volatile boolean updating = false;

    public GitUpdater(JavaPlugin plugin, UpdaterOptions options) {
        this.plugin = plugin;
        this.options = options;
        this.config = new UpdaterConfig(plugin, options.defaultBranch);
        this.apiBase = options.repoUrl.replaceFirst("^https://github\\.com/", "https://api.github.com/repos/").replaceFirst("\\.git$", "");
        this.userAgent = options.userAgent != null ? options.userAgent : plugin.getName() + "-GitUpdater";
    }

    /** Démarre la vérification périodique si elle est activée dans la config. */
    public void start() {
        REGISTRY.put(plugin.getName(), this);
        plugin.getLogger().info("GitUpdater sur " + options.repoUrl + " (branche " + config.getBranch() + ")");
        plugin.getLogger().info("Config: " + config.getFile().getAbsolutePath());
        if (config.isAutoUpdateEnabled()) {
            startAutoUpdate();
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, this::diagnose);
    }

    // ---------------------------------------------------------------- diagnostic de setup

    private static class HttpException extends IOException {
        final int code;

        HttpException(int code) {
            super("HTTP " + code);
            this.code = code;
        }
    }

    private static final String README = "https://github.com/Luclyoko/GitUpdater#";

    /** Vérifie le setup et renvoie vers le README à la première anomalie bloquante. */
    private void diagnose() {
        String branch = config.getBranch();

        if (!options.repoUrl.matches("^https://github\\.com/[^/]+/[^/]+?(\\.git)?$")) {
            problem("URL du dépôt invalide: " + options.repoUrl, "url-du-depot");
            return;
        }

        options.installDir.mkdirs();
        if (!options.installDir.isDirectory() || !options.installDir.canWrite()) {
            problem("Dossier d'installation inutilisable: " + options.installDir.getAbsolutePath(), "dossier-dinstallation");
            return;
        }

        try {
            api("/branches/" + branch);
        } catch (HttpException e) {
            switch (e.code) {
                case 404:
                    problem("Dépôt ou branche '" + branch + "' introuvable (HTTP 404)" + (config.hasGitHubToken() ? "" : ", aucun token configuré"), "depot-prive-et-token");
                    break;
                case 401:
                    problem("Token GitHub invalide ou expiré (HTTP 401)", "depot-prive-et-token");
                    break;
                case 403:
                    problem("Accès refusé par GitHub (HTTP 403), limite de requêtes sans token ?", "depot-prive-et-token");
                    break;
                default:
                    plugin.getLogger().warning("[GitUpdater] Vérification du setup impossible: " + e.getMessage());
            }
            return;
        } catch (IOException e) {
            plugin.getLogger().warning("[GitUpdater] Vérification du setup impossible (réseau ?): " + e.getMessage());
            return;
        }

        String tag = String.format(options.releaseTagFormat, branch);
        try {
            api("/releases/tags/" + tag);
        } catch (HttpException e) {
            if (e.code == 404) {
                problem("Aucune release '" + tag + "' sur le dépôt", "release-par-branche");
                return;
            }
        } catch (IOException ignored) {
            return;
        }

        plugin.getLogger().info("[GitUpdater] Setup OK" + (config.isAutoUpdateEnabled() ? "." : ", mise à jour auto désactivée: /gup " + plugin.getName() + " auto on"));
    }

    private void problem(String message, String anchor) {
        plugin.getLogger().warning("[GitUpdater] Setup incomplet: " + message);
        plugin.getLogger().warning("[GitUpdater] Tutoriel: " + README + anchor);
    }

    // ---------------------------------------------------------------- messages ingame

    /** {@code [Plugin] } en tête de chaque message, texte en gris par défaut. */
    static String prefix(String pluginName) {
        return ChatColor.DARK_GRAY + "[" + ChatColor.GOLD + pluginName + ChatColor.DARK_GRAY + "] " + ChatColor.GRAY;
    }

    static String shortSha(String sha) {
        return sha.substring(0, Math.min(7, sha.length()));
    }

    /** 300 -> "5 min", 3600 -> "1 h", 90 -> "90 s". */
    static String duration(int seconds) {
        if (seconds % 3600 == 0) return seconds / 3600 + " h";
        if (seconds % 60 == 0) return seconds / 60 + " min";
        return seconds + " s";
    }

    /** Message aux seuls admins (gitupdater.admin), pas aux joueurs. */
    private void notifyAdmins(String message) {
        String line = prefix(plugin.getName()) + message;
        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.broadcast(line, "gitupdater.admin"));
    }

    JavaPlugin getPlugin() {
        return plugin;
    }

    public UpdaterConfig getConfig() {
        return config;
    }

    public String getRepoUrl() {
        return options.repoUrl;
    }

    public boolean isUpdating() {
        return updating;
    }

    public void startAutoUpdate() {
        stopAutoUpdate();
        updateTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () ->
                checkForUpdates().thenAccept(hasUpdate -> {
                    if (hasUpdate && !updating) {
                        plugin.getLogger().info("Nouvelle mise à jour détectée sur GitHub !");
                        notifyAdmins(ChatColor.YELLOW + "Nouvelle version détectée, téléchargement...");
                        Bukkit.getScheduler().runTaskLater(plugin, this::performUpdate, 100L);
                    }
                }), 200L, config.getCheckInterval() * 20L);
    }

    public void stopAutoUpdate() {
        if (updateTask != null) {
            updateTask.cancel();
            updateTask = null;
        }
    }

    public CompletableFuture<Boolean> checkForUpdates() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String remote = getLatestCommit(config.getBranch());
                if (remote == null || remote.equals(config.getLastCommit())) {
                    return false;
                }
                plugin.getLogger().info("Mise à jour disponible: " + remote.substring(0, 8));
                return isBuildComplete(remote);
            } catch (Exception e) {
                plugin.getLogger().warning("Erreur vérification: " + e.getMessage());
                return false;
            }
        });
    }

    public CompletableFuture<Boolean> switchBranch(String branch) {
        return CompletableFuture.supplyAsync(() -> {
            String commit = getLatestCommit(branch);
            if (commit == null) {
                return false;
            }
            // lastCommit n'est mis à jour qu'après une installation réussie (performUpdate)
            config.setBranch(branch);
            return true;
        });
    }

    public CompletableFuture<List<String>> getAvailableBranches() {
        return CompletableFuture.supplyAsync(() -> {
            List<String> branches = new ArrayList<>();
            try {
                for (com.google.gson.JsonElement e : new JsonParser().parse(api("/branches")).getAsJsonArray()) {
                    branches.add(e.getAsJsonObject().get("name").getAsString());
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Erreur récupération branches: " + e.getMessage());
            }
            return branches;
        });
    }

    public void performUpdate() {
        if (updating) {
            return;
        }
        updating = true;

        CompletableFuture.runAsync(() -> {
            try {
                String branch = config.getBranch();
                String commit = getLatestCommit(branch);

                if (commit != null && !waitForBuild(commit)) {
                    plugin.getLogger().warning("Le build n'est pas terminé ou a échoué");
                    notifyAdmins(ChatColor.RED + "Build GitHub pas prêt ou échoué, réessayez plus tard.");
                    return;
                }

                // Ordre : release immuable du build de ce commit, puis release auto-<branche>-latest, puis artifacts
                File jar = commit == null ? null : downloadFromBuildRelease(branch, commit);
                if (jar == null) jar = downloadFromBranchRelease(branch, commit);
                if (jar == null) jar = downloadFromActions(branch, commit);
                if (jar == null) throw new IOException("Impossible de récupérer le JAR");

                installJar(jar, branch);
                if (commit != null) {
                    config.setLastCommit(commit);
                }

                notifyAdmins(ChatColor.GREEN + "✓ Mis à jour " + ChatColor.GRAY + "(" + ChatColor.AQUA + branch
                        + (commit != null ? ChatColor.DARK_GRAY + " @ " + ChatColor.AQUA + shortSha(commit) : "")
                        + ChatColor.GRAY + "), redémarrez le serveur pour l'appliquer.");
            } catch (Exception e) {
                plugin.getLogger().severe("Erreur mise à jour: " + e.getMessage());
                e.printStackTrace();
                notifyAdmins(ChatColor.RED + "✗ Échec de la mise à jour: " + e.getMessage() + ChatColor.GRAY + " (détails en console)");
            } finally {
                updating = false;
            }
        });
    }

    // ---------------------------------------------------------------- téléchargement

    /**
     * Release créée pour ce build précis (tag {@code <branche>-build-<n>}, cible = le sha), jamais réécrite :
     * son jar correspond forcément au commit, contrairement à auto-<branche>-latest qui est remplacée à chaque push.
     */
    private File downloadFromBuildRelease(String branch, String commit) {
        String autoTag = String.format(options.releaseTagFormat, branch);
        try {
            for (com.google.gson.JsonElement e : new JsonParser().parse(api("/releases?per_page=50")).getAsJsonArray()) {
                JsonObject release = e.getAsJsonObject();
                if (!release.get("draft").getAsBoolean()
                        && commit.equals(release.get("target_commitish").getAsString())
                        && !autoTag.equals(release.get("tag_name").getAsString())) {
                    File jar = downloadJarAsset(release);
                    if (jar != null) return jar;
                }
            }
        } catch (Exception e) {
            plugin.getLogger().info("Pas de release de build pour " + shortSha(commit) + ": " + e.getMessage());
        }
        return null;
    }

    private File downloadFromBranchRelease(String branch, String commit) {
        try {
            JsonObject release = new JsonParser().parse(api("/releases/tags/" + String.format(options.releaseTagFormat, branch))).getAsJsonObject();

            String target = release.get("target_commitish").getAsString();
            if (commit != null && !target.equals(commit)) {
                plugin.getLogger().warning("La release ne correspond pas au dernier commit, build probablement en cours");
                return null;
            }
            return downloadJarAsset(release);
        } catch (Exception e) {
            plugin.getLogger().info("Pas de release pour la branche " + branch + ": " + e.getMessage());
            return null;
        }
    }

    private File downloadJarAsset(JsonObject release) throws IOException {
        for (com.google.gson.JsonElement e : release.getAsJsonArray("assets")) {
            JsonObject asset = e.getAsJsonObject();
            String name = asset.get("name").getAsString();
            if (name.endsWith(".jar") && !name.contains("sources") && !name.contains("javadoc")) {
                plugin.getLogger().info("Release trouvée: " + name);
                return download(asset.get("browser_download_url").getAsString());
            }
        }
        return null;
    }

    /** Les artifacts Actions sont des ZIP et exigent un token. Liste triée du plus récent au plus ancien. */
    private File downloadFromActions(String branch, String commit) {
        try {
            JsonArray artifacts = new JsonParser().parse(api("/actions/artifacts")).getAsJsonObject().getAsJsonArray("artifacts");
            for (com.google.gson.JsonElement e : artifacts) {
                JsonObject artifact = e.getAsJsonObject();
                String name = artifact.get("name").getAsString();
                JsonObject run = artifact.getAsJsonObject("workflow_run");
                // Avec le commit connu, on exige l'artifact de ce commit précis, sinon celui de la branche
                String field = commit != null ? "head_sha" : "head_branch";
                String expected = commit != null ? commit : branch;
                boolean matches = run != null && run.has(field) && !run.get(field).isJsonNull()
                        && expected.equals(run.get(field).getAsString());
                if (matches &&(name.contains("jar") || name.contains("build"))) {
                    File zip = download(artifact.get("archive_download_url").getAsString());
                    return zip == null ? null : extractJar(zip);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().info("Erreur GitHub Actions: " + e.getMessage());
        }
        return null;
    }

    private File download(String urlString) throws IOException {
        HttpURLConnection conn = open(urlString, 60000);
        if (conn.getResponseCode() != 200) {
            plugin.getLogger().warning("HTTP " + conn.getResponseCode() + " sur " + urlString);
            return null;
        }
        File tmp = File.createTempFile("gitupdater-", ".tmp");
        try (InputStream in = conn.getInputStream()) {
            Files.copy(in, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        return tmp;
    }

    private File extractJar(File zipFile) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().endsWith(".jar") && !entry.getName().contains("sources")) {
                    File jar = File.createTempFile("gitupdater-", ".jar");
                    Files.copy(zis, jar.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    return jar;
                }
            }
            return null;
        } finally {
            zipFile.delete();
        }
    }

    private void installJar(File jar, String branch) throws IOException {
        File dir = options.installDir;
        dir.mkdirs();

        File[] old = dir.listFiles((d, name) -> name.startsWith(options.jarPrefix) && name.endsWith(".jar"));
        if (old != null) {
            for (File f : old) {
                if (!f.delete()) {
                    plugin.getLogger().warning("Impossible de supprimer: " + f.getName());
                }
            }
        }

        File target = new File(dir, options.jarPrefix + branch.replace("/", "_").replace("\\", "_") + ".jar");
        Files.copy(jar.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        jar.delete();
        plugin.getLogger().info("JAR installé: " + target.getPath());
    }

    // ---------------------------------------------------------------- API GitHub

    private HttpURLConnection open(String urlString, int readTimeout) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlString).openConnection();
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
        conn.setRequestProperty("User-Agent", userAgent);
        if (config.hasGitHubToken()) {
            conn.setRequestProperty("Authorization", "token " + config.getGitHubToken());
        }
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(readTimeout);
        return conn;
    }

    private String api(String endpoint) throws IOException {
        HttpURLConnection conn = open(apiBase + endpoint, 10000);
        int code = conn.getResponseCode();
        if (code != 200) {
            throw new HttpException(code);
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }

    private String getLatestCommit(String branch) {
        try {
            return new JsonParser().parse(api("/branches/" + branch)).getAsJsonObject()
                    .getAsJsonObject("commit").get("sha").getAsString();
        } catch (Exception e) {
            plugin.getLogger().warning("Erreur récupération commit: " + e.getMessage());
            return null;
        }
    }

    /** Le workflow Actions de ce commit doit être terminé avec succès. */
    private boolean isBuildComplete(String sha) {
        try {
            // Tous les workflows de ce commit doivent être terminés : avant, le premier trouvé suffisait,
            // et un autre workflow fini plus tôt laissait passer pendant que la release était encore en cours d'écriture.
            JsonArray runs = new JsonParser().parse(api("/actions/runs?head_sha=" + sha)).getAsJsonObject().getAsJsonArray("workflow_runs");
            boolean anySuccess = false;
            for (com.google.gson.JsonElement e : runs) {
                JsonObject run = e.getAsJsonObject();
                if (!"completed".equals(run.get("status").getAsString())) {
                    return false;
                }
                anySuccess |= !run.get("conclusion").isJsonNull() && "success".equals(run.get("conclusion").getAsString());
            }
            return anySuccess;
        } catch (Exception e) {
            plugin.getLogger().warning("Erreur vérification build: " + e.getMessage());
        }
        return false;
    }

    /** Attend jusqu'à 10 minutes. */
    private boolean waitForBuild(String sha) {
        for (int i = 0; i < 60; i++) {
            if (isBuildComplete(sha)) {
                return true;
            }
            try {
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }
}
