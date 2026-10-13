//! Animated covers: the MP4 reader and the loop of pictures.
//!
//! The fixture is 24 pictures of 64 by 64 at 24 a second, H.264 High with
//! B pictures, in fragments as Apple's are. Picture n is one flat grey,
//! luma 16 + 9n, so the order pictures come out in can be read off them.

use crate::error::{EngineError, ErrorKind};
use crate::motion::{MotionCover, MotionFrame, Track};
use crate::testing::fixtures::fixture;

const RAMP: &str = "gray-ramp-64-24f-high.mp4";

fn ramp_bytes() -> Vec<u8> {
    std::fs::read(fixture(RAMP)).unwrap()
}

fn open_ramp() -> std::sync::Arc<MotionCover> {
    MotionCover::open(fixture(RAMP).to_string_lossy().into_owned()).unwrap()
}

// The grey a picture is, as the middle pixel's red.
fn grey(frame: &MotionFrame) -> i32 {
    let middle = ((frame.height / 2 * frame.width + frame.width / 2) * 4) as usize;
    frame.pixels[middle] as i32
}

// What picture n's grey is in RGB: luma 16 + 9n stretched from video range.
fn expected_grey(n: usize) -> i32 {
    ((9 * n) as f64 * 255.0 / 219.0).round() as i32
}

fn kind(error: EngineError) -> ErrorKind {
    match error {
        EngineError::Failed { kind, .. } => kind,
    }
}

#[test]
fn reads_the_track() {
    let track = Track::read(&ramp_bytes()).unwrap();
    assert_eq!(track.samples.len(), 24);
    let durations = track.shown_durations_us();
    assert_eq!(durations.len(), 24);
    // Each picture shows for a 24th of a second, to the microsecond.
    for d in &durations {
        assert!((41_666..=41_667).contains(d), "{d}");
    }
}

#[test]
fn says_its_size_and_length() {
    let cover = open_ramp();
    assert_eq!((cover.width(), cover.height()), (64, 64));
    assert_eq!(cover.frame_count(), 24);
    assert!((999_000..=1_001_000).contains(&cover.loop_us()), "{}", cover.loop_us());
}

#[test]
fn pictures_come_in_the_order_shown() {
    let cover = open_ramp();
    for n in 0..24 {
        let frame = cover.next_frame().unwrap();
        assert_eq!((frame.width, frame.height), (64, 64));
        assert_eq!(frame.pixels.len(), 64 * 64 * 4);
        assert!(frame.pixels.chunks_exact(4).all(|p| p[3] == 255), "opaque");
        let got = grey(&frame);
        assert!(
            (got - expected_grey(n)).abs() <= 4,
            "picture {n}: grey {got}, wanted about {}",
            expected_grey(n)
        );
    }
}

#[test]
fn starts_over_after_the_last() {
    let cover = open_ramp();
    let first: Vec<MotionFrame> = (0..24).map(|_| cover.next_frame().unwrap()).collect();
    for round in 1..3 {
        for (n, before) in first.iter().enumerate() {
            let again = cover.next_frame().unwrap();
            assert_eq!(grey(&again), grey(before), "round {round}, picture {n}");
            assert_eq!(again.duration_us, before.duration_us, "round {round}, picture {n}");
        }
    }
}

#[test]
fn rewinds_to_the_first() {
    let cover = open_ramp();
    for _ in 0..10 {
        cover.next_frame().unwrap();
    }
    cover.rewind();
    let frame = cover.next_frame().unwrap();
    assert!((grey(&frame) - expected_grey(0)).abs() <= 4);
}

#[test]
fn a_missing_file_is_not_found() {
    let error = MotionCover::open("/no/such/cover.mp4".into()).err().unwrap();
    assert_eq!(kind(error), ErrorKind::NotFound);
}

#[test]
fn a_file_without_fragments_is_unsupported() {
    // Only the ftyp and moov: the track described, no pictures.
    let bytes = ramp_bytes();
    let moov_end = moov_end(&bytes);
    let error = MotionCover::from_bytes(bytes[..moov_end].to_vec()).err().unwrap();
    assert_eq!(kind(error), ErrorKind::Unsupported);
}

#[test]
fn something_else_is_turned_away() {
    let bytes = std::fs::read(fixture("tone-440-44k-mono.mp3")).unwrap();
    assert!(MotionCover::from_bytes(bytes).is_err());
    assert!(MotionCover::from_bytes(Vec::new()).is_err());
}

#[test]
fn a_cut_short_file_fails_without_panicking() {
    let bytes = ramp_bytes();
    // Every length the file could be cut to, from nothing to one byte short.
    for len in (0..bytes.len()).step_by(7) {
        if let Ok(cover) = MotionCover::from_bytes(bytes[..len].to_vec()) {
            // What did read may still play, or fail plainly.
            for _ in 0..30 {
                if cover.next_frame().is_err() {
                    break;
                }
            }
        }
    }
}

// Where the moov box ends.
fn moov_end(bytes: &[u8]) -> usize {
    let mut at = 0;
    while at + 8 <= bytes.len() {
        let size = u32::from_be_bytes(bytes[at..at + 4].try_into().unwrap()) as usize;
        if &bytes[at + 4..at + 8] == b"moov" {
            return at + size;
        }
        at += size;
    }
    panic!("no moov")
}
