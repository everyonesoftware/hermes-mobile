# Development

## Structure

`apps/android` is a native Kotlin + Jetpack Compose app. Root Git history belongs to this product only. No Music or other Agent server sources are moved here.

Requires JDK 17+, Android platform 36/build-tools 36.0.0, and an `ANDROID_HOME` SDK path. The Gradle wrapper is checked in with a distribution checksum. Open `apps/android` in Android Studio.

On this development machine:

```sh
source /opt/data/tools/android-env.sh
python3 scripts/verify-android.py
```

For device UI tests, launch a task-owned emulator and pass its serial:

```sh
python3 scripts/verify-android.py --serial emulator-5582
```

The runner refuses physical devices and validates the JUnit summary; a zero exit code from `am instrument` alone is not sufficient.

## Automated signed prereleases

`.github/workflows/android-release.yml` runs on pushes to `main` and can be manually dispatched from `main`. It checks out full history, uses `scripts/release-version.sh` to count commits reachable from `HEAD`, then sets the APK `versionName` to `0.1.N` and `versionCode` to `N`. It runs JVM unit tests, Android lint, builds debug and release variants, verifies the release APK metadata, signature, and pinned certificate, and publishes `hermes-pocket-v0.1.N.apk` under the `v0.1.N` GitHub prerelease. This is a signed development prerelease, not a store publication. No emulator or real-server integration test runs in CI.

The release key is kept outside Git at `/opt/data/.local/share/hermes-pocket/signing/hermes-pocket-release.p12` with its password in `keystore-password` (directory mode 0700; files mode 0600). GitHub Actions uses repository secrets `HERMES_SIGNING_KEYSTORE_BASE64` (base64-encoded PKCS12) and `HERMES_SIGNING_STORE_PASSWORD`; the PKCS12 key password is the same. The alias is `hermes-pocket`. The public signing certificate SHA-256 is `2F:BE:9C:81:83:43:E2:2B:7F:FD:D3:4E:2F:D4:23:6C:6E:A1:A4:88:A6:7A:EB:99:E6:A2:49:62:14:79:C0:F2`. **Back up the keystore and password securely outside this machine; GitHub Actions secrets cannot be read back.** Losing the key prevents future updates, while exposure of the key and password compromises signing identity. Never put either in Git or chat. The CI runner deletes its decoded keystore after building. Existing `v0.1.2` and `v0.1.3` CI debug APKs have different keys: uninstall them before installing the first stable-key APK; subsequent stable-key APKs can update in place with increasing version codes.

The release step refuses to overwrite an existing release tag on rerun. Local builds without release Gradle properties keep the development defaults `0.1.0` / version code `1`. To inspect a release-equivalent local build, pass `-PreleaseVersionName=0.1.N -PreleaseVersionCode=N` (using the values printed by `bash scripts/release-version.sh`) to Gradle, plus `HERMES_SIGNING_STORE_FILE`, `HERMES_SIGNING_STORE_PASSWORD`, and `HERMES_SIGNING_KEY_PASSWORD` in the build environment. Full history is required for the count. Never run a locally signed `assembleRelease` with these variables absent: that produces an unsigned APK.

## Safety boundaries

- No remote connection happens on first launch. The demo must be explicitly entered and visibly labeled.
- Only HTTPS gateway origins are accepted. No trust-all certificate handlers or cleartext allowances.
- Credentials are never placed in UI state, saved instance state, SharedPreferences, logs, Git, or Android backups. Re-authentication is required after process death.
- Only the last gateway origin may be persisted locally. Disconnect clears remote conversation content from app state.
- Demo approval decisions do not execute commands. Remote approvals remain unavailable until the real gateway contract is verified and implemented safely.
- Build output and SDK/tool binaries are ignored or outside the repository.
- Nothing is committed or pushed automatically.
