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
If you're authorized to publish to nexus, which is a process done manually by a developer, you'll need access to the Sonatype credentials in lastpass (details below). 

Before you publish for the first time you will need to do some set up:
1. Set up Sonatype Credentials 
2. Install GPG (if not already done)
3. A GPG key set up
4. Gradle configured with your GPG & Sonatype credentials

Then you can push the artefacts to Sonatype, and from there publish them to maven central


### 1. Sonatype Credentials
To access Sonatype you need the Sonatype credentials via [LastPass](https://lastpass.com) which you'll need to put in your environment
```
export SONATYPE_USERNAME=HW5QuxHU
export SONATYPE_PASSWORD='<token from lastpass>'
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

### 5. Pushing to sonatype
Then to push up to Sonatype

`./gradlew clean build publish`

This will build kestrel, sign it and upload it to Sonatype staging repository. 

### 6. Pushing to maven central
To push from Sonatype to Maven Central, you need to do the following steps adapted from
[the documentation](https://www.albertgao.xyz/2018/01/18/how-to-publish-artifact-to-maven-central-via-gradle/):

- Open [Nexus Repository Manager](https://oss.sonatype.org/#welcome)
- Click the Log in at upper right corner
- On the left side, click Staging Repositories
- Search for the project by using "comcultureamp"
- Select the right item and click the Close button to close it. This finalizes the package in order to publish it.
- Click the Refresh button until the state in the "Activity" tab shows that repository has been closed.
- If there are any errors:
  - You can inspect them at the Activity panel.
  - You need to Drop this upload
  - Fix them in your local folder
  - Run the `./gradlew clean build publish` task again.
  - Then Close then continue
- If there are no errors:
  - Click Release button
- Your artifact has uploaded to [Maven central](https://search.maven.org/artifact/com.cultureamp/kestrel) (may take up to 2 hours to appear but should be much faster).

# Links

- https://central.sonatype.org/pages/releasing-the-deployment.html
- https://central.sonatype.org/pages/gradle.html (Outdated)
- https://www.albertgao.xyz/2018/01/18/how-to-publish-artifact-to-maven-central-via-gradle/
- https://central.sonatype.org/pages/working-with-pgp-signatures.html
- https://docs.gradle.org/current/userguide/signing_plugin.html
- https://docs.gradle.org/current/userguide/publishing_maven.html