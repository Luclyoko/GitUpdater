# GitUpdater

Plugin Bukkit 1.8.8 (et lib) qui met à jour d'autres plugins depuis les releases GitHub : `/gitupdate check|update|branch|list|auto|info|token`.

## Installation sur le serveur

Copier `GitUpdater-<version>.jar` (`./gradlew build`, jar dans `build/libs/`) dans `plugins/` du serveur.

## Utilisation dans un plugin

`build.gradle` :

```gradle
repositories { maven { url 'https://jitpack.io' } }
dependencies { compileOnly 'com.github.Luclyoko:GitUpdater:1.0.2' }  // 1.0.0 = tag git
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

La config de chaque plugin est dans `plugins/GitUpdater/<nom du plugin>.yml` (le `name` du `plugin.yml`), tout au même endroit : branche, auto-update, intervalle en secondes, dernier commit, token.

Au démarrage, `start()` vérifie le setup et la console renvoie vers la section ci-dessous correspondant au problème.

## Configuration GitHub Actions

L'updater télécharge le jar depuis une release GitHub taguée `auto-<branche>-latest` (format réglable avec `releaseTagFormat`), et vérifie que le workflow du commit est terminé avec succès. Il faut donc un workflow qui construit le jar et recrée cette release à chaque push, dans `.github/workflows/auto-release.yml` du plugin :

```yaml
name: Auto Build and Release

on:
  push:
    branches: [main, master, develop]   # les branches à déployer

permissions:
  contents: write   # créer la release

jobs:
  build-and-release:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          java-version: '8'
          distribution: temurin
          cache: gradle

      - run: chmod +x gradlew
      - run: ./gradlew clean build -x test --no-daemon

      - name: Recréer la release de la branche
        env:
          GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        run: |
          TAG="auto-${GITHUB_REF_NAME}-latest"
          gh release delete "$TAG" --cleanup-tag --yes || true
          gh release create "$TAG" build/libs/*.jar             --target "$GITHUB_SHA"             --title "[${GITHUB_REF_NAME}] Build ${GITHUB_SHA::7}"             --notes "Build automatique du commit ${GITHUB_SHA}"
```

Points importants :
- La release est **supprimée puis recréée** avec `--target "$GITHUB_SHA"` : l'updater compare le commit de la release au dernier commit de la branche et ignore la release s'ils diffèrent (build en cours ou périmé). Mettre à jour une release existante ne déplace pas son commit.
- Le tag doit correspondre à `releaseTagFormat` (par défaut `auto-%s-latest`, `%s` = nom de la branche).
- Le jar doit être dans `build/libs/`. Les jars `*sources*` et `*javadoc*` sont ignorés par l'updater.
- `permissions: contents: write` est obligatoire pour que `GITHUB_TOKEN` puisse créer la release.
- Optionnel, pour le repli sur les artifacts (token requis) : ajouter un step `actions/upload-artifact@v4` avec `name: <plugin>-jar` et `path: build/libs/*.jar`.

## Résolution de problèmes

### URL du depot

L'URL passée à `UpdaterOptions` doit être de la forme `https://github.com/<utilisateur>/<depot>`. Corriger dans le code du plugin.

### Dossier d'installation

Le dossier de `.installDir(...)` doit exister (ou être créable) et être accessible en écriture par le serveur. Le jar installé s'appelle `<prefixe><branche>.jar`, les anciens jars avec ce préfixe sont supprimés.

### Depot prive et token

Erreur 404 / 401 / 403 : la branche n'existe pas, le dépôt est privé, le token est invalide ou la limite de 60 requêtes/h sans token est atteinte.

1. Vérifier la branche : `/gitupdate list`, puis `/gitupdate branch <nom>`.
2. Pour un dépôt privé, créer un token **limité à ce seul dépôt** (fine-grained) :
   https://github.com/settings/personal-access-tokens/new
   - *Repository access* > **Only select repositories** > choisir uniquement le dépôt du plugin.
   - *Repository permissions* : **Contents: Read-only** (releases et branches) et **Actions: Read-only** (état du build et artifacts). *Metadata: Read-only* est ajouté automatiquement.
   - Ne pas utiliser un token *classic* avec le scope `repo` : il donne accès à tous tes dépôts.
3. Copier le token (`github_pat_...`) puis le donner : `/gitupdate token <token>` ou `github-token` dans `plugins/GitUpdater/<nom du plugin>.yml`.
4. Tester : `/gitupdate check`.

### Release par branche

Aucune release `auto-<branche>-latest` n'a été trouvée : le workflow décrit dans [Configuration GitHub Actions](#configuration-github-actions) n'est pas en place, n'a pas encore tourné pour cette branche, ou a échoué. Sans elle l'updater retombe sur la dernière release, puis sur les artifacts Actions (token requis), mais le setup reste incomplet.
