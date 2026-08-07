use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Instant;

pub struct SyncManager {
    audio_pts: Arc<Mutex<f64>>,
    start_time: Arc<Mutex<Option<Instant>>>,
    pause_start: Arc<Mutex<Option<Instant>>>,
    // Compartilhado com o PlaybackController: quando ha uma trilha de
    // audio, o controle de velocidade funciona reamostrando o audio (ver
    // AudioDecoder::set_speed), e o master clock (audio_pts) reflete isso
    // automaticamente. Para arquivos SEM audio, o master clock cai no
    // fallback de wall-clock abaixo, que precisa saber a velocidade para
    // acompanhar.
    speed_bits: Arc<AtomicU32>,
}

impl SyncManager {
    pub fn new(speed_bits: Arc<AtomicU32>) -> Self {
        Self {
            audio_pts: Arc::new(Mutex::new(0.0)),
            start_time: Arc::new(Mutex::new(None)),
            pause_start: Arc::new(Mutex::new(None)),
            speed_bits,
        }
    }

    pub fn start(&mut self) {
        if let Ok(mut st) = self.start_time.lock() {
            *st = Some(Instant::now());
        }
        if let Ok(mut locked_pts) = self.audio_pts.lock() {
            *locked_pts = 0.0;
        }
    }

    pub fn reset(&self) {
        if let Ok(mut st) = self.start_time.lock() {
            *st = Some(Instant::now());
        }
        if let Ok(mut locked_pts) = self.audio_pts.lock() {
            *locked_pts = 0.0;
        }
    }

    pub fn update_audio_pts(&self, pts: f64) {
        if let Ok(mut locked_pts) = self.audio_pts.lock() {
            *locked_pts = pts;
        }
    }

    pub fn update_master_clock(&self, time_sec: f64) {
        if let Ok(mut locked_pts) = self.audio_pts.lock() {
            *locked_pts = time_sec;
        }
    }

    pub fn pause(&self) {
        if let Ok(mut pt) = self.pause_start.lock() {
            *pt = Some(Instant::now());
        }
    }

    pub fn resume(&self) {
        if let Ok(mut pt_lock) = self.pause_start.lock() {
            if let Some(pt) = *pt_lock {
                let pause_duration = pt.elapsed();
                if let Ok(mut st_lock) = self.start_time.lock() {
                    if let Some(st) = st_lock.as_mut() {
                        // Advance start_time by pause_duration so elapsed() stays the same
                        *st += pause_duration;
                    }
                }
            }
            *pt_lock = None;
        }
    }

    pub fn get_master_clock(&self) -> f64 {
        if let Ok(pts) = self.audio_pts.lock() {
            let pts_val = *pts;
            if pts_val == 0.0 {
                if let Ok(st_lock) = self.start_time.lock() {
                    if let Some(st) = *st_lock {
                        let speed = f32::from_bits(self.speed_bits.load(Ordering::Relaxed)) as f64;
                        return st.elapsed().as_secs_f64() * speed;
                    }
                }
            }
            pts_val
        } else {
            0.0
        }
    }
}
