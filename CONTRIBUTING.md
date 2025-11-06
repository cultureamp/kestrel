# Contributing

We're still figuring out how this will look but for now if you need support, please raise an issue and we will get back
to you within a few days.

## Developing

To compile and run tests

`./gradlew test`

Make sure to increase the `base_version` number in `gradle.properties`

### Publishing to maven local

If you need to test your version with another project in your development environment, you can publish it to maven local

`SKIP_SIGNING=true ./gradlew publishToMavenLocal`

## Publishing
If you're authorized to publish to Maven Central, which is a process done manually by a developer, you'll need access to the Central Portal credentials (details below).

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