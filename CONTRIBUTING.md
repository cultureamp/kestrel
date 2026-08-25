# Contributing

We're still figuring out how this will look but for now if you need support, please raise an issue and we will get back
to you within a few days.

## Developing

To compile and run tests

`./gradlew test`

Make sure to increase the `base_version` number in `gradle.properties`. That bump is what triggers a
release when your PR is merged - see [Releasing](#releasing).

### Publishing to maven local

If you need to test your version with another project in your development environment, you can publish it to maven local

`SKIP_SIGNING=true ./gradlew publishToMavenLocal`

## Releasing

Releases are automatic. The `.github/workflows/release.yaml` workflow watches pushes to `master`, and when
`base_version` in `gradle.properties` has increased it builds, tests, signs and publishes the artefacts to
Maven Central, then tags the commit `v<version>` and creates a GitHub release.

So to cut a release:

1. Bump `base_version` in `gradle.properties` (semantic versioning).
2. Add a `# <version>` section to `CHANGELOG.md` if there are breaking changes. The workflow uses that section
   as the GitHub release notes.
3. Merge to `master`.

The artefacts typically appear on Maven Central within 15-30 minutes. A push that doesn't change
`base_version` is a no-op, and a version already present on Maven Central is never re-published, so the
workflow is safe to re-run.

You can verify publication at:
- **Central Portal**: https://central.sonatype.com/artifact/com.cultureamp/kestrel
- **Maven Central Search**: https://search.maven.org/artifact/com.cultureamp/kestrel

### First-time setup

Run this once, from a repo admin account:

```bash
bin/setup_release_secrets --dry-run   # check everything, change nothing
bin/setup_release_secrets             # apply
```

It creates the `maven-central` environment, restricts it to `master`, loads the four secrets,
publishes your signing key to a keyserver, requires a reviewed pull request on `master`, and
finishes with a test signature through the same code path CI uses. Secret values are never
printed and never written to disk. It is safe to re-run.

By default it signs with the key named by `signing.gnupg.keyName` in `~/.gradle/gradle.properties`.
Prefer a key that isn't tied to one person:

```bash
bin/setup_release_secrets --new-key team_pathfinder@cultureamp.com
```

That generates a dedicated release-signing key owned by the team (`team_pathfinder@cultureamp.com`),
so releases don't depend on one engineer's personal key remaining valid and a leak doesn't force
anyone to revoke their own identity key. Save the passphrase in 1Password before you run it - it
cannot be recovered.

### Secrets and access control

Kestrel is a **public** repository, and these credentials can publish signed artefacts under
`com.cultureamp` to an immutable registry. Treat them accordingly.

The release job needs four secrets, attached to the `maven-central` GitHub Environment rather than
to the repository, so they can only be read by a job running on `master`. Values come from
1Password, Team Develop > Kestrel Sonatype Credentials.

| Secret | Value |
| --- | --- |
| `CENTRAL_TOKEN_USERNAME` | Central Portal token username |
| `CENTRAL_TOKEN_PASSWORD` | Central Portal token password |
| `GPG_SIGNING_KEY` | ASCII-armoured private key: `gpg --armor --export-secret-keys <keyid>` |
| `GPG_SIGNING_PASSPHRASE` | Passphrase for that key |

The signing key must be published to a keyserver for Maven Central to accept it - see
[GPG Key](#3-gpg-key) below. A leaked Central Portal token can simply be regenerated; a leaked
signing key has to be revoked on the keyservers and replaced, so it is the more sensitive of the two.

Two controls do the real work here, and neither lives in the workflow file:

- **Branch protection on `master`**, requiring a pull request with an approving review. The release
  job only ever runs on merged code, and GitHub never gives secrets to a pull request from a fork,
  so outsiders cannot reach these credentials. Anyone who can merge to `master`, however, can write
  a workflow step that exfiltrates them - so merge review *is* the security boundary. Merging is
  restricted to write access, so only Culture Ampers can trigger a release.
- **A deployment branch rule on the `maven-central` environment limited to `master`**, so the
  secrets cannot be read by a job on any other branch even if a future workflow references the
  environment.

Required reviewers on the environment are deliberately *not* used: the pull request review is
already an approval of the same change by the same people, so a second gate would only delay
releases.

The workflow itself runs with `permissions: contents: read` by default, grants `contents: write`
only to the release job for the tag it pushes, and pins every third-party action to a commit SHA.

## Publishing manually

You shouldn't normally need this; it's here for when the workflow is unavailable. If you're publishing to
Maven Central by hand you'll need access to the Central Portal credentials (details below).

**Note: Sonatype has migrated from OSSRH to Central Portal. The old OSSRH system is deprecated.**

Before you publish for the first time you will need to do some set up:
1. Set up Central Portal Credentials
2. Install GPG (if not already done)
3. A GPG key set up
4. Gradle configured with your GPG & Central Portal credentials

Then you can push the artefacts directly to Maven Central via the Central Portal


### 1. Central Portal Credentials
To access the Central Portal you need credentials from 1Password, Team Develop > Kestrel Sonatype Credentials

```bash
export CENTRAL_TOKEN_USERNAME="lQBcTi"
export CENTRAL_TOKEN_PASSWORD="<from 1password>"
```

### 2. Installing GPG
Command line: `brew install gpg2`

GUI: https://gpgtools.org/

### 3. GPG Key
For a bit more detail, see: https://central.sonatype.org/publish/requirements/gpg/

For a gui approach see https://www.albertgao.xyz/2018/01/18/how-to-publish-artifact-to-maven-central-via-gradle/#3-Get-the-GPG-key

Command Line:
1. Generate a key: `gpg --gen-key` - this will ask you for your name and email, and prompt you for a passphrase
2. Inspect the key: `gpg -K`. This will print out something like this
```
/Users/david.wheeler/.gnupg/pubring.kbx
---------------------------------------
sec   ed25519 2022-06-28 [SC] [expires: 2024-06-27]
      01383415B252342AA9423A16B82E212AAA803DA2
uid           [ultimate] David Wheeler <david.wheeler@cultureamp.com>
ssb   cv25519 2022-06-28 [E] [expires: 2024-06-27]
```
3. Publish the key to the internet: `gpg --keyserver keys.openpgp.org --send-keys <keyid>` using the big string of hex chars as `<keyid>` (eg `01383415B252342AA9423A16B82E212AAA803DA2` for the key above)

### 4. Configure Gradle
Create a file `~/.gradle/gradle.properties` if it doesn't already exist. Inside this file, add the lines
```properties
signing.gnupg.passphrase=<gpg_passphrase>
signing.gnupg.keyName=<gpg_key_name>
```
where `<gpg_passphrase>` is the passphrase you set up for the key, and `<gpg_key_name>` is the last 8 digits of the key id (eg `AA803DA2` in the example above)

### 5. Publishing to Maven Central
The build now uses the Gradle Nexus Publish Plugin which handles the complete publishing workflow automatically.

To publish a new version:

```bash
./gradlew publishToSonatype closeAndReleaseSonatypeStagingRepository
```

This single command will:
1. Build and sign the artifacts
2. Upload to Central Portal staging repository
3. Automatically close and release the staging repository
4. Publish directly to Maven Central

**No manual staging repository management is required anymore.**

The artifacts typically appear on Maven Central within 15-30 minutes after the build completes successfully.

You can verify publication at:
- **Central Portal**: https://central.sonatype.com/artifact/com.cultureamp/kestrel
- **Maven Central Search**: https://search.maven.org/artifact/com.cultureamp/kestrel

# Links

**Current (Central Portal):**
- https://central.sonatype.org/publish/requirements/
- https://central.sonatype.org/publish/publish-portal-gradle/
- https://github.com/gradle-nexus/publish-plugin
- https://central.sonatype.org/pages/working-with-pgp-signatures.html
- https://docs.gradle.org/current/userguide/signing_plugin.html
- https://docs.gradle.org/current/userguide/publishing_maven.html

**Legacy (OSSRH - Deprecated):**
- ~~https://central.sonatype.org/pages/gradle.html~~ (Outdated)
- ~~https://www.albertgao.xyz/2018/01/18/how-to-publish-artifact-to-maven-central-via-gradle/~~ (Uses old OSSRH)
- ~~https://central.sonatype.org/pages/releasing-the-deployment.html~~ (OSSRH-specific)