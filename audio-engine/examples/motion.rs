//! Plays an animated cover file through the decoder without showing it:
//! two times round, then says how many pictures came, how long the loop is
//! and how long each picture took to decode.
//!
//! cargo run --release --example motion -- path/to/cover.mp4

use std::time::Instant;

use octo_audio::MotionCover;

fn main() {
    let path = std::env::args().nth(1).expect("usage: motion <file.mp4>");
    let cover = MotionCover::open(path).unwrap_or_else(|e| panic!("{e}"));
    println!(
        "{}x{}, {} pictures, loop {:.3} s",
        cover.width(),
        cover.height(),
        cover.frame_count(),
        cover.loop_us() as f64 / 1e6
    );
    let rounds = 2;
    let started = Instant::now();
    let mut shown_us = 0u64;
    for _ in 0..cover.frame_count() * rounds {
        shown_us += cover.next_frame().unwrap_or_else(|e| panic!("{e}")).duration_us;
    }
    let took = started.elapsed();
    let pictures = cover.frame_count() * rounds;
    println!(
        "{pictures} pictures in {:.2} s ({:.1} ms each), covering {:.3} s of loop",
        took.as_secs_f64(),
        took.as_secs_f64() * 1000.0 / pictures as f64,
        shown_us as f64 / 1e6
    );
}
