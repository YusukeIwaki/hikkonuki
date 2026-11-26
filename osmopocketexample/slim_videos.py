#!/usr/bin/env python3
"""Make upload copies of camera videos; never modify the camera originals."""
from __future__ import annotations

import argparse
import json
import math
import os
import platform
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from datetime import datetime, timezone
from fractions import Fraction
from pathlib import Path
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

SUPPORTED_EXTENSIONS = {".mp4", ".mov", ".m4v", ".avi", ".mkv"}
TIMESTAMP_PATTERN = re.compile(r"(?<!\d)(\d{8})_?(\d{6})(?!\d)")
DATE_KEYS = ("creation_time", "com.apple.quicktime.creationdate", "date")
COPY_AUDIO = {"aac", "mp3", "alac", "ac3", "eac3"}
ENCODERS = ("auto", "hevc_videotoolbox", "h264_videotoolbox", "libx265", "libx264")


@dataclass
class Job:
    input_path: Path
    output_path: Path
    info: dict
    video: dict
    audio: list[dict]
    width: int
    height: int
    creation_time: str | None
    date_source: str
    needs_date: bool
    camera_make: str | None
    camera_model: str | None

    @property
    def duration(self) -> float:
        return float(self.info["format"]["duration"])


def bitrate(value: str) -> int:
    match = re.fullmatch(r"(\d+(?:\.\d+)?)([kKmM]?)", value)
    if not match:
        raise ValueError(f"Invalid bitrate: {value}; use e.g. 8M or 8000k")
    result = int(float(match[1]) * {"": 1, "k": 1000, "m": 1000000}[match[2].lower()])
    if result <= 0:
        raise ValueError("Bitrate must be positive")
    return result


