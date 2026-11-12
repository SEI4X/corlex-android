# Release dependency integrity

The Gradle8.14 wrapper JAR matches the official SHA-256
`7d3a4ac4de1c32b59bc6a4eb8ecb8e612ccd0cf1ae1e99f66902da64df296172`.
The wrapper distribution is pinned with `distributionSha256Sum` from
[Gradle's official checksum](https://services.gradle.org/distributions/gradle-8.14-bin.zip.sha256).

Gradle dependency locks record the reviewed build/runtime graph. The checked-in
verification metadata pins resolved artifact checksums, including the official
Google Maven Linux AAPT2 artifact used by hosted Linux builds. This is integrity
verification against reviewed checksums, not a claim that checksums prove source
code safety or publisher identity.

Normal checks must use `--dependency-verification strict`; CI must never generate
or automatically accept new metadata. Dependency updates require a reviewed PR:
verify upstream provenance, regenerate locks/metadata locally, inspect the diff,
and run normal strict checks afterward. Never add broad trusted-artifact or
ignored-artifact exceptions to make a failed check pass.

Validation performed for this graph: all293 build/test tasks passed, a subsequent
offline strict build passed, and replacing the expected AGP JAR hash caused actual
Gradle strict verification to reject the dependency. The original hash was restored.

The standalone Compose build also resolves Kotlin stdlib-common1.9.24 module metadata during variant selection. Its exact SHA-256 was independently checked against Maven Central over TLS and pinned explicitly; the runtime lock graph is unchanged.

Hosted Linux included-plugin configuration also resolves kotlinx-coroutines-bom1.8.0 metadata. Its pinned POM hash matches both an independent Maven Central TLS download and the previously resolved local cache; verification stays strict.
