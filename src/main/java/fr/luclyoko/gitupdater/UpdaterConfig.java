package fr.luclyoko.gitupdater;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;

/**
 * Configuration persistée dans {@code <dataFolder>/git-updater.yml}.
 */
public class UpdaterConfig {

    private static final int DEFAULT_CHECK_INTERVAL = 300;

    private final JavaPlugin plugin;
    private final File file;
    private final YamlConfiguration config;

    UpdaterConfig(JavaPlugin plugin, String defaultBranch) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "git-updater.yml");
        file.getParentFile().mkdirs();
        this.config = YamlConfiguration.loadConfiguration(file);

        config.addDefault("branch", defaultBranch);
        config.addDefault("auto-update", false);
        config.addDefault("check-interval", DEFAULT_CHECK_INTERVAL);
        config.addDefault("last-commit", "");
        config.addDefault("github-token", "");
        config.options().copyDefaults(true);
        save();
    }

    private void save() {
        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Impossible de sauvegarder git-updater.yml: " + e.getMessage());
        }
    }

    private void set(String key, Object value) {
        config.set(key, value);
        save();
    }

    public String getBranch() { return config.getString("branch"); }
    public void setBranch(String branch) { set("branch", branch); }

    public boolean isAutoUpdateEnabled() { return config.getBoolean("auto-update"); }
    public void setAutoUpdateEnabled(boolean enabled) { set("auto-update", enabled); }

    /** En secondes */
    public int getCheckInterval() { return config.getInt("check-interval"); }
    public void setCheckInterval(int seconds) { set("check-interval", seconds); }

    public String getLastCommit() { return config.getString("last-commit"); }
    public void setLastCommit(String sha) { set("last-commit", sha); }

    public String getGitHubToken() { return config.getString("github-token"); }
    public void setGitHubToken(String token) { set("github-token", token); }
    public boolean hasGitHubToken() { return !getGitHubToken().isEmpty(); }
}
