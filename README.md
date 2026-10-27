# GitUpdater

Plugin Bukkit 1.8.8 (et lib) qui met à jour d'autres plugins depuis les releases GitHub : `/gitupdate check|update|branch|list|auto|info|token`.

## Installation sur le serveur

Copier `GitUpdater-<version>.jar` (`./gradlew build`, jar dans `build/libs/`) dans `plugins/` du serveur.

## Utilisation dans un plugin

`build.gradle` :

```gradle
repositories { maven { url 'https://jitpack.io' } }
dependencies { compileOnly 'com.github.Luclyoko:GitUpdater:1.0.0' }  // 1.0.0 = tag git
```

`plugin.yml` : `depend: [GitUpdater]`

```java
GitUpdater updater = new GitUpdater(this, new UpdaterOptions("https://github.com/user/repo", "MonPlugin_")
        .installDir(new File("plugins/SupUHC/modules")));   // optionnel, défaut: plugins/
updater.start();

// commande : relayer vers UpdaterCommand
UpdaterCommand cmd = new UpdaterCommand(updater);
cmd.handle(sender, args);          // exécution
cmd.complete(sender, args);        // tab completion
```

Options (`UpdaterOptions`) : `installDir`, `defaultBranch` (`master`), `releaseTagFormat` (`auto-%s-latest`), `userAgent`, `commandName` (`gitupdate`, affiché dans les messages).

La config de chaque plugin est dans `<dataFolder du plugin>/git-updater.yml` (branche, auto-update, intervalle en secondes, dernier commit, token).

Au démarrage, `start()` vérifie le setup et la console renvoie vers la section ci-dessous correspondant au problème.

## Résolution de problèmes

### URL du depot

L'URL passée à `UpdaterOptions` doit être de la forme `https://github.com/<utilisateur>/<depot>`. Corriger dans le code du plugin.

### Dossier d'installation

Le dossier de `.installDir(...)` doit exister (ou être créable) et être accessible en écriture par le serveur. Le jar installé s'appelle `<prefixe><branche>.jar`, les anciens jars avec ce préfixe sont supprimés.

### Depot prive et token

Erreur 404 / 401 / 403 : la branche n'existe pas, le dépôt est privé, le token est invalide ou la limite de 60 requêtes/h sans token est atteinte.

1. Vérifier la branche : `/gitupdate list`, puis `/gitupdate branch <nom>`.
2. Pour un dépôt privé : https://github.com/settings/tokens > *Generate new token (classic)* > cocher `repo`.
3. Donner le token : `/gitupdate token <token>` ou `github-token` dans `git-updater.yml`.
4. Tester : `/gitupdate check`.

### Release par branche

L'updater cherche d'abord la release taguée `auto-<branche>-latest` (format réglable avec `releaseTagFormat`) contenant le jar. Le workflow GitHub Actions du projet doit la publier à chaque push (voir `.github/workflows/auto-release.yml` d'AigriUHC). Sans elle l'updater retombe sur la dernière release, puis sur les artifacts Actions (token requis), mais le setup reste incomplet.
