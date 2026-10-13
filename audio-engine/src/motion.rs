//! Animated covers: the short silent loops Apple Music shows in place of
//! some album covers. Each one reaches the app as a fragmented MP4 holding a
//! single H.264 video track (the app picks the H.264 copy from the HLS
//! playlist and saves it whole). This reads that file, decodes it with
//! OpenH264 and hands the app one picture at a time, round and round.
//!
//! Only what those files use is read: one video track, H.264 in `avc1`
//! samples, fragments (`moof` and `mdat`) after an empty `moov`. Anything
//! else is turned away as unsupported rather than guessed at.

use std::collections::VecDeque;
use std::sync::{Arc, Mutex};

use openh264::OpenH264API;
use openh264::decoder::{DecodedYUV, Decoder, DecoderConfig, Flush};
use openh264::formats::YUVSource;

use crate::error::{EngineError, ErrorKind};

/// One picture of an animated cover: RGBA, four bytes a pixel, rows top
/// down with no padding, and how long it stays on screen.
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct MotionFrame {
    pub width: u32,
    pub height: u32,
    pub pixels: Vec<u8>,
    pub duration_us: u64,
}

/// An animated cover read from a file, playing in a loop. Each call to
/// `next_frame` decodes the next picture in the order it is shown; after
/// the last comes the first again. Safe to share, though one reader at a
/// time makes sense: the pictures come in turn.
#[derive(uniffi::Object)]
pub struct MotionCover {
    state: Mutex<Playback>,
    width: u32,
    height: u32,
    frames: u32,
    loop_us: u64,
}

#[uniffi::export]
impl MotionCover {
    /// Reads the animated cover at `path`. Fails as unsupported for a file
    /// that is not H.264 in fragmented MP4, and as a decode failure for one
    /// that is damaged.
    #[uniffi::constructor]
    pub fn open(path: String) -> Result<Arc<Self>, EngineError> {
        let bytes = std::fs::read(&path).map_err(|e| failed(ErrorKind::NotFound, format!("{path}: {e}")))?;
        Self::from_bytes(bytes)
    }

    /// The picture's size as the file declares it.
    pub fn width(&self) -> u32 {
        self.width
    }

    pub fn height(&self) -> u32 {
        self.height
    }

    /// How many pictures one time round holds.
    pub fn frame_count(&self) -> u32 {
        self.frames
    }

    /// How long one time round lasts.
    pub fn loop_us(&self) -> u64 {
        self.loop_us
    }

    /// The next picture, starting over after the last.
    pub fn next_frame(&self) -> Result<MotionFrame, EngineError> {
        self.state.lock().unwrap_or_else(|e| e.into_inner()).next()
    }

    /// Goes back to the first picture.
    pub fn rewind(&self) {
        self.state.lock().unwrap_or_else(|e| e.into_inner()).restart();
    }
}

impl MotionCover {
    pub(crate) fn from_bytes(bytes: Vec<u8>) -> Result<Arc<Self>, EngineError> {
        let track = Track::read(&bytes)?;
        let width = track.width;
        let height = track.height;
        let frames = track.samples.len() as u32;
        let durations = track.shown_durations_us();
        let loop_us = durations.iter().sum();
        let playback = Playback::new(bytes, track, durations)?;
        Ok(Arc::new(Self { state: Mutex::new(playback), width, height, frames, loop_us }))
    }
}

fn failed(kind: ErrorKind, detail: impl Into<String>) -> EngineError {
    EngineError::Failed { kind, detail: detail.into() }
}

fn unsupported(detail: impl Into<String>) -> EngineError {
    failed(ErrorKind::Unsupported, detail)
}

fn damaged(detail: impl Into<String>) -> EngineError {
    failed(ErrorKind::Decode, detail)
}

// ---------------------------------------------------------------------------
// Decoding, in a loop.

