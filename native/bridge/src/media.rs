use base64::Engine;
use serde_json::json;
use std::sync::mpsc::Receiver;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use windows::core::Result;
use windows::Media::Control::{
    GlobalSystemMediaTransportControlsSession as Session,
    GlobalSystemMediaTransportControlsSessionManager as Manager,
};
use windows::Storage::Streams::DataReader;
use windows::Win32::System::Com::{CoInitializeEx, COINIT_MULTITHREADED};

use crate::emit;

const TICKS_PER_MS: i64 = 10_000;
const UNIX_EPOCH_TICKS: i64 = 116_444_736_000_000_000;

#[derive(Default, PartialEq, Clone)]
struct Track {
    title: String,
    artist: String,
    album: String,
}

impl Track {
    fn key(&self) -> String {
        format!("{}\u{1f}{}\u{1f}{}", self.title, self.artist, self.album)
    }
}

pub fn run(rx: Receiver<String>) {
    unsafe {
        let _ = CoInitializeEx(None, COINIT_MULTITHREADED);
    }
    loop {
        match Manager::RequestAsync().and_then(|op| op.get()) {
            Ok(manager) => poll_loop(&manager, &rx),
            Err(e) => emit(json!({ "t": "error", "msg": format!("session manager: {e}") })),
        }
        std::thread::sleep(Duration::from_secs(2));
    }
}

fn poll_loop(manager: &Manager, rx: &Receiver<String>) {
    let mut track = Track::default();
    let mut art_key = String::new();
    let mut art_tries = 0u32;
    let mut last_props = Instant::now() - Duration::from_secs(10);
    let mut had_session = true;

    loop {
        while let Ok(cmd) = rx.try_recv() {
            if let Ok(session) = manager.GetCurrentSession() {
                let _ = command(&session, &cmd);
            }
        }

        let session = match manager.GetCurrentSession() {
            Ok(s) => s,
            Err(_) => {
                if had_session {
                    emit(json!({ "t": "none" }));
                    had_session = false;
                    track = Track::default();
                    art_key.clear();
                }
                std::thread::sleep(Duration::from_millis(400));
                continue;
            }
        };
        had_session = true;

        if last_props.elapsed() >= Duration::from_millis(500) {
            last_props = Instant::now();
            if let Ok(t) = read_track(&session) {
                if t != track {
                    track = t;
                    art_tries = 0;
                }
            }
            let key = track.key();
            if art_key != key && art_tries < 8 && !track.title.is_empty() {
                art_tries += 1;
                if let Ok(Some((mime, bytes))) = read_thumbnail(&session) {
                    art_key = key.clone();
                    emit(json!({
                        "t": "art",
                        "key": key,
                        "mime": mime,
                        "data": base64::engine::general_purpose::STANDARD.encode(bytes),
                    }));
                }
            }
        }

        if let Err(e) = emit_state(&session, &track) {
            emit(json!({ "t": "error", "msg": format!("state: {e}") }));
        }
        std::thread::sleep(Duration::from_millis(200));
    }
}

fn read_track(session: &Session) -> Result<Track> {
    let props = session.TryGetMediaPropertiesAsync()?.get()?;
    Ok(Track {
        title: props.Title()?.to_string(),
        artist: props.Artist()?.to_string(),
        album: props.AlbumTitle()?.to_string(),
    })
}

fn read_thumbnail(session: &Session) -> Result<Option<(String, Vec<u8>)>> {
    let props = session.TryGetMediaPropertiesAsync()?.get()?;
    let Ok(thumb) = props.Thumbnail() else { return Ok(None) };
    let stream = thumb.OpenReadAsync()?.get()?;
    let size = stream.Size()? as u32;
    if size == 0 {
        return Ok(None);
    }
    let mime = stream.ContentType()?.to_string();
    let reader = DataReader::CreateDataReader(&stream)?;
    reader.LoadAsync(size)?.get()?;
    let mut buf = vec![0u8; size as usize];
    reader.ReadBytes(&mut buf)?;
    Ok(Some((mime, buf)))
}

fn emit_state(session: &Session, track: &Track) -> Result<()> {
    let timeline = session.GetTimelineProperties()?;
    let playback = session.GetPlaybackInfo()?;
    let status = playback.PlaybackStatus()?.0;
    let rate = playback
        .PlaybackRate()
        .and_then(|r| r.Value())
        .unwrap_or(1.0);
    let playing = status == 4;

    let pos_ticks = timeline.Position()?.Duration;
    let start_ticks = timeline.StartTime()?.Duration;
    let end_ticks = timeline.EndTime()?.Duration;
    let updated = timeline.LastUpdatedTime()?.UniversalTime;

    let now_unix_ms = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0);
    let now_ticks = now_unix_ms * TICKS_PER_MS + UNIX_EPOCH_TICKS;

    let mut pos_ms = (pos_ticks - start_ticks) / TICKS_PER_MS;
    if playing && updated > 0 {
        let drift = ((now_ticks - updated) / TICKS_PER_MS).max(0);
        pos_ms += (drift as f64 * rate) as i64;
    }
    let dur_ms = ((end_ticks - start_ticks) / TICKS_PER_MS).max(0);
    if dur_ms > 0 {
        pos_ms = pos_ms.clamp(0, dur_ms);
    }

    emit(json!({
        "t": "state",
        "title": track.title,
        "artist": track.artist,
        "album": track.album,
        "app": session.SourceAppUserModelId().map(|s| s.to_string()).unwrap_or_default(),
        "status": status,
        "pos": pos_ms,
        "dur": dur_ms,
        "rate": rate,
        "ts": now_unix_ms,
    }));
    Ok(())
}

fn command(session: &Session, cmd: &str) -> Result<()> {
    match cmd {
        "toggle" => session.TryTogglePlayPauseAsync()?.get().map(|_| ()),
        "play" => session.TryPlayAsync()?.get().map(|_| ()),
        "pause" => session.TryPauseAsync()?.get().map(|_| ()),
        "next" => session.TrySkipNextAsync()?.get().map(|_| ()),
        "prev" => session.TrySkipPreviousAsync()?.get().map(|_| ()),
        _ => Ok(()),
    }
}
