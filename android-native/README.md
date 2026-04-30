# Pipo Native Android

This is the native Android track for Pipo. It is intentionally separate from
`src-tauri/gen/android` because Tauri can regenerate that directory.

Current scope:

- Jetpack Compose app shell.
- Media3 ExoPlayer playback service.
- MediaSession-backed transport controls for notification and lock screen.
- Native player screen mirroring the compact Pipo surface, queue strip, and immersive lyrics.
- Native taste, distill, settings, AI Pet chat, queue, and QR/SMS login surfaces.
- Repository boundary for Netease, lyrics, cache, settings, AI, and Rust/JNI reuse.
- Rust JNI bridge for Netease login/playlists/search/song URLs/lyrics, AI provider config/chat, native audio cache stats, and Symphonia audio features.

Parity map:

- `src/components/PlayerCard.tsx` -> `ui/PlayerScreen.kt`
- `src/components/AiPet.tsx` and `AiPet.css` -> `ui/NativeAiPet.kt`
- `src/app/taste/page.tsx` -> `ui/TasteScreen`
- `src/app/distill/page.tsx` -> `ui/DistillScreen`
- `src/app/settings/page.tsx` -> `ui/SettingsScreen`
- `src/lib/player-state.tsx` -> `playback/PlayerViewModel.kt`
- `src/lib/tauri.ts` command surface -> `data/PipoRepository.kt`
- `src-tauri/src/netease/*`, `audio/*`, `ai/*` -> `data/RustBridgeRepository.kt`

Remaining migration slices:

1. Install Android SDK/NDK/Gradle on the build machine and produce a signed device APK.
2. Run device profiling on low/mid/high Android phones and tune Compose recomposition hot paths.
3. Add crash/performance telemetry before external testing.
4. Expand native playlist/detail management beyond the first auto-loaded playlist.

Build:

```powershell
cd ..
.\scripts\check-android-native-env.ps1
.\scripts\build-android-native-bridge.ps1
.\scripts\build-android-native.ps1
```

Direct Gradle build after environment setup:

```powershell
cd android-native
gradle :app:assembleDebug
```

This machine currently needs either Gradle installed or a Gradle wrapper added
before the command can run.

Native bridge:

```powershell
cd ..
.\scripts\build-android-native-bridge.ps1
```

For device APKs, build the bridge with Android NDK targets and package the
resulting `libpipo_native_bridge.so` under `app/src/main/jniLibs/<abi>/`.
