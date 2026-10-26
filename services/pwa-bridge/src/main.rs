include!("../../../android-native/native-bridge/src/lib.rs");

fn main() {
    use std::io::{BufRead, Write};
    let Some(directory) = std::env::args_os().nth(1) else {
        eprintln!("PWA bridge requires its session data directory");
        std::process::exit(2);
    };
    let directory = PathBuf::from(directory);
    std::fs::create_dir_all(&directory).expect("create PWA session directory");
    #[cfg(unix)] {
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(&directory, std::fs::Permissions::from_mode(0o700)).ok();
    }
    let _ = APP_DATA_DIR.set(directory);
    let output = std::sync::Arc::new(std::sync::Mutex::new(std::io::stdout()));
    let active = std::sync::Arc::new(std::sync::atomic::AtomicUsize::new(0));
    for line in std::io::stdin().lock().lines() {
        let Ok(line) = line else { break };
        let Ok(request) = serde_json::from_str::<Value>(&line) else { continue };
        let id = request.get("id").cloned().unwrap_or(Value::Null);
        let command = request.get("command").and_then(Value::as_str).unwrap_or("").to_owned();
        let args = request.get("args").cloned().unwrap_or(Value::Null);
        let output = output.clone();
        if command == "bridge_init" || active.load(std::sync::atomic::Ordering::SeqCst) >= 24 {
            let response = json!({"id": id, "result": {"error": "bridge unavailable or busy"}});
            if let Ok(mut stdout) = output.lock() { let _ = writeln!(stdout, "{response}"); let _ = stdout.flush(); }
            continue;
        }
        active.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
        let active = active.clone();
        std::thread::spawn(move || {
            let raw = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| dispatch(&command, args)))
                .unwrap_or_else(|_| error_json("bridge command failed".into()));
            let result = serde_json::from_str::<Value>(&raw).unwrap_or_else(|_| json!({"error": "invalid bridge response"}));
            if let Ok(mut stdout) = output.lock() {
                let _ = writeln!(stdout, "{}", json!({"id": id, "result": result}));
                let _ = stdout.flush();
            }
            active.fetch_sub(1, std::sync::atomic::Ordering::SeqCst);
        });
    }
}
