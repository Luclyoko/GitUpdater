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
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Mise à jour automatique d'un plugin depuis les releases / artifacts GitHub.
 * Usage: {@code GitUpdater updater = new GitUpdater(this, new UpdaterOptions(url, "Mon-Plugin_")); updater.start();}
 */
public class GitUpdater {

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
        plugin.getLogger().info("GitUpdater sur " + options.repoUrl + " (branche " + config.getBranch() + ")");
        if (config.isAutoUpdateEnabled()) {
            startAutoUpdate();
        }
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
                        Bukkit.broadcastMessage(ChatColor.YELLOW + "[GitUpdater] Nouvelle mise à jour détectée ! Téléchargement en cours...");
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
            config.setBranch(branch);
            config.setLastCommit(commit);
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
                    Bukkit.getScheduler().runTask(plugin, () ->
                            Bukkit.broadcastMessage(ChatColor.RED + "[GitUpdater] Le build n'est pas prêt, réessayez plus tard"));
                    return;
                }

                File jar = downloadFromBranchRelease(branch);
                if (jar == null) jar = downloadLatestReleaseJar();
                if (jar == null) jar = downloadFromActions();
                if (jar == null) throw new IOException("Impossible de récupérer le JAR");

                installJar(jar, branch);
                if (commit != null) {
                    config.setLastCommit(commit);
                }

                Bukkit.getScheduler().runTask(plugin, () -> {
                    Bukkit.broadcastMessage(ChatColor.GREEN + "═══════════════════════════════════════════");
                    Bukkit.broadcastMessage(ChatColor.GOLD + "    [GitUpdater] Mise à jour téléchargée");
                    Bukkit.broadcastMessage(ChatColor.YELLOW + "Le serveur doit être redémarré pour appliquer.");
                    Bukkit.broadcastMessage(ChatColor.GREEN + "═══════════════════════════════════════════");
                    Bukkit.getOnlinePlayers().stream().filter(p -> p.isOp()).forEach(p -> {
                        p.sendMessage(ChatColor.AQUA + "Branche: " + ChatColor.WHITE + branch);
                        p.sendMessage(ChatColor.YELLOW + "Utilisez /stop pour redémarrer");
                    });
                });
            } catch (Exception e) {
                plugin.getLogger().severe("Erreur mise à jour: " + e.getMessage());
                e.printStackTrace();
            } finally {
                updating = false;
            }
        });
    }

    // ---------------------------------------------------------------- téléchargement

    private File downloadFromBranchRelease(String branch) {
        try {
            JsonObject release = new JsonParser().parse(api("/releases/tags/" + String.format(options.releaseTagFormat, branch))).getAsJsonObject();

            String target = release.get("target_commitish").getAsString();
            String latest = getLatestCommit(branch);
            if (latest != null && !target.equals(latest)) {
                plugin.getLogger().warning("La release ne correspond pas au dernier commit, build probablement en cours");
                return null;
            }
            return downloadJarAsset(release);
        } catch (Exception e) {
            plugin.getLogger().info("Pas de release pour la branche " + branch + ": " + e.getMessage());
            return null;
        }
    }

    private File downloadLatestReleaseJar() {
        try {
            return downloadJarAsset(new JsonParser().parse(api("/releases/latest")).getAsJsonObject());
        } catch (Exception e) {
            plugin.getLogger().info("Pas de release disponible: " + e.getMessage());
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

    /** Les artifacts Actions sont des ZIP et exigent un token. */
    private File downloadFromActions() {
        try {
            JsonArray artifacts = new JsonParser().parse(api("/actions/artifacts")).getAsJsonObject().getAsJsonArray("artifacts");
            for (com.google.gson.JsonElement e : artifacts) {
                JsonObject artifact = e.getAsJsonObject();
                String name = artifact.get("name").getAsString();
                if (name.contains("jar") || name.contains("build")) {
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
        if (code == 404 && !config.hasGitHubToken()) {
            plugin.getLogger().severe("HTTP 404 sur " + apiBase + endpoint + " - dépôt privé ? Configurez un token (scope 'repo') avec /<commande> token <token>");
        }
        if (code != 200) {
            throw new IOException("HTTP " + code);
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
            JsonArray runs = new JsonParser().parse(api("/actions/runs?per_page=10")).getAsJsonObject().getAsJsonArray("workflow_runs");
            for (com.google.gson.JsonElement e : runs) {
                JsonObject run = e.getAsJsonObject();
                if (run.get("head_sha").getAsString().equals(sha)) {
                    return "completed".equals(run.get("status").getAsString())
                            && !run.get("conclusion").isJsonNull()
                            && "success".equals(run.get("conclusion").getAsString());
                }
            }
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
