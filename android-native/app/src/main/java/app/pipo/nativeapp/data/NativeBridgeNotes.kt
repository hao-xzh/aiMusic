package app.pipo.nativeapp.data

/**
 * Native migration boundary.
 *
 * The web/Tauri app already has the hard parts implemented in Rust:
 * Netease weapi, cookies, cache.db, audio byte cache, feature analysis, and
 * AI provider config. The native app talks to those capabilities through
 * [PipoRepository]. During the first native slices this file keeps the boundary
 * explicit so UI work does not depend on the final bridge choice.
 *
 * Planned bridge choices:
 *
 * 1. Reuse Rust through JNI/UniFFI and keep one source of truth for weapi/cache.
 * 2. Port the weapi/cache repositories to Kotlin if JNI friction is too high.
 *
 * The UI should not call bridge primitives directly. Add methods to
 * [PipoRepository] and implement them behind this boundary.
 */
interface NativeBridgeNotes
