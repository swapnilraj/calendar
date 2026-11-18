# Manual candidate workflow discovery

GitHub requires a workflow file on its default branch before manual dispatch is available. This
bootstrap makes the existing signed-candidate workflow discoverable; it does not merge the daily-driver
application into main or publish an APK.

Choose the reviewed `codex/daily-driver` integration ref when dispatching, after its release pipeline
is merged and the owner has provisioned the `release-signing` environment. The selected ref supplies
the application, version properties and signing script. Choose an increasing version code and a
version name such as `0.2.0-beta.1`. Package/version and non-debuggable metadata are checked before
signing; the output is always a draft release.

Provisioning and acceptance instructions live in the integration branch's
[release signing guide](https://github.com/swapnilraj/calendar/blob/codex/daily-driver/docs/RELEASE_SIGNING.md).
Keep keys/passwords outside Git and chat. Stable owner signing, account/device/upgrade tests and the
seven-day beta remain required before publishing a production release.
