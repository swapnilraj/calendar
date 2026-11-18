# Stable signing and downloadable candidates

The manual `Signed release candidate` workflow builds the selected source revision, runs the full
test/debug/lint gate and release assembly, signs outside Gradle, verifies the pinned signing
certificate, and creates a **draft GitHub release** containing the APK and SHA256SUMS. It also uploads
an Actions artifact. The draft release assets provide durable storage; this does not certify the
account/device/beta gates or publish a production release.

GitHub requires the workflow file on the repository's default branch before manual dispatch is
available. The CI-only bootstrap merged in [PR59](https://github.com/swapnilraj/calendar/pull/59)
and GitHub reports the workflow active (ID423203007). This made manual dispatch available without
merging the application into main. The signing pipeline also merged into the integration branch
in [PR58](https://github.com/swapnilraj/calendar/pull/58). No release workflow has been dispatched;
stable owner-key provisioning and release acceptance remain pending.

Provision the GitHub `release-signing` environment before running it. An owner must supply these
secrets through GitHub's settings or a trusted local CLI, never a chat, source file, commit or log:

| Name | Content |
| --- | --- |
| `DAYLIGHT_KEYSTORE_B64` | Single-line base64 of the owner's backed-up stable keystore |
| `DAYLIGHT_KEYSTORE_PASSWORD` | Keystore password |
| `DAYLIGHT_KEY_ALIAS` | Signing alias |
| `DAYLIGHT_KEY_PASSWORD` | Key password |

Set environment variable `DAYLIGHT_SIGNING_CERT_SHA256` to that key's public SHA-256 certificate
fingerprint. Keep an independent secure backup of the keystore and credentials: replacing the key
breaks ordinary APK upgrades. Protect the environment with the owner's required reviewer. The
workflow refuses missing configuration or a mismatched certificate and never generates a key.
Its temporary keystore is removed on exit; only the verified APK/checksum/notes are uploaded.

Run the workflow on the intended reviewed commit with a version such as `0.2.0-beta.1` and a positive
version code greater than every previously distributed build. It also rejects codes at or below the
highest `daylight-v<version>-<code>` release tag, including draft candidates; first code must exceed1.
The version code and name are embedded with `-Pdaylight.versionCode` / `-Pdaylight.versionName`.
Default debug development versioning remains1 / `0.1.0`.

Before publishing, inspect the draft APK/checksum and record the controlled personal/Workspace,
offline/conflict/recurrence, device reminder/Doze/reboot/timezone/TalkBack and upgrade results, plus
the seven-day work/personal beta. Verify upgrade from the prior **same-certificate** build while
retaining app-local settings/drafts/reminder state. Publish the existing draft only after all gates
pass; do not regenerate its asset under the same version/tag.

Existing development APKs are debug-signed. Android rejects replacing them with a different stable
certificate. Do not automatically uninstall them: any one-time transition must preserve or finish
local drafts and explicitly account for app-local data. Future stable candidates must use the
same owner-controlled key and increasing version codes.

Current provisioning is pending. This task's GitHub credentials returned403 for Actions secrets;
no stable owner key has been installed and no stable APK or published release is claimed. Local
fixture-key tests, if recorded, validate only the signing mechanism and are never release artifacts.

Mechanism evidence: shell syntax and all eight workflow shell blocks passed parsing; six local
ephemeral fixture-key checks passed (valid signature/certificate and16KiB alignment, wrong
certificate, wrong password, malformed base64, missing alias, existing-output refusal). The fixture
key and signed APKs were deleted. Release assembly passed with versionCode2 / versionName0.2.0-beta.1; APK metadata confirmed the expected package and a non-debuggable release. That unsigned release variant also passed fixture-key signing/certificate and16KiB zip-alignment verification; the temporary key and signed fixture APK were deleted. Same-key device upgrade and stable owner provisioning remain pending.