struct Playback {
    bytes: Vec<u8>,
    track: Track,
    // How long each picture is shown, in the order they are shown.
    durations: Vec<u64>,
    decoder: Decoder,
    // The next sample to hand the decoder, in file order.
    cursor: usize,
    // Pictures out of the decoder not yet handed on: those it lets go of
    // all at once when the samples run out.
    ready: VecDeque<MotionFrame>,
    // How many pictures this time round has given, which says how long the
    // next one lasts.
    shown: usize,
    // Passes through the samples ended since a picture last came out.
    dry_passes: u32,
    scratch: Vec<u8>,
}

impl Playback {
    fn new(bytes: Vec<u8>, track: Track, durations: Vec<u64>) -> Result<Self, EngineError> {
        Ok(Self {
            decoder: new_decoder()?,
            bytes,
            track,
            durations,
            cursor: 0,
            ready: VecDeque::new(),
            shown: 0,
            dry_passes: 0,
            scratch: Vec::new(),
        })
    }

    fn next(&mut self) -> Result<MotionFrame, EngineError> {
        loop {
            if let Some(frame) = self.ready.pop_front() {
                return Ok(self.timed(frame));
            }
            if self.cursor < self.track.samples.len() {
                let sample = self.track.samples[self.cursor];
                self.cursor += 1;
                self.track.annex_b(&self.bytes, sample, &mut self.scratch)?;
                let decoded = match self.decoder.decode(&self.scratch) {
                    Ok(picture) => picture.map(|p| rgba(&p)),
                    // A damaged picture is skipped; the next key picture
                    // puts things right.
                    Err(e) => {
                        log::debug!("animated cover: picture {} skipped: {e}", self.cursor - 1);
                        None
                    }
                };
                if let Some(frame) = decoded {
                    return Ok(self.timed(frame));
                }
                continue;
            }
            // The samples are done: the pictures the decoder held back for
            // their order come out now, then the samples start again.
            let held = self.decoder.flush_remaining().map_err(|e| damaged(e.to_string()))?;
            self.ready.extend(held.iter().map(rgba));
            if self.ready.is_empty() {
                // A pass ending since the last picture came out: a second
                // one means a whole pass gave none, and none ever will.
                self.dry_passes += 1;
                if self.dry_passes > 1 {
                    return Err(damaged("no picture in the whole loop"));
                }
            }
            // The pictures waiting are the last of this round, so the count
            // lines up with the loop again however many were skipped.
            let n = self.durations.len();
            self.shown = (n - self.ready.len().min(n)) % n;
            self.cursor = 0;
            self.fresh_decoder();
        }
    }

    // Puts a picture's display time on it: the next of the loop's in turn.
    fn timed(&mut self, mut frame: MotionFrame) -> MotionFrame {
        frame.duration_us = self.durations[self.shown % self.durations.len()];
        self.shown += 1;
        self.dry_passes = 0;
        frame
    }

    // A fresh decoder for each pass: the first sample is a key picture, and
    // a used one would keep reference pictures from the end of the loop.
    fn fresh_decoder(&mut self) {
        match new_decoder() {
            Ok(decoder) => self.decoder = decoder,
            Err(e) => log::warn!("animated cover: decoder kept, a new one failed: {e}"),
        }
    }

    fn restart(&mut self) {
        self.ready.clear();
        self.cursor = 0;
        self.shown = 0;
        self.dry_passes = 0;
        self.fresh_decoder();
    }
}

// OpenH264 flushing after every sample (its default) runs out of picture
// buffers on streams with B pictures, which Apple's have: it then fails
// with "out of memory" a dozen pictures in. Left to keep pictures until it
// has them in order, it decodes them all.
fn new_decoder() -> Result<Decoder, EngineError> {
    let config = DecoderConfig::new().flush_after_decode(Flush::NoFlush);
    Decoder::with_api_config(OpenH264API::from_source(), config)
        .map_err(|e| failed(ErrorKind::Other, e.to_string()))
}

fn rgba(picture: &DecodedYUV<'_>) -> MotionFrame {
    let (width, height) = picture.dimensions();
    let mut pixels = vec![0u8; width * height * 4];
    // `write_rgba8` leaves the fourth byte as it found it: every picture is
    // opaque.
    pixels.chunks_exact_mut(4).for_each(|p| p[3] = 255);
    picture.write_rgba8(&mut pixels);
    MotionFrame { width: width as u32, height: height as u32, pixels, duration_us: 0 }
}

