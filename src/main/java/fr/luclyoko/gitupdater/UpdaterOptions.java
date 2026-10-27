package fr.luclyoko.gitupdater;

import java.io.File;

/**
 * Paramètres propres à chaque projet.
 */
public class UpdaterOptions {

    final String repoUrl;
    File installDir = new File("plugins");
    String jarPrefix;
    String defaultBranch = "master";
    String releaseTagFormat = "auto-%s-latest";
    String userAgent;
    String commandName = "gitupdate";

    /**
     * @param repoUrl   https://github.com/user/repo
     * @param jarPrefix préfixe du jar installé, le nom final est {@code jarPrefix + branche + ".jar"}
     *                  (les anciens jars avec ce préfixe sont supprimés à l'installation)
     */
    public UpdaterOptions(String repoUrl, String jarPrefix) {
        this.repoUrl = repoUrl;
        this.jarPrefix = jarPrefix;
    }

    /** Dossier d'installation du jar (ex: plugins/SupUHC/modules). Défaut: plugins */
    public UpdaterOptions installDir(File installDir) {
        this.installDir = installDir;
        return this;
    }

    public UpdaterOptions defaultBranch(String defaultBranch) {
        this.defaultBranch = defaultBranch;
        return this;
    }

    /** Format du tag de release par branche, %s = branche. Défaut: auto-%s-latest */
    public UpdaterOptions releaseTagFormat(String releaseTagFormat) {
        this.releaseTagFormat = releaseTagFormat;
        return this;
    }

    public UpdaterOptions userAgent(String userAgent) {
        this.userAgent = userAgent;
        return this;
    }

    /** Nom de la commande d'admin, utilisé dans le tutoriel de setup. Défaut: gitupdate */
    public UpdaterOptions commandName(String commandName) {
        this.commandName = commandName;
        return this;
    }
}