def parse_args(argv=None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("inputs", nargs="*", help="Files or directories; default: current directory")
    resolution = parser.add_mutually_exclusive_group()
    resolution.add_argument("--full-hd", dest="max_dimension", action="store_const", const=1920,
                            help="Full HD: up to 1920x1080 (portrait: 1080x1920). Never upscale.")
    resolution.add_argument("--2k", dest="max_dimension", action="store_const", const=2560,
                            help="2K/QHD: up to 2560x1440 (portrait: 1440x2560). Default.")
    resolution.add_argument("--max-dimension", type=int,
                            help="Custom maximum long edge in pixels (advanced option)")
    parser.set_defaults(max_dimension=2560)
    parser.add_argument("--video-encoder", choices=ENCODERS, default="auto",
                        help="auto requires macOS HEVC hardware encoding; CPU encoders are opt-in")
    parser.add_argument("--video-bitrate", default="auto",
                        help="Target bitrate; auto starts at 8M for 1080p30, 12M for 1440p30")
    parser.add_argument("--video-maxrate", default="auto", help="Peak bitrate; default: twice the target")
    parser.add_argument("--crf", type=int, default=20, help="Only for libx264/libx265; default: 20")
    parser.add_argument("--preset", default="medium", help="Only for software encoders")
    parser.add_argument("--hw-speed", action="store_true", help="Prioritize hardware encoding speed")
    parser.add_argument("--audio-bitrate", default="copy",
                        help="copy keeps compatible audio; other codecs use AAC 192k. Or specify e.g. 192k")
    parser.add_argument("--min-savings", type=float, default=20,
                        help="Skip compression saving less than this percentage; default: 20")
    parser.add_argument("--min-ssim", type=float, default=0.97,
                        help="Minimum sample SSIM against a resized original; default: 0.97")
    parser.add_argument("--sample-seconds", type=float, default=5,
                        help="Quality check length at 10/50/90 percent of each clip; default: 5")
    parser.add_argument("--force", action="store_true", help="Bypass savings and sample quality gates")
    parser.add_argument("--metadata-only", action="store_true",
                        help="Only remux files needing a date; copy video/audio without compression")
    parser.add_argument("--date-from-filename", action="store_true",
                        help="Explicitly replace dates using the filename (normally existing dates win)")
    parser.add_argument("--timezone", default="Asia/Tokyo", help="Timezone of filename timestamps")
    parser.add_argument("--suffix", default="_slim.mp4", help="Output suffix; default: _slim.mp4")
    parser.add_argument("--overwrite", action="store_true", help="Replace existing outputs after success")
    parser.add_argument("--analyze", action="store_true", help="Inspect sources without encoding or writing")
    parser.add_argument("--sample-only", action="store_true",
                        help="Check sample quality/savings without creating full output videos")
    parser.add_argument("--dry-run", action="store_true", help="Inspect and print commands without writing")
    args = parser.parse_args(argv)
    if not 128 <= args.max_dimension <= 16384 or args.max_dimension % 2:
        parser.error("--max-dimension must be an even integer between 128 and 16384")
    if not (0 <= args.min_savings < 100 and 0 <= args.min_ssim <= 1):
        parser.error("--min-savings must be in [0,100); --min-ssim in [0,1]")
    if not math.isfinite(args.sample_seconds) or not 0 < args.sample_seconds <= 60:
        parser.error("--sample-seconds must be greater than 0 and at most 60")
    if not 0 <= args.crf <= 51:
        parser.error("--crf must be in [0,51]")
    if (not args.suffix.lower().endswith(".mp4") or Path(args.suffix).name != args.suffix
            or "\\" in args.suffix):
        parser.error("--suffix must be a filename suffix ending in .mp4, without directories")
    for value in (args.video_bitrate, args.video_maxrate):
        if value != "auto":
            bitrate(value)
    if args.audio_bitrate != "copy":
        bitrate(args.audio_bitrate)
    try:
        ZoneInfo(args.timezone)
    except (ZoneInfoNotFoundError, ValueError):
        parser.error(f"Invalid timezone: {args.timezone}")
    return args


def probe(path: Path) -> dict:
    result = subprocess.run(["ffprobe", "-v", "error", "-show_format", "-show_streams",
                             "-of", "json", str(path)], check=True, capture_output=True, text=True)
    return json.loads(result.stdout)


def parse_date(value: str | None) -> datetime | None:
    if not value:
        return None
    try:
        dt = datetime.fromisoformat(value.strip().replace("Z", "+00:00"))
        if dt.year < 1980:  # QuickTime's unset dates commonly resolve to 1904 or 1970.
            return None
        return dt.replace(tzinfo=dt.tzinfo or timezone.utc).astimezone(timezone.utc)
    except (ValueError, TypeError):
        return None


def filename_to_creation_time(filename: str, timezone_name: str) -> str:
    match = TIMESTAMP_PATTERN.search(filename)
    if not match:
        raise ValueError(f"No YYYYMMDDHHMMSS or YYYYMMDD_HHMMSS timestamp in {filename}")
    local = datetime.strptime(match[1] + match[2], "%Y%m%d%H%M%S").replace(tzinfo=ZoneInfo(timezone_name))
    return local.astimezone(timezone.utc).isoformat(timespec="microseconds").replace("+00:00", "Z")


def choose_date(info: dict, video: dict, path: Path, args) -> tuple[str | None, str, bool]:
    tags = info["format"].get("tags", {})
    if args.date_from_filename:
        return filename_to_creation_time(path.name, args.timezone), "filename (explicit override)", True
    sources = [("container", tags), ("video", video.get("tags", {}))]
    sources += [("stream", s.get("tags", {})) for s in info["streams"]
                if s.get("codec_type") in {"video", "audio"} and s is not video]
    for label, source in sources:
        for key in DATE_KEYS:
            dt = parse_date(source.get(key))
            if dt is not None:
                canonical = dt.isoformat(timespec="microseconds").replace("+00:00", "Z")
                return canonical, f"{label}.{key} (preserved)", parse_date(tags.get("creation_time")) is None
    try:
        return filename_to_creation_time(path.name, args.timezone), "filename (missing metadata)", True
    except ValueError:
        return None, "unknown (no valid metadata or filename timestamp)", False


def discover_inputs(raw_inputs, suffix: str) -> list[Path]:
    files = set()
    for item in raw_inputs or [str(Path.cwd())]:
        seed = Path(item).expanduser().resolve()
        if not seed.exists():
            raise ValueError(f"Input does not exist: {seed}")
        for path in sorted(seed.iterdir()) if seed.is_dir() else [seed]:
            if (path.is_file() and path.suffix.lower() in SUPPORTED_EXTENSIONS
                    and not path.name.lower().endswith(suffix.lower())
                    and not path.stem.lower().endswith("_slim")
                    and not path.name.startswith(".slim-")):
                files.add(path.resolve())
    return sorted(files)


def build_job(path: Path, args) -> Job:
    output = path.with_name(path.stem + args.suffix)
    if output.resolve() == path.resolve():
        raise ValueError("Output would overwrite the original; choose a different --suffix")
    info = probe(path)
    videos = [s for s in info["streams"] if s.get("codec_type") == "video"
              and not s.get("disposition", {}).get("attached_pic")]
    if not videos:
        raise ValueError(f"No main video stream: {path.name}")
    video = videos[0]
    width, height = video["width"], video["height"]
    for side in video.get("side_data_list", []):
        if round(float(side.get("rotation", 0))) % 180 == 90:
            width, height = height, width
            break
    if float(info["format"].get("duration", 0)) <= 0:
        raise ValueError(f"No usable duration: {path.name}")
    date, source, needs_date = choose_date(info, video, path, args)
    tags = info["format"].get("tags", {})
    make = tags.get("make") or tags.get("com.apple.quicktime.make")
    model = tags.get("model") or tags.get("com.apple.quicktime.model")
    encoder = tags.get("encoder", "")
    if encoder.startswith("DJI "):
        make = make or "DJI"
        model = model or encoder.removeprefix("DJI ")
    return Job(path, output, info, video,
               [s for s in info["streams"] if s.get("codec_type") == "audio"],
               width, height, date, source, needs_date, make, model)


def target_dimensions(job: Job, maximum: int) -> tuple[int, int]:
    ratio = min(1, maximum / max(job.width, job.height))
    return max(2, int(job.width * ratio) // 2 * 2), max(2, int(job.height * ratio) // 2 * 2)


def is_ten_bit(video: dict) -> bool:
    return any(token in video.get("pix_fmt", "") for token in ("10", "12", "16"))


def resolve_video_encoder(requested: str) -> str:
    if requested != "auto":
        return requested
    if platform.system() != "Darwin":
        raise ValueError("auto uses macOS VideoToolbox. Select --video-encoder libx265 for CPU encoding.")
    result = subprocess.run(["ffmpeg", "-hide_banner", "-encoders"], check=True,
                            capture_output=True, text=True)
    if "hevc_videotoolbox" not in result.stdout:
        raise ValueError("This FFmpeg has no hevc_videotoolbox encoder")
    return "hevc_videotoolbox"


def rates(job: Job, args) -> tuple[int, int]:
    w, h = target_dimensions(job, args.max_dimension)
    try:
        fps = float(Fraction(job.video.get("avg_frame_rate", "30"))) or 30
    except (ValueError, ZeroDivisionError):
        fps = 30
    auto = max(750_000, round(8_000_000 * (w * h / (1920 * 1080)) ** 0.75
                              * max(1, fps / 30) / 1_000_000) * 1_000_000)
    target = auto if args.video_bitrate == "auto" else bitrate(args.video_bitrate)
    peak = target * 2 if args.video_maxrate == "auto" else bitrate(args.video_maxrate)
    if peak < target:
        raise ValueError("--video-maxrate cannot be lower than --video-bitrate")
    return target, peak


def color_skip_reason(job: Job) -> str | None:
    transfer = job.video.get("color_transfer", "unknown")
    gamma = job.info["format"].get("tags", {}).get("com.dji.camera.ColorGammaSxS", "")
    if transfer in {"smpte2084", "arib-std-b67"} or "log" in gamma.lower():
        return "HDR/Log footage needs a separate color-managed workflow; compression skipped"
    if any("dovi" in str(s).lower() for s in job.video.get("side_data_list", [])):
        return "Dolby Vision metadata cannot be preserved by this encoding workflow"
    return None


def build_ffmpeg_command(job: Job, args, encoder: str, output: Path,
                         copy_video=False, start=None, duration=None) -> list[str]:
    command = ["ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y"]
    if not copy_video and encoder.endswith("_videotoolbox"):
        command += ["-hwaccel", "videotoolbox"]
    if start is not None:
        command += ["-ss", str(start)]
    command += ["-i", str(job.input_path)]
    if duration is not None:
        command += ["-t", str(duration)]
    # Only the main video and audio: DJI djmd/dbgi/tmcd and JPEG thumbnails are excluded.
    command += ["-map", f"0:{job.video['index']}"]
    for stream in job.audio:
        command += ["-map", f"0:{stream['index']}"]
    command += ["-map_metadata", "0", "-map_chapters", "0",
                "-movflags", "+faststart+use_metadata_tags", "-write_tmcd", "0"]
    if copy_video:
        command += ["-c", "copy"]
    else:
        w, h = target_dimensions(job, args.max_dimension)
        ten_bit = is_ten_bit(job.video) and encoder in {"hevc_videotoolbox", "libx265"}
        command += ["-vf", f"scale={w}:{h}:flags=lanczos", "-c:v", encoder,
                    "-fps_mode:v", "passthrough"]
        if encoder.endswith("_videotoolbox"):
            target, peak = rates(job, args)
            command += ["-allow_sw", "0", "-b:v", str(target), "-maxrate", str(peak),
                        "-bufsize", str(peak * 2), "-pix_fmt", "p010le" if ten_bit else "yuv420p",
                        "-profile:v", ("main10" if ten_bit else "main")
                        if encoder.startswith("hevc") else "high"]
            if args.hw_speed:
                command += ["-realtime", "1", "-prio_speed", "1"]
        else:
            command += ["-crf", str(args.crf), "-preset", args.preset,
                        "-pix_fmt", "yuv420p10le" if ten_bit else "yuv420p"]
        if encoder in {"hevc_videotoolbox", "libx265"}:
            command += ["-tag:v", "hvc1"]
        for flag, key in (("-color_primaries", "color_primaries"), ("-color_trc", "color_transfer"),
                          ("-colorspace", "color_space"), ("-color_range", "color_range")):
            value = job.video.get(key)
            if value and value not in {"unknown", "unspecified", "reserved"}:
                command += [flag, value]
        for index, stream in enumerate(job.audio):
            copy = args.audio_bitrate == "copy" and stream.get("codec_name") in COPY_AUDIO
            command += [f"-c:a:{index}", "copy" if copy else "aac"]
            if not copy:
                command += [f"-b:a:{index}", "192k" if args.audio_bitrate == "copy" else args.audio_bitrate]
    if job.camera_make:
        command += ["-metadata", f"make={job.camera_make}"]
    if job.camera_model:
        command += ["-metadata", f"model={job.camera_model}"]
    if job.creation_time:
        if job.needs_date:
            command += ["-metadata", f"creation_time={job.creation_time}"]
            if args.date_from_filename:
                for key in DATE_KEYS[1:]:
                    if key in job.info["format"].get("tags", {}):
                        command += ["-metadata", f"{key}={job.creation_time}"]
        for index, stream in enumerate([job.video] + job.audio):
            if args.date_from_filename or parse_date(stream.get("tags", {}).get("creation_time")) is None:
                command += [f"-metadata:s:{index}", f"creation_time={job.creation_time}"]
    return command + [str(output)]


def sample_positions(duration: float, seconds: float) -> list[tuple[float, float]]:
    length = min(seconds, duration)
    fractions = (0.1, 0.5, 0.9) if duration >= seconds * 3 else (0.5,)
    return [(round((duration - length) * f, 6), length) for f in fractions]


def sample_ssim(job: Job, args, sample: Path, start: float, duration: float) -> float:
    w, h = target_dimensions(job, args.max_dimension)
    pixel_format = "yuv420p10le" if is_ten_bit(job.video) else "yuv420p"
    graph = (f"[0:{job.video['index']}]trim=duration={duration},setpts=PTS-STARTPTS,"
             f"scale={w}:{h}:flags=lanczos,format={pixel_format}[ref];"
             f"[1:V:0]setpts=PTS-STARTPTS,format={pixel_format}[dist];"
             "[dist][ref]ssim=shortest=1")
    result = subprocess.run(["ffmpeg", "-nostdin", "-hide_banner", "-nostats", "-ss", str(start),
                             "-i", str(job.input_path), "-i", str(sample), "-filter_complex", graph,
                             "-an", "-f", "null", "-"], capture_output=True, text=True, check=True)
    match = re.search(r"\bAll:([0-9.]+)", result.stderr)
    if not match:
        raise ValueError("FFmpeg did not return a sample SSIM score")
    return float(match[1])


def check_samples(job: Job, args, encoder: str) -> tuple[float, float]:
    scores, total_bytes, total_seconds = [], 0, 0.0
    with tempfile.TemporaryDirectory(prefix=".slim-check-", dir=job.output_path.parent) as folder:
        for number, (start, duration) in enumerate(sample_positions(job.duration, args.sample_seconds)):
            sample = Path(folder) / f"sample_{number}.mp4"
            subprocess.run(build_ffmpeg_command(job, args, encoder, sample, start=start, duration=duration),
                           check=True)
            scores.append(sample_ssim(job, args, sample, start, duration))
            total_bytes += sample.stat().st_size
            total_seconds += float(probe(sample)["format"]["duration"])
    estimate = total_bytes / total_seconds * job.duration
    savings = 100 * (1 - estimate / job.input_path.stat().st_size)
    print(f"sample SSIM min={min(scores):.6f}; estimated saving={savings:.1f}%", flush=True)
    return min(scores), savings


def select_compression(job: Job, args, encoder: str) -> tuple[argparse.Namespace, bool]:
    selected = argparse.Namespace(**vars(args))
    initial, _ = rates(job, args)
    adaptive = args.video_bitrate == "auto" and encoder.endswith("_videotoolbox")
    for attempt in range(4 if adaptive else 1):
        if attempt:
            target = round(initial * 1.5 ** attempt / 1_000_000) * 1_000_000
            if args.video_maxrate != "auto" and target > bitrate(args.video_maxrate):
                break
            selected.video_bitrate = str(target)
            print(f"retry  target {target / 1e6:g}M to preserve sample quality", flush=True)
        score, savings = check_samples(job, selected, encoder)
        if savings < args.min_savings:
            break
        if score >= args.min_ssim:
            return selected, True
    return selected, False


def validate_output(job: Job, output: Path, args, copy_video: bool, encoder: str) -> None:
    info = probe(output)
    video = next(s for s in info["streams"] if s.get("codec_type") == "video")
    if not copy_video:
        if (video["width"], video["height"]) != target_dimensions(job, args.max_dimension):
            raise ValueError("Output resolution differs from the plan")
        if is_ten_bit(job.video) and encoder in {"hevc_videotoolbox", "libx265"} and not is_ten_bit(video):
            raise ValueError("Output lost 10-bit precision")
    if len([s for s in info["streams"] if s.get("codec_type") == "audio"]) != len(job.audio):
        raise ValueError("Output audio stream count differs from the source")
    if abs(float(info["format"]["duration"]) - job.duration) > 0.25:
        raise ValueError("Output duration differs from the source by more than 0.25 seconds")
    if job.creation_time and parse_date(info["format"].get("tags", {}).get("creation_time")) != parse_date(job.creation_time):
        raise ValueError("Output creation_time was not preserved")
    if not copy_video and job.video.get("nb_frames") and video.get("nb_frames"):
        if job.video["nb_frames"] != video["nb_frames"]:
            raise ValueError("Output frame count differs from the source")


def write_output(job: Job, args, encoder: str, copy_video: bool) -> None:
    fd, name = tempfile.mkstemp(prefix=".slim-", suffix=".mp4", dir=job.output_path.parent)
    os.close(fd)
    temporary = Path(name)
    try:
        subprocess.run(build_ffmpeg_command(job, args, encoder, temporary, copy_video), check=True)
        validate_output(job, temporary, args, copy_video, encoder)
        savings = 100 * (1 - temporary.stat().st_size / job.input_path.stat().st_size)
        if not copy_video and not args.force and savings < args.min_savings:
            print(f"skip: actual saving {savings:.1f}% is below {args.min_savings:g}%")
            if job.needs_date:
                write_output(job, args, encoder, copy_video=True)
            return
        if args.overwrite:
            os.replace(temporary, job.output_path)
        else:
            os.link(temporary, job.output_path)  # Atomic failure if the destination now exists.
        print(f"done   {job.output_path.name}: {job.output_path.stat().st_size / 1e9:.3f} GB "
              f"({savings:.1f}% smaller; {'metadata remux' if copy_video else encoder})\n", flush=True)
        if job.output_path.stat().st_size > 10_000_000_000:
            print("warning: output still exceeds Google Photos' 10 GB upload limit", file=sys.stderr)
    finally:
        temporary.unlink(missing_ok=True)


def main(argv=None) -> int:
    args = parse_args(argv)
    for binary in (["ffprobe"] if args.analyze else ["ffprobe", "ffmpeg"]):
        if shutil.which(binary) is None:
            raise ValueError(f"{binary} is not available in PATH")
    paths = discover_inputs(args.inputs, args.suffix)
    if not paths:
        print("No source videos found.", file=sys.stderr)
        return 1
    encoder = "copy" if args.metadata_only or args.analyze else resolve_video_encoder(args.video_encoder)
    failed = False
    for path in paths:
        try:
            job = build_job(path, args)
            w, h = target_dimensions(job, args.max_dimension)
            print(f"input  {path.name}: {job.width}x{job.height}, {job.video.get('codec_name')}, "
                  f"{job.video.get('pix_fmt')}, {job.duration:.2f}s, {path.stat().st_size / 1e9:.3f} GB")
            print(f"date   {job.creation_time or '-'} [{job.date_source}]", flush=True)
            try:
                filename_date = parse_date(filename_to_creation_time(path.name, args.timezone))
                if job.creation_time and abs((parse_date(job.creation_time) - filename_date).total_seconds()) > 2:
                    print("note: filename and embedded dates differ; the selected date above wins")
            except ValueError:
                pass
            if path.stat().st_size > 10_000_000_000:
                print("note: source exceeds Google Photos' 10 GB upload limit")
            if args.analyze:
                print(f"plan   {w}x{h}; date {'needs filling' if job.needs_date else 'kept'}\n")
                continue
            if job.output_path.exists() and not args.overwrite and not args.sample_only:
                print(f"skip   {job.output_path.name}: output already exists\n")
                continue
            copy_video = args.metadata_only
            if copy_video and not job.needs_date:
                print("skip: no date repair needed\n")
                continue
            if not copy_video:
                reason = color_skip_reason(job)
                if reason:
                    print(f"skip compression: {reason}")
                    if not job.needs_date:
                        print()
                        continue
                    copy_video = True
                else:
                    target, _ = rates(job, args)
                    print(f"plan   {w}x{h}, {encoder}, target {target / 1e6:g}M; audio {args.audio_bitrate}")
                    if is_ten_bit(job.video) and encoder in {"h264_videotoolbox", "libx264"}:
                        print("note: the explicitly selected H.264 encoder converts 10-bit SDR to 8-bit")
            if args.dry_run:
                command = build_ffmpeg_command(job, args, encoder, job.output_path, copy_video)
                command[command.index("-y")] = "-y" if args.overwrite else "-n"
                print("cmd    " + shlex.join(command))
                print("note: a real run also checks sample SSIM/savings and validates the temporary output\n")
                continue
            selected = args
            if not copy_video and (not args.force or args.sample_only):
                selected, acceptable = select_compression(job, args, encoder)
                if args.sample_only:
                    print(f"result {'compress' if acceptable else 'keep original'}; "
                          f"target {rates(job, selected)[0] / 1e6:g}M\n", flush=True)
                    continue
                if not acceptable:
                    print("skip compression: sample quality or savings below threshold")
                    if not job.needs_date:
                        print("use the original for this video\n")
                        continue
                    copy_video = True
                    print("plan: repair date with video/audio stream copy")
            if args.sample_only:
                print("result date repair only; no full output written\n")
                continue
            write_output(job, selected, encoder, copy_video)
        except (ValueError, OSError, subprocess.CalledProcessError, KeyError, StopIteration) as exc:
            print(f"failed {path.name}: {exc}", file=sys.stderr, flush=True)
            if isinstance(exc, subprocess.CalledProcessError) and exc.stderr:
                print(exc.stderr[-1600:], file=sys.stderr)
            failed = True
    return int(failed)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ValueError, OSError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        raise SystemExit(2)
    except KeyboardInterrupt:
        print("\nInterrupted; originals and completed outputs are intact.", file=sys.stderr)
        raise SystemExit(130)
