# Maven Central Release Guide

This document is for FileLogger maintainers. Consumers only need the Maven Central
coordinates shown in the main README.

## Publication architecture

- Group: `io.github.sevcanucan`
- Candidate version: `1.0.0-rc5`
- Portal: Sonatype Central Portal
- Gradle integration: `com.vanniktech.maven.publish` version `0.35.0`
- Signing: Gradle Signing Plugin with an ASCII-armored in-memory OpenPGP key
- Release control: manual GitHub Actions dispatch from a version tag
- Final release: manual approval in Central Portal after upload validation
- Release host: macOS, so Android, JVM, iOS, and Wasm variants are uploaded together

Version `0.35.0` of the publishing plugin is intentional. It supports this
project's Gradle 8.13, Android Gradle Plugin 8.11.2, and Kotlin 2.2.20 toolchain.
Newer plugin releases require Gradle 9 and a newer Android Gradle Plugin.

The seven primary publications are:

```text
io.github.sevcanucan:filelogger
io.github.sevcanucan:filelogger-core
io.github.sevcanucan:filelogger-okhttp
io.github.sevcanucan:filelogger-remote
io.github.sevcanucan:filelogger-crashlytics
io.github.sevcanucan:filelogger-otel
io.github.sevcanucan:filelogger-ui
```

`filelogger-core` is a Kotlin Multiplatform publication. Gradle also creates its
platform variants for Android, JVM, iOS Arm64, iOS Simulator Arm64, and Wasm JS.
Consumers should normally depend on `filelogger-core`; Gradle metadata selects the
correct platform artifact automatically.

JitPack compatibility is retained. When JitPack sets `JITPACK`, the build uses the
existing `com.github.SevcanUcan.android-file-logger` group. All other builds use the
Maven Central group.

## Required GitHub configuration

Create a GitHub Actions environment named `maven-central`. Add required reviewers
so that a maintainer must approve every deployment. Store these environment secrets:

| Secret | Value |
| --- | --- |
| `MAVEN_CENTRAL_USERNAME` | Username from the Sonatype publishing token |
| `MAVEN_CENTRAL_PASSWORD` | Password from the Sonatype publishing token |
| `SIGNING_KEY` | Complete ASCII-armored private key, including BEGIN/END lines |
| `SIGNING_KEY_ID` | Long OpenPGP key ID, currently `A08BD19C7EB5A7EC` |
| `SIGNING_PASSWORD` | OpenPGP private-key passphrase |

The Sonatype portal login password is not the publishing-token password. Use the
username and password shown when the publishing token was created.

Export the private key only on a trusted machine. Do not paste it into terminal
logs, issue comments, workflow output, or repository files. If a temporary export
file is created, store it outside the repository and securely remove it after the
GitHub secret is configured. Exported `.asc`, `.gpg`, and `.pgp` files are ignored
as an additional guard, but `.gitignore` is not a secret-management system.

## Local verification

These checks do not contact or publish to Maven Central and do not need credentials:

```powershell
.\gradlew.bat clean test apiCheck :filelogger-core:wasmJsNodeTest lintDebug assembleRelease
.\gradlew.bat publishToMavenLocal
.\gradlew.bat verifyMavenLocalPublications
```

The last task checks all seven primary coordinates and the KMP platform coordinates
supported by the current host. Windows/macOS checks all five generated variants;
Linux checks Android, JVM, and Wasm because Apple publications are disabled there.
For every module it validates the binary, POM, Gradle module metadata, sources JAR,
javadoc JAR, and mandatory POM fields. It also validates internal project dependency
coordinates for the primary modules. The release workflow runs on macOS and therefore
requires all five platform variants before uploading to Central Portal.

For a private local signing test, expose the same Gradle properties only in the
current process or in the user-level Gradle configuration outside this repository:

```text
ORG_GRADLE_PROJECT_signingInMemoryKey
ORG_GRADLE_PROJECT_signingInMemoryKeyId
ORG_GRADLE_PROJECT_signingInMemoryKeyPassword
```

Then run:

```powershell
.\gradlew.bat publishToMavenLocal verifyMavenLocalPublications -PverifySignatures=true
```

Never put credentials in this repository's `gradle.properties`.

## First release procedure

1. Confirm that `VERSION_NAME` is `1.0.0-rc5` and the changelog is ready.
2. Run the complete local verification commands above.
3. Merge the reviewed publishing branch into `main`.
4. Create and push the exact tag `1.0.0-rc5` from the reviewed main commit.
5. In GitHub Actions, open **Publish Maven Central**.
6. Select the `1.0.0-rc5` tag and run the workflow manually.
7. Approve the protected `maven-central` environment deployment.
8. Wait for tests, signed local verification, and Central Portal upload to pass.
9. Open Central Portal and inspect the deployment validation results.
10. Click **Publish** in Central Portal only after every artifact is correct.

The workflow calls `publishToMavenCentral`, not
`publishAndReleaseToMavenCentral`. Therefore a workflow run can upload a deployment,
but it cannot make it public without the separate Portal action.

## Post-release verification

Verify all seven primary coordinates in Central Portal and Maven Central Search.
Each primary coordinate must contain:

- Main AAR or JAR
- POM
- Gradle module metadata
- Sources JAR
- Javadoc JAR
- ASCII-armored signatures for every published file

Inspect the POMs for project name, description, project URL, MIT license,
developer, SCM, and expected dependencies. In particular, `filelogger` must depend
on `filelogger-core`, while each Android extension must depend on `filelogger`.

Finally, use a clean sample project with only `google()` and `mavenCentral()` and
resolve these dependencies:

```kotlin
implementation("io.github.sevcanucan:filelogger:1.0.0-rc5")
implementation("io.github.sevcanucan:filelogger-okhttp:1.0.0-rc5")
```

Build a release variant to confirm transitive dependency resolution and consumer
ProGuard metadata. Central synchronization may take some time after the Portal
reports the deployment as published.

## Failure handling

- A failed workflow does not publish anything automatically. Fix the cause before
  retrying.
- If Portal validation fails, inspect the deployment there and drop the failed
  deployment. Do not reuse a version that was already published.
- If a secret is exposed, revoke the Sonatype token or signing key immediately and
  rotate the corresponding GitHub secret.
- Never create or move the release tag until the commit to be released is final.