// ---------------------------------------------------------------------------
// Reading the MP4.

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct Sample {
    offset: usize,
    size: usize,
    // Decode time and the gap to when it is shown, in the track's units.
    decode: u64,
    shift: i64,
    duration: u32,
    key: bool,
}

#[derive(Debug)]
pub(crate) struct Track {
    width: u32,
    height: u32,
    timescale: u32,
    // How many bytes give each NAL unit's length in a sample.
    length_size: usize,
    // The sequence and picture parameter sets, sent before each key picture.
    parameter_sets: Vec<Vec<u8>>,
    pub(crate) samples: Vec<Sample>,
}

const START_CODE: [u8; 4] = [0, 0, 0, 1];

impl Track {
    pub(crate) fn read(bytes: &[u8]) -> Result<Self, EngineError> {
        let mut setup: Option<Setup> = None;
        let mut samples = Vec::new();
        for item in boxes(bytes, 0, bytes.len()) {
            let item = item?;
            match &item.kind {
                b"moov" => setup = Some(Setup::read(bytes, item)?),
                b"moof" => {
                    let setup = setup
                        .as_ref()
                        .ok_or_else(|| unsupported("fragment before the track is described"))?;
                    read_fragment(bytes, item, setup, &mut samples)?;
                }
                _ => {}
            }
        }
        let setup = setup.ok_or_else(|| unsupported("no moov box"))?;
        if samples.is_empty() {
            return Err(unsupported("no fragments: only fragmented MP4 is read"));
        }
        for sample in &samples {
            if sample.offset.checked_add(sample.size).is_none_or(|end| end > bytes.len()) {
                return Err(damaged("a sample runs past the end of the file"));
            }
        }
        Ok(Self {
            width: setup.width,
            height: setup.height,
            timescale: setup.timescale,
            length_size: setup.length_size,
            parameter_sets: setup.parameter_sets,
            samples,
        })
    }

    // How long each picture is shown, in microseconds, in the order shown:
    // the gaps between their display times. The last lasts its own duration.
    pub(crate) fn shown_durations_us(&self) -> Vec<u64> {
        let mut times: Vec<i64> = self.samples.iter().map(|s| s.decode as i64 + s.shift).collect();
        times.sort_unstable();
        let scale = self.timescale.max(1) as i128;
        let last = self.samples.last().map_or(0, |s| s.duration as i64);
        (0..times.len())
            .map(|i| {
                let gap = times.get(i + 1).map_or(last, |next| next - times[i]).max(0);
                (gap as i128 * 1_000_000 / scale) as u64
            })
            .collect()
    }

    // One sample as the decoder wants it: each NAL unit after a start code
    // in place of its length, with the parameter sets before a key picture.
    fn annex_b(&self, bytes: &[u8], sample: Sample, out: &mut Vec<u8>) -> Result<(), EngineError> {
        out.clear();
        if sample.key {
            for set in &self.parameter_sets {
                out.extend_from_slice(&START_CODE);
                out.extend_from_slice(set);
            }
        }
        let mut data = &bytes[sample.offset..sample.offset + sample.size];
        while !data.is_empty() {
            if data.len() < self.length_size {
                return Err(damaged("a NAL unit's length is cut short"));
            }
            let length = data[..self.length_size].iter().fold(0usize, |n, &b| (n << 8) | b as usize);
            data = &data[self.length_size..];
            if length > data.len() {
                return Err(damaged("a NAL unit runs past its sample"));
            }
            out.extend_from_slice(&START_CODE);
            out.extend_from_slice(&data[..length]);
            data = &data[length..];
        }
        Ok(())
    }
}

// What the `moov` box says about the video track.
struct Setup {
    track_id: u32,
    width: u32,
    height: u32,
    timescale: u32,
    length_size: usize,
    parameter_sets: Vec<Vec<u8>>,
    defaults: Defaults,
}

#[derive(Clone, Copy, Default)]
struct Defaults {
    duration: u32,
    size: u32,
    flags: u32,
}

