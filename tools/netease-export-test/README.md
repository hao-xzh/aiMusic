# NetEase Export Test

Standalone local test page for resolving playable NetEase Cloud Music audio and exporting MP3 files.

It is intentionally kept outside the product UI. It reuses the existing Rust NetEase client from `src-tauri`, including the saved login cookie.

## Run

```powershell
cargo run --manifest-path tools\netease-export-test\Cargo.toml
```

Then open:

```text
http://127.0.0.1:4577
```

If the cookie is not found automatically:

```powershell
cargo run --manifest-path tools\netease-export-test\Cargo.toml -- --cookie "C:\path\to\netease_cookies.json"
```

## Notes

- Log in with the main Pipo app first. This tester reads the same `netease_cookies.json`.
- You do not need to know song IDs. Search by song name or artist, then click Preview or Export.
- Use quality `exhigh` when you want NetEase to return MP3 where available.
- If the returned source is FLAC/M4A/AAC, MP3 conversion requires `ffmpeg` on PATH, or an explicit ffmpeg path in the page.
