# Rhine Android development

## Scope and architecture

- This is an independent native Android music player, not a foobar2000 component or a WebView wrapper.
- `data/` owns SAF document access, metadata and the atomic library cache. `playback/` owns Media3 and the media session. `ui/` owns rendering and touch interaction.
- Read only user-selected music directories. Keep metadata and artwork work off the main thread. A cancelled or failed import must retain the previous library and its URI permissions.
- Keep playback in the service so the screen's lifecycle does not stop music. System controls may control the queue; arbitrary external media URIs must not be accepted.
- Demo albums are explicitly visual previews, never fake playback.

## Build and verification

Use JDK 17 and the checked-in Gradle wrapper:

```sh
./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
```

Use the Android emulator smoke job for playback lifecycle and substantial UI changes. Test focused behavior, including cancellation/permission handling where changed. Do not substitute a successful build for playback or visual verification.

## Sources and changes

- Preserve the source commit, asset hashes and licence notices in `docs/UPSTREAM.md`, `THIRD_PARTY_NOTICES.md`, and `app/src/main/assets/rhine/upstream.json`.
- Do not add third-party songs or album covers. The debug test WAV is a generated test signal, not a library entry.
- Keep changes independently reviewable. Use a feature branch and PR; check the complete diff against `origin/main` and exclude local SDK paths, build outputs and signing material.
- Before merging, verify CI and actual CodeRabbit coverage for the latest PR HEAD. Check inline threads, review bodies and Conversation findings. Fix valid findings and let CodeRabbit confirm them; do not manually resolve its threads or dismiss reviews to clear status.
- Use squash merge after the required checks and review converge.