impl Setup {
    fn read(bytes: &[u8], moov: Item) -> Result<Self, EngineError> {
        let mut video: Option<Setup> = None;
        let mut trex: Vec<(u32, Defaults)> = Vec::new();
        for item in moov.children(bytes) {
            let item = item?;
            match &item.kind {
                b"trak" => {
                    if video.is_none() {
                        video = read_video_track(bytes, item)?;
                    }
                }
                b"mvex" => {
                    for ex in item.children(bytes) {
                        let ex = ex?;
                        if &ex.kind == b"trex" {
                            let mut r = Reader::new(ex.body(bytes));
                            r.skip(4)?; // version and flags
                            let id = r.u32()?;
                            r.skip(4)?; // default sample description index
                            let defaults = Defaults { duration: r.u32()?, size: r.u32()?, flags: r.u32()? };
                            trex.push((id, defaults));
                        }
                    }
                }
                _ => {}
            }
        }
        let mut video = video.ok_or_else(|| unsupported("no H.264 video track"))?;
        if let Some((_, defaults)) = trex.iter().find(|(id, _)| *id == video.track_id) {
            video.defaults = *defaults;
        }
        Ok(video)
    }
}

// The track's setup when it is H.264 video; None for any other track.
fn read_video_track(bytes: &[u8], trak: Item) -> Result<Option<Setup>, EngineError> {
    let mut track_id = None;
    let mut handler = None;
    let mut timescale = None;
    let mut entry = None;
    for item in trak.children(bytes) {
        let item = item?;
        match &item.kind {
            b"tkhd" => {
                let mut r = Reader::new(item.body(bytes));
                let version = r.u8()?;
                r.skip(3)?;
                r.skip(if version == 1 { 16 } else { 8 })?; // creation and modification times
                track_id = Some(r.u32()?);
            }
            b"mdia" => {
                for m in item.children(bytes) {
                    let m = m?;
                    match &m.kind {
                        b"mdhd" => {
                            let mut r = Reader::new(m.body(bytes));
                            let version = r.u8()?;
                            r.skip(3)?;
                            r.skip(if version == 1 { 16 } else { 8 })?;
                            timescale = Some(r.u32()?);
                        }
                        b"hdlr" => {
                            let mut r = Reader::new(m.body(bytes));
                            r.skip(8)?; // version, flags, pre-defined
                            handler = Some(r.tag()?);
                        }
                        b"minf" => entry = find_sample_entry(bytes, m)?.or(entry),
                        _ => {}
                    }
                }
            }
            _ => {}
        }
    }
    if handler != Some(*b"vide") {
        return Ok(None);
    }
    let Some((width, height, length_size, parameter_sets)) = entry else {
        return Err(unsupported("the video is not H.264 (avc1 or avc3)"));
    };
    Ok(Some(Setup {
        track_id: track_id.ok_or_else(|| damaged("track without tkhd"))?,
        width,
        height,
        timescale: timescale.ok_or_else(|| damaged("track without mdhd"))?,
        length_size,
        parameter_sets,
        defaults: Defaults::default(),
    }))
}

// The `avc1` sample entry under minf/stbl/stsd: the picture's size and the
// `avcC` record.
type Entry = (u32, u32, usize, Vec<Vec<u8>>);

fn find_sample_entry(bytes: &[u8], minf: Item) -> Result<Option<Entry>, EngineError> {
    for stbl in minf.children(bytes) {
        let stbl = stbl?;
        if &stbl.kind != b"stbl" {
            continue;
        }
        for stsd in stbl.children(bytes) {
            let stsd = stsd?;
            if &stsd.kind != b"stsd" {
                continue;
            }
            // version, flags and the entry count come before the entries.
            let start = stsd.body_start + 8;
            for entry in boxes(bytes, start, stsd.end) {
                let entry = entry?;
                if &entry.kind != b"avc1" && &entry.kind != b"avc3" {
                    continue;
                }
                // The visual sample entry: 6 reserved, 2 index, 16 pre-defined
                // and reserved, then width and height; 78 bytes before its boxes.
                let mut r = Reader::new(entry.body(bytes));
                r.skip(24)?;
                let width = r.u16()? as u32;
                let height = r.u16()? as u32;
                for inner in boxes(bytes, entry.body_start + 78, entry.end) {
                    let inner = inner?;
                    if &inner.kind == b"avcC" {
                        let (length_size, sets) = read_avcc(inner.body(bytes))?;
                        return Ok(Some((width, height, length_size, sets)));
                    }
                }
                return Err(damaged("avc1 without avcC"));
            }
        }
    }
    Ok(None)
}

