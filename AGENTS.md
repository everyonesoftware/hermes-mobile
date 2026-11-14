# Hermes Pocket

Native Android companion for a remote Hermes Agent. Keep this product separate from Music and the unrelated Agent server repository.

- `apps/android/`: Kotlin / Jetpack Compose application.
- `docs/`: protocol findings and development notes.
- UI previews/demo mode must be explicitly labeled; never fabricate remote success.
- Never log, commit, or back up credentials. Require HTTPS and standard certificate verification. Do not follow authenticated requests across redirects.
- Keep API behavior grounded in the Hermes remote gateway source; document its version and unsupported operations.
- Test new behavior before implementing. Run unit tests, Android lint, debug build, and emulator UI tests before claiming completion.
- Local build: `source /opt/data/tools/android-env.sh; cd apps/android; bash gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --console=plain --max-workers=2`.
- Do not commit, push, modify Music, or change any home-server configuration without permission.
