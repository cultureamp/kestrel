# Contributing

We're still figuring out how this will look but for now if you need support, please raise an issue and we will get back
to you within a few days.

# Developing

To compile and run tests

`./gradlew test`

If you're authorized to publish to nexus, which is a process done manually by a developer, you'll need to have been
provided with the Sonatype credentials via LastPass which you'll need to put in your environment
```
export SONATYPE_USERNAME="william.boxhall.cultureamp"
export SONATYPE_PASSWORD='<password from lastpass>'
```

Then to push up to Sonatype

`./gradlew uploadArchives`

This will launch the gpg-agent locally on your machine. You'll probably need the GPG key passphrase which is also in
LastPass. Once you're past the GPG agent, the archives will be pushed up to the Sonatype staging area.

To push from Sonatype to Maven Central, you need to do the following steps adapted from
[the documentation](https://www.albertgao.xyz/2018/01/18/how-to-publish-artifact-to-maven-central-via-gradle/):

- Open Nexus Repository Manager
- Click the Log in at upper right corner
- On the right side, click Staging Repositories
- Search for the project by using "comcultureamp"
- Select the right item and click the Close button to close it. This finalizes the package in order to publish it.
- Click the Refresh button until the state updates.
- If there are any errors:
  - You can inspect them at the Activity panel.
  - You need to Drop this upload
  - Fix them in your local folder
  - Run the `uploadArchives` task again.
  - Then Close then continue
- If there are no errors:
  - Click Release button
- Your artifact has uploaded to Maven central (may take  up to 2 hours to appear but should be much faster)

# Links

- https://central.sonatype.org/pages/releasing-the-deployment.html
- https://central.sonatype.org/pages/gradle.html
- https://www.albertgao.xyz/2018/01/18/how-to-publish-artifact-to-maven-central-via-gradle/
- https://central.sonatype.org/pages/working-with-pgp-signatures.html
- https://docs.gradle.org/current/userguide/signing_plugin.html
- https://docs.gradle.org/current/userguide/publishing_maven.html