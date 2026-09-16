# Modrinth releases

The `Publish to Modrinth` workflow uploads the existing GitHub release assets to
the [The New Economy fork](https://modrinth.com/plugin/the-new-economy).
It does not rebuild or publish on ordinary pushes or pull requests.

1. Create a draft GitHub release with a version tag such as `0.1.5.1`.
2. Attach `TNE-BukkitCore-<version>.jar`, `TNE-Paper-<version>.jar`,
   `TNE-Folia-<version>.jar`, and `TNE-Sponge8Core-<version>.jar`.
3. Write the release notes and publish the GitHub release.

Each platform gets a separate Modrinth version. Prereleases use the beta channel.
BukkitEarly is omitted, matching the existing Modrinth release layout.
Compatibility labels in `modrinth.json` are copied from the existing 0.1.5.0
Modrinth entries; they are not a claim of newly tested compatibility. Update
these explicit lists when the supported versions change.

The repository Actions secret `MODRINTH_TOKEN` must contain a token with permission
to create versions on this project. Never put the token in this repository.

For validation or retries, run the workflow manually with an existing release tag.
Dry run is enabled by default. Disable it to publish. Identical files already on
Modrinth are skipped; conflicting releases fail instead of being overwritten.
Missing assets fail validation before any upload. If a network error interrupts
publishing, rerun the workflow to finish the remaining platforms.

Local validation requires Node.js 22 or newer and authenticated GitHub CLI:

```powershell
$env:RELEASE_TAG = '0.1.5.0'
$env:DRY_RUN = 'true'
node .github/scripts/publish-modrinth.mjs
```
