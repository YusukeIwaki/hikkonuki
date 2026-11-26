# Video Slimming

`slim_videos.py` converts videos in the current directory into smaller MP4 files while:

- fitting them within `1920x1920` and preserving aspect ratio
- keeping container metadata where possible
- overwriting `creation_time` from the filename timestamp
- preserving camera identity in `make` / `model` when it can be detected

The filename must contain `YYYYMMDD_HHMMSS`, for example:

- `VID_20260322_123800_012.mp4`
- `VID_20260322_095529_00_001.mp4`

## Full Batch Commands

Software encode, quality-oriented, output as `*_slim.mp4`:

```bash
python3 slim_videos.py
```

Hardware encode on Apple Silicon / macOS, faster, output as `*_hw_slim.mp4`:

```bash
python3 slim_videos.py --video-encoder h264_videotoolbox --suffix _hw_slim.mp4
```

Overwrite existing outputs:

```bash
python3 slim_videos.py --overwrite
python3 slim_videos.py --overwrite --video-encoder h264_videotoolbox --suffix _hw_slim.mp4
```

## Typical Usage

Software mode:

- use when final visual quality matters most
- encoder is `libx264`
- output suffix is usually `*_slim.mp4`
- current default is `CRF 25`
- bitrate varies by content, so complex clips can stay relatively large

Typical command:

```bash
python3 slim_videos.py
```

Hardware mode:

- use when speed matters and you still want reasonable quality
- encoder is `h264_videotoolbox`
- output suffix is usually `*_hw_slim.mp4`
- current default bitrate policy is:
  `8M / 12M maxrate` for landscape video
  `20M / 24M maxrate` for portrait video
- this was chosen to land near the observed software sizes in this folder

Typical command:

```bash
python3 slim_videos.py --video-encoder h264_videotoolbox --suffix _hw_slim.mp4
```

If you want hardware mode to prioritize speed more aggressively:

```bash
python3 slim_videos.py --video-encoder h264_videotoolbox --suffix _hw_slim.mp4 --hw-speed
```

## Single File Examples

Software:

```bash
python3 slim_videos.py VID_20260322_123800_012.mp4
```

Hardware:

```bash
python3 slim_videos.py --video-encoder h264_videotoolbox --suffix _hw_slim.mp4 VID_20260322_123800_012.mp4
```

## Metadata Behavior

- `creation_time` is rebuilt from the filename timestamp
- timezone default is `Asia/Tokyo`
- `make` / `model` are copied when available or inferred for supported Insta360 files
- this is intended to keep Google Photos device name and shooting date usable

## Useful Options

Preview commands without running them:

```bash
python3 slim_videos.py --dry-run
```

Change timezone used for filename timestamps:

```bash
python3 slim_videos.py --timezone Asia/Tokyo
```

Force software mode explicitly:

```bash
python3 slim_videos.py --video-encoder libx264
```

Set manual hardware bitrate values:

```bash
python3 slim_videos.py --video-encoder h264_videotoolbox --suffix _hw_slim.mp4 --video-bitrate 10M --video-maxrate 14M
```