fn read_avcc(body: &[u8]) -> Result<(usize, Vec<Vec<u8>>), EngineError> {
    let mut r = Reader::new(body);
    r.skip(4)?; // version, profile, compatibility, level
    let length_size = (r.u8()? & 0b11) as usize + 1;
    let mut sets = Vec::new();
    let sps = r.u8()? & 0b1_1111;
    for _ in 0..sps {
        let n = r.u16()? as usize;
        sets.push(r.bytes(n)?.to_vec());
    }
    let pps = r.u8()?;
    for _ in 0..pps {
        let n = r.u16()? as usize;
        sets.push(r.bytes(n)?.to_vec());
    }
    Ok((length_size, sets))
}

// The samples one `moof` describes for the video track, in file order.
fn read_fragment(bytes: &[u8], moof: Item, setup: &Setup, out: &mut Vec<Sample>) -> Result<(), EngineError> {
    for traf in moof.children(bytes) {
        let traf = traf?;
        if &traf.kind != b"traf" {
            continue;
        }
        let mut defaults = setup.defaults;
        let mut base = moof.start as u64;
        let mut ours = false;
        // When no tfdt says, a fragment follows on from the last sample.
        let mut decode = out.last().map_or(0, |s: &Sample| s.decode + s.duration as u64);
        // Without its own offset, a run's data follows the run before.
        let mut next_data: Option<u64> = None;
        for item in traf.children(bytes) {
            let item = item?;
            let body = item.body(bytes);
            match &item.kind {
                b"tfhd" => {
                    let mut r = Reader::new(body);
                    let flags = r.u32()? & 0x00FF_FFFF;
                    ours = r.u32()? == setup.track_id;
                    if flags & 0x1 != 0 {
                        base = r.u64()?;
                    }
                    if flags & 0x2 != 0 {
                        r.skip(4)?;
                    }
                    if flags & 0x8 != 0 {
                        defaults.duration = r.u32()?;
                    }
                    if flags & 0x10 != 0 {
                        defaults.size = r.u32()?;
                    }
                    if flags & 0x20 != 0 {
                        defaults.flags = r.u32()?;
                    }
                }
                b"tfdt" if ours => {
                    let mut r = Reader::new(body);
                    let version = r.u8()?;
                    r.skip(3)?;
                    decode = if version == 1 { r.u64()? } else { r.u32()? as u64 };
                }
                b"trun" if ours => {
                    let mut r = Reader::new(body);
                    let version = r.u8()?;
                    let flags = r.u24()?;
                    let count = r.u32()?;
                    let mut data = if flags & 0x1 != 0 {
                        (base as i64 + r.u32()? as i32 as i64) as u64
                    } else {
                        next_data.unwrap_or(base)
                    };
                    let first_flags = if flags & 0x4 != 0 { Some(r.u32()?) } else { None };
                    for i in 0..count {
                        let duration = if flags & 0x100 != 0 { r.u32()? } else { defaults.duration };
                        let size = if flags & 0x200 != 0 { r.u32()? } else { defaults.size };
                        let sample_flags = if flags & 0x400 != 0 {
                            r.u32()?
                        } else if i == 0 {
                            first_flags.unwrap_or(defaults.flags)
                        } else {
                            defaults.flags
                        };
                        let shift = if flags & 0x800 != 0 {
                            let raw = r.u32()?;
                            if version == 0 { raw as i64 } else { raw as i32 as i64 }
                        } else {
                            0
                        };
                        // sample_is_non_sync_sample, bit 16 of the flags.
                        let key = sample_flags & 0x0001_0000 == 0;
                        out.push(Sample {
                            offset: data as usize,
                            size: size as usize,
                            decode,
                            shift,
                            duration,
                            key,
                        });
                        data += size as u64;
                        decode += duration as u64;
                    }
                    next_data = Some(data);
                }
                _ => {}
            }
        }
    }
    Ok(())
}

