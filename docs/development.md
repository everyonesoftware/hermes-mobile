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

## Safety boundaries

- No remote connection happens on first launch. The demo must be explicitly entered and visibly labeled.
- Only HTTPS gateway origins are accepted. No trust-all certificate handlers or cleartext allowances.
- Credentials are never placed in UI state, saved instance state, SharedPreferences, logs, Git, or Android backups. Re-authentication is required after process death.
- Only the last gateway origin may be persisted locally. Disconnect clears remote conversation content from app state.
- Demo approval decisions do not execute commands. Remote approvals remain unavailable until the real gateway contract is verified and implemented safely.
- Build output and SDK/tool binaries are ignored or outside the repository.
- Nothing is committed or pushed automatically.
