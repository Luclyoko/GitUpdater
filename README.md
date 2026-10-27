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

L'updater télécharge le jar depuis une release GitHub taguée `auto-<branche>-latest` (format réglable avec `releaseTagFormat`), et vérifie que le workflow du commit est terminé avec succès. Il faut donc un workflow qui construit le jar et met à jour cette release à chaque push, dans `.github/workflows/auto-release.yml` du plugin (basé sur celui d'AigriUHC) :

```yaml
name: Auto Build and Release

# Déclenche le workflow à chaque push sur ces branches
on:
  push:
    branches:
      - main
      - master
      - develop

# Permissions nécessaires pour créer des releases
permissions:
  contents: write

jobs:
  build-and-release:
    name: Build et Release Automatique
    runs-on: ubuntu-latest

    steps:
      - name: Checkout code
        uses: actions/checkout@v4

      - name: Setup JDK 8
        uses: actions/setup-java@v4
        with:
          java-version: '8'
          distribution: 'temurin'
          cache: 'gradle'

      - name: Make gradlew executable
        run: chmod +x gradlew

      - name: Build with Gradle
        run: ./gradlew clean build -x test --no-daemon

      - name: Get build info
        id: info
        run: |
          echo "sha_short=$(git rev-parse --short HEAD)" >> $GITHUB_OUTPUT
          echo "branch=${GITHUB_REF#refs/heads/}" >> $GITHUB_OUTPUT
          echo "date=$(date +'%Y-%m-%d_%H-%M')" >> $GITHUB_OUTPUT

      # Repli de l'updater si la release est absente (token requis)
      - name: Upload Build Artifact
        uses: actions/upload-artifact@v4
        with:
          name: MonPlugin-jar
          path: build/libs/*.jar
          retention-days: 90

      - name: Create/Update Release
        uses: softprops/action-gh-release@v1
        with:
          tag_name: auto-${{ steps.info.outputs.branch }}-latest
          target_commitish: ${{ github.sha }}
          name: "[${{ steps.info.outputs.branch }}] Build ${{ steps.info.outputs.sha_short }}"
          body: |
            Release automatique, branche `${{ steps.info.outputs.branch }}`, commit `${{ steps.info.outputs.sha_short }}`, ${{ steps.info.outputs.date }}.

            Installation sur le serveur : `/gitupdate check` puis `/gitupdate update`.

            ${{ github.event.head_commit.message }}
          files: |
            build/libs/*.jar
          draft: false
          prerelease: false
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
```

Points importants :
- Le tag doit correspondre à `releaseTagFormat` (par défaut `auto-%s-latest`, `%s` = nom de la branche).
- `target_commitish: ${{ github.sha }}` : l'updater compare le commit de la release au dernier commit de la branche et ignore la release s'ils diffèrent (build en cours ou périmé), puis retombe sur la dernière release. Sans cette ligne la release est rattachée au nom de la branche et non au commit.
- Le jar doit être dans `build/libs/`. Les jars `*sources*` et `*javadoc*` sont ignorés par l'updater.
- `permissions: contents: write` est obligatoire pour que `GITHUB_TOKEN` puisse créer la release.
- Adapter `name: MonPlugin-jar` et la liste des branches.

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
