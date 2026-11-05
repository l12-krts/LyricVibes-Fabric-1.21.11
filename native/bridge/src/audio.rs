use rustfft::{num_complex::Complex32, FftPlanner};
use serde_json::json;
use std::time::{Duration, Instant};
use windows::core::Result;
use windows::Win32::Media::Audio::*;
use windows::Win32::System::Com::*;

use crate::emit;

const FFT_SIZE: usize = 2048;
const BANDS: usize = 16;
const FRAME: Duration = Duration::from_millis(33);

pub fn run() {
    unsafe {
        let _ = CoInitializeEx(None, COINIT_MULTITHREADED);
    }
    loop {
        if let Err(e) = unsafe { capture() } {
            emit(json!({ "t": "error", "msg": format!("audio: {e}") }));
        }
        std::thread::sleep(Duration::from_secs(1));
    }
}

unsafe fn capture() -> Result<()> {
    let enumerator: IMMDeviceEnumerator = CoCreateInstance(&MMDeviceEnumerator, None, CLSCTX_ALL)?;
    let device = enumerator.GetDefaultAudioEndpoint(eRender, eConsole)?;
    let device_id = device.GetId()?.to_string().unwrap_or_default();
    let client: IAudioClient = device.Activate(CLSCTX_ALL, None)?;
    let fmt_ptr = client.GetMixFormat()?;
    let fmt = *fmt_ptr;
    client.Initialize(
        AUDCLNT_SHAREMODE_SHARED,
        AUDCLNT_STREAMFLAGS_LOOPBACK,
        10_000_000,
        0,
        fmt_ptr,
        None,
    )?;
    CoTaskMemFree(Some(fmt_ptr as *const _));

    let channels = fmt.nChannels.max(1) as usize;
    let bits = fmt.wBitsPerSample;
    let rate = fmt.nSamplesPerSec as f32;
    let capture: IAudioCaptureClient = client.GetService()?;
    client.Start()?;

    let mut ring = vec![0f32; FFT_SIZE];
    let mut head = 0usize;
    let mut planner = FftPlanner::<f32>::new();
    let fft = planner.plan_fft_forward(FFT_SIZE);
    let window: Vec<f32> = (0..FFT_SIZE)
        .map(|i| 0.5 - 0.5 * (2.0 * std::f32::consts::PI * i as f32 / (FFT_SIZE - 1) as f32).cos())
        .collect();
    let edges = band_edges(rate);
    let mut buf = vec![Complex32::default(); FFT_SIZE];
    let mut last_emit = Instant::now();
    let mut last_device_check = Instant::now();
    let mut was_silent = true;

    loop {
        std::thread::sleep(Duration::from_millis(8));
        let mut packet = capture.GetNextPacketSize()?;
        while packet > 0 {
            let mut data: *mut u8 = std::ptr::null_mut();
            let mut frames = 0u32;
            let mut flags = 0u32;
            capture.GetBuffer(&mut data, &mut frames, &mut flags, None, None)?;
            let silent = flags & (AUDCLNT_BUFFERFLAGS_SILENT.0 as u32) != 0;
            for f in 0..frames as usize {
                let mut sum = 0f32;
                if !silent {
                    for c in 0..channels {
                        let idx = f * channels + c;
                        sum += match bits {
                            32 => *(data as *const f32).add(idx),
                            16 => *(data as *const i16).add(idx) as f32 / 32768.0,
                            _ => 0.0,
                        };
                    }
                }
                ring[head] = sum / channels as f32;
                head = (head + 1) % FFT_SIZE;
            }
            capture.ReleaseBuffer(frames)?;
            packet = capture.GetNextPacketSize()?;
        }

        if last_emit.elapsed() < FRAME {
            continue;
        }
        last_emit = Instant::now();

        for i in 0..FFT_SIZE {
            buf[i] = Complex32::new(ring[(head + i) % FFT_SIZE] * window[i], 0.0);
        }
        fft.process(&mut buf);
        let bands = to_bands(&buf, &edges);
        let silent = bands.iter().all(|b| *b < 0.01);
        if !(silent && was_silent) {
            emit(json!({ "t": "bands", "v": bands }));
        }
        was_silent = silent;

        if last_device_check.elapsed() > Duration::from_secs(2) {
            last_device_check = Instant::now();
            let current = enumerator
                .GetDefaultAudioEndpoint(eRender, eConsole)
                .and_then(|d| d.GetId())
                .map(|p| p.to_string().unwrap_or_default())
                .unwrap_or_default();
            if current != device_id {
                let _ = client.Stop();
                return Ok(());
            }
        }
    }
}

fn band_edges(rate: f32) -> Vec<usize> {
    let (lo, hi) = (40f32, 16_000f32.min(rate / 2.0));
    (0..=BANDS)
        .map(|i| {
            let f = lo * (hi / lo).powf(i as f32 / BANDS as f32);
            ((f / rate * FFT_SIZE as f32) as usize).clamp(1, FFT_SIZE / 2)
        })
        .collect()
}

fn to_bands(spec: &[Complex32], edges: &[usize]) -> Vec<f64> {
    (0..BANDS)
        .map(|b| {
            let (a, z) = (edges[b], edges[b + 1].max(edges[b] + 1));
            let peak = spec[a..z].iter().map(|c| c.norm()).fold(0f32, f32::max);
            let db = 20.0 * (peak / FFT_SIZE as f32 * 4.0 + 1e-9).log10();
            ((((db + 60.0) / 55.0).clamp(0.0, 1.0) * 1000.0).round() / 1000.0) as f64
        })
        .collect()
}
