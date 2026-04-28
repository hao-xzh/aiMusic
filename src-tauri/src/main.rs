// 防止 Windows 下额外的控制台窗口
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    claudio_lib::run();
}
