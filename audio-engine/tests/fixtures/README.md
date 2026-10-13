# Test fixtures

Small lossy files the decoder tests read. The sounds are pure sine tones
made by `tools/make-fixtures`, and the video is flat grey, so no recorded
music or artwork is checked in. WAV and FLAC test
files are made while the tests run instead.

| File | Contents |
| --- | --- |
| `tone-440-44k-mono.mp3` | 1 s of 440 Hz, 44.1 kHz mono, 96 kbps CBR, with the LAME header holding the encoder delay and padding |
| `tone-440-48k-stereo.opus` | 1 s of 440 Hz, 48 kHz stereo, Ogg Opus at 128 kbps, pre-skip 312 |
| `gray-ramp-64-24f-high.mp4` | 24 pictures of 64 by 64 at 24 a second, H.264 High with B pictures, in fragments as Apple Music's animated covers are; picture n is flat grey, luma 16 + 9n |

To make them again:

```sh
cd tools/make-fixtures
cargo run --release
```

The animated cover fixture is made with ffmpeg (built with libx264):

```sh
ffmpeg -f lavfi -i "nullsrc=s=64x64:r=24:d=1,format=yuv420p,geq=lum='16+N*9':cb=128:cr=128" \
    -c:v libx264 -profile:v high -bf 2 -g 12 -x264-params "b-pyramid=none:scenecut=0" \
    -bsf:v h264_metadata=aud=remove \
    -movflags +frag_keyframe+empty_moov+default_base_moof+separate_moof \
    -f mp4 gray-ramp-64-24f-high.mp4
```
