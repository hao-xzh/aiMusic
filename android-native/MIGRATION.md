# Native Migration Checklist

The native app should preserve the existing product behavior and visual design.
This file tracks the remaining parity work.

## Player

- Compact cover, title, subtitle, progress, transport controls: implemented.
- Queue strip: implemented.
- Immersive lyrics overlay: implemented.
- YRC per-character wipe: implemented.
- Cover color sampling and adaptive tones: pending.
- Gapless/crossfade strategy: pending native implementation.

## Playback

- Media3 ExoPlayer service: implemented.
- MediaSession controls and lock screen card: implemented.
- Notification artwork sizing and OEM compatibility: pending.
- Audio cache-backed data source: implemented with Media3 SimpleCache.
- Lossless/exhigh fallback: pending.

## Netease

- QR login surface: implemented.
- SMS login surface: implemented.
- Account state: implemented.
- User playlists and playlist detail: implemented through Rust JNI bridge.
- Song URLs, lyrics, search: implemented through Rust JNI bridge.

## AI

- Provider config surface: implemented.
- Key storage and model list: implemented through Rust JNI bridge.
- AI narration/chat: implemented through Rust JNI bridge and native Pet chat.

## Screens

- Taste profile: implemented with native demo/taste signals.
- Distill pipeline: implemented with native source/pipeline surface.
- Settings: implemented with persisted Android settings, cache controls, login, AI config, and user facts.
- Library/playlist detail: partial. The player auto-loads the first real user playlist; a full playlist-detail browser is still pending.

## Engineering

- Android SDK/Gradle build verification: blocked on local machine setup.
- Native build script: `scripts/build-android-native.ps1`.
- Native bridge build script: `scripts/build-android-native-bridge.ps1`.
- Rust/JNI implementation: implemented for Netease, AI, audio cache stats, and audio analysis.
- Crash/performance telemetry: pending.
- Device profiling matrix: pending.