// ---------------------------------------------------------------------------
// Boxes.

#[derive(Clone, Copy, Debug)]
struct Item {
    kind: [u8; 4],
    start: usize,
    body_start: usize,
    end: usize,
}

impl Item {
    fn body<'a>(&self, bytes: &'a [u8]) -> &'a [u8] {
        &bytes[self.body_start..self.end]
    }

    fn children<'a>(&self, bytes: &'a [u8]) -> Boxes<'a> {
        boxes(bytes, self.body_start, self.end)
    }
}

fn boxes(bytes: &[u8], start: usize, end: usize) -> Boxes<'_> {
    Boxes { bytes, at: start, end }
}

struct Boxes<'a> {
    bytes: &'a [u8],
    at: usize,
    end: usize,
}

impl Iterator for Boxes<'_> {
    type Item = Result<Item, EngineError>;

    fn next(&mut self) -> Option<Self::Item> {
        if self.at + 8 > self.end {
            return None;
        }
        let start = self.at;
        let head = &self.bytes[start..start + 8];
        let size32 = u32::from_be_bytes([head[0], head[1], head[2], head[3]]) as u64;
        let kind = [head[4], head[5], head[6], head[7]];
        let (size, body_start) = match size32 {
            0 => ((self.end - start) as u64, start + 8),
            1 => {
                if start + 16 > self.end {
                    self.at = self.end;
                    return Some(Err(damaged("a box's size is cut short")));
                }
                let mut raw = [0u8; 8];
                raw.copy_from_slice(&self.bytes[start + 8..start + 16]);
                (u64::from_be_bytes(raw), start + 16)
            }
            n => (n, start + 8),
        };
        let end = start as u64 + size;
        if size < (body_start - start) as u64 || end > self.end as u64 {
            self.at = self.end;
            return Some(Err(damaged(format!(
                "box {} runs past its parent",
                String::from_utf8_lossy(&kind)
            ))));
        }
        self.at = end as usize;
        Some(Ok(Item { kind, start, body_start, end: end as usize }))
    }
}

struct Reader<'a> {
    data: &'a [u8],
    at: usize,
}

impl<'a> Reader<'a> {
    fn new(data: &'a [u8]) -> Self {
        Self { data, at: 0 }
    }

    fn bytes(&mut self, n: usize) -> Result<&'a [u8], EngineError> {
        let end = self
            .at
            .checked_add(n)
            .filter(|&e| e <= self.data.len())
            .ok_or_else(|| damaged("a box is cut short"))?;
        let out = &self.data[self.at..end];
        self.at = end;
        Ok(out)
    }

    fn skip(&mut self, n: usize) -> Result<(), EngineError> {
        self.bytes(n).map(|_| ())
    }

    fn u8(&mut self) -> Result<u8, EngineError> {
        Ok(self.bytes(1)?[0])
    }

    fn u16(&mut self) -> Result<u16, EngineError> {
        let b = self.bytes(2)?;
        Ok(u16::from_be_bytes([b[0], b[1]]))
    }

    fn u24(&mut self) -> Result<u32, EngineError> {
        let b = self.bytes(3)?;
        Ok(u32::from_be_bytes([0, b[0], b[1], b[2]]))
    }

    fn u32(&mut self) -> Result<u32, EngineError> {
        let b = self.bytes(4)?;
        Ok(u32::from_be_bytes([b[0], b[1], b[2], b[3]]))
    }

    fn u64(&mut self) -> Result<u64, EngineError> {
        let b = self.bytes(8)?;
        let mut raw = [0u8; 8];
        raw.copy_from_slice(b);
        Ok(u64::from_be_bytes(raw))
    }

    fn tag(&mut self) -> Result<[u8; 4], EngineError> {
        let b = self.bytes(4)?;
        Ok([b[0], b[1], b[2], b[3]])
    }
}
