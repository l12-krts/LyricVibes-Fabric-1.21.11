#![windows_subsystem = "windows"]

mod audio;
mod media;

use std::io::{BufRead, Write};
use std::sync::mpsc;
use std::thread;

pub fn emit(value: serde_json::Value) {
    let mut out = std::io::stdout().lock();
    if writeln!(out, "{value}").is_err() {
        std::process::exit(0);
    }
    let _ = out.flush();
}

fn main() {
    let (tx, rx) = mpsc::channel::<String>();

    thread::spawn(move || {
        let stdin = std::io::stdin();
        for line in stdin.lock().lines() {
            let Ok(line) = line else { break };
            let cmd = line.trim().to_string();
            if cmd == "quit" {
                break;
            }
            if !cmd.is_empty() && tx.send(cmd).is_err() {
                break;
            }
        }
        std::process::exit(0);
    });

    let audio_enabled = !std::env::args().any(|a| a == "--no-audio");
    if audio_enabled {
        thread::spawn(audio::run);
    }

    emit(serde_json::json!({ "t": "hello", "v": env!("CARGO_PKG_VERSION") }));
    media::run(rx);
}
