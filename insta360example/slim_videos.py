#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import platform
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable
from zoneinfo import ZoneInfo
import re


SUPPORTED_EXTENSIONS = {".mp4", ".mov", ".m4v", ".avi", ".mkv"}
TIMESTAMP_PATTERN = re.compile(r"(?P<date>\d{8})_(?P<time>\d{6})")


@dataclass(frozen=True)
class Job:
    input_path: Path
    output_path: Path
    creation_time_utc: str
    camera_make: str | None
    camera_model: str | None
    video_width: int
    video_height: int


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Shrink videos into *_slim.mp4 while preserving metadata and "
            "overwriting creation_time from the filename."
        )
    )
    parser.add_argument(
        "inputs",
        nargs="*",
        help="Video files or directories. If omitted, process supported videos in the current directory.",
    )
    parser.add_argument(
        "--max-dimension",
        type=int,
        default=1920,
        help="Fit videos within this square box while preserving aspect ratio. Default: 1920",
    )
    parser.add_argument(
        "--video-encoder",
        choices=["auto", "libx264", "h264_videotoolbox"],
        default="auto",
        help=(
            "Video encoder. 'auto' uses h264_videotoolbox on macOS when available, "
            "otherwise libx264. Default: auto"
        ),
    )
    parser.add_argument(
        "--crf",
        type=int,
        default=25,
        help="libx264 CRF. Used only with libx264. Lower is higher quality and larger files. Default: 25",
    )
    parser.add_argument(
        "--preset",
        default="medium",
        help="libx264 preset. Used only with libx264. Default: medium",
    )
    parser.add_argument(
        "--video-bitrate",
        default="auto",
        help="Target bitrate for h264_videotoolbox. Use 'auto' to choose by orientation. Default: auto",
    )
    parser.add_argument(
        "--video-maxrate",
        default="auto",
        help="Peak bitrate for h264_videotoolbox. Use 'auto' to choose by orientation. Default: auto",
    )
    parser.add_argument(
        "--hw-speed",
        action="store_true",
        help="Prioritize speed for h264_videotoolbox. By default hardware mode favors quality.",
    )
    parser.add_argument(
        "--audio-bitrate",
        default="128k",
        help="AAC audio bitrate. Default: 128k",
    )
    parser.add_argument(
        "--timezone",
        default="Asia/Tokyo",
        help=(
            "Timezone used when converting filename timestamps to creation_time. "
            "Default: Asia/Tokyo"
        ),
    )
    parser.add_argument(
        "--overwrite",
        action="store_true",
        help="Overwrite existing *_slim.mp4 files.",
    )
    parser.add_argument(
        "--suffix",
        default="_slim.mp4",
        help="Output filename suffix. Default: _slim.mp4",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Print the ffmpeg commands without running them.",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    ensure_binary("ffmpeg")
    ensure_binary("ffprobe")

    timezone_name = validate_timezone(args.timezone)
    video_encoder = resolve_video_encoder(args.video_encoder)
    jobs = build_jobs(
        args.inputs,
        timezone_name,
        overwrite=args.overwrite,
        output_suffix=args.suffix,
    )
    if not jobs:
        print("No videos found to process.", file=sys.stderr)
        return 1

    failed = False
    for job in jobs:
        if job.output_path.exists() and not args.overwrite:
            print(f"skip {job.output_path.name}: output already exists")
            continue

        command = build_ffmpeg_command(
            job=job,
            max_dimension=args.max_dimension,
            video_encoder=video_encoder,
            crf=args.crf,
            preset=args.preset,
            video_bitrate=args.video_bitrate,
            video_maxrate=args.video_maxrate,
            hw_speed=args.hw_speed,
            audio_bitrate=args.audio_bitrate,
        )

        print(f"input  {job.input_path.name}")
        print(f"output {job.output_path.name}")
        print(f"date   {job.creation_time_utc}")

        if args.dry_run:
            print("cmd    " + " ".join(shell_quote(part) for part in command))
            print()
            continue

        try:
            subprocess.run(command, check=True)
            print("status done\n")
        except subprocess.CalledProcessError:
            print("status failed\n", file=sys.stderr)
            failed = True

    return 1 if failed else 0


def ensure_binary(name: str) -> None:
    result = subprocess.run(
        [name, "-version"],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        check=False,
    )
    if result.returncode != 0:
        raise SystemExit(f"{name} is not available in PATH")


def validate_timezone(name: str) -> str:
    try:
        ZoneInfo(name)
    except Exception as exc:
        raise SystemExit(f"Invalid timezone: {name}") from exc
    return name


def resolve_video_encoder(requested: str) -> str:
    if requested != "auto":
        return requested

    if platform.system() == "Darwin" and ffmpeg_supports_encoder("h264_videotoolbox"):
        return "h264_videotoolbox"
    return "libx264"


def ffmpeg_supports_encoder(name: str) -> bool:
    result = subprocess.run(
        ["ffmpeg", "-hide_banner", "-encoders"],
        capture_output=True,
        text=True,
        check=True,
    )
    return name in result.stdout


def build_jobs(
    raw_inputs: Iterable[str],
    timezone_name: str,
    overwrite: bool,
    output_suffix: str,
) -> list[Job]:
    candidates = discover_inputs(raw_inputs)
    jobs: list[Job] = []

    for path in candidates:
        if path.name.endswith("_slim.mp4"):
            continue

        output_path = path.with_name(f"{path.stem}{output_suffix}")
        camera_make, camera_model = detect_camera_identity(path)
        video_width, video_height = probe_video_dimensions(path)
        if output_path.exists() and not overwrite:
            jobs.append(
                Job(
                    input_path=path,
                    output_path=output_path,
                    creation_time_utc=filename_to_creation_time(path.name, timezone_name),
                    camera_make=camera_make,
                    camera_model=camera_model,
                    video_width=video_width,
                    video_height=video_height,
                )
            )
            continue

        jobs.append(
            Job(
                input_path=path,
                output_path=output_path,
                creation_time_utc=filename_to_creation_time(path.name, timezone_name),
                camera_make=camera_make,
                camera_model=camera_model,
                video_width=video_width,
                video_height=video_height,
            )
        )

    return jobs


def discover_inputs(raw_inputs: Iterable[str]) -> list[Path]:
    if raw_inputs:
        seeds = [Path(item) for item in raw_inputs]
    else:
        seeds = [Path.cwd()]

    files: list[Path] = []
    for seed in seeds:
        if seed.is_dir():
            files.extend(
                sorted(
                    candidate
                    for candidate in seed.iterdir()
                    if candidate.is_file() and candidate.suffix.lower() in SUPPORTED_EXTENSIONS
                )
            )
        elif seed.is_file() and seed.suffix.lower() in SUPPORTED_EXTENSIONS:
            files.append(seed)
    return files


def filename_to_creation_time(filename: str, timezone_name: str) -> str:
    match = TIMESTAMP_PATTERN.search(filename)
    if not match:
        raise SystemExit(
            f"Could not extract YYYYMMDD_HHMMSS from filename: {filename}"
        )

    local_dt = datetime.strptime(
        f"{match.group('date')}{match.group('time')}",
        "%Y%m%d%H%M%S",
    ).replace(tzinfo=ZoneInfo(timezone_name))
    utc_dt = local_dt.astimezone(timezone.utc)
    return utc_dt.strftime("%Y-%m-%dT%H:%M:%S.000000Z")


def probe_stream_indexes(path: Path) -> list[int]:
    result = subprocess.run(
        [
            "ffprobe",
            "-v",
            "error",
            "-show_entries",
            "stream=index",
            "-of",
            "json",
            str(path),
        ],
        capture_output=True,
        text=True,
        check=True,
    )
    payload = json.loads(result.stdout)
    return [stream["index"] for stream in payload.get("streams", [])]


def probe_video_dimensions(path: Path) -> tuple[int, int]:
    result = subprocess.run(
        [
            "ffprobe",
            "-v",
            "error",
            "-select_streams",
            "v:0",
            "-show_entries",
            "stream=width,height",
            "-of",
            "json",
            str(path),
        ],
        capture_output=True,
        text=True,
        check=True,
    )
    payload = json.loads(result.stdout)
    streams = payload.get("streams", [])
    if not streams:
        raise SystemExit(f"No video stream found: {path}")
    stream = streams[0]
    return int(stream["width"]), int(stream["height"])


def probe_tags(path: Path) -> tuple[dict[str, str], list[dict[str, str]]]:
    result = subprocess.run(
        [
            "ffprobe",
            "-v",
            "error",
            "-show_entries",
            "format_tags:stream_tags",
            "-of",
            "json",
            str(path),
        ],
        capture_output=True,
        text=True,
        check=True,
    )
    payload = json.loads(result.stdout)
    format_tags = payload.get("format", {}).get("tags", {}) or {}
    stream_tags = [stream.get("tags", {}) or {} for stream in payload.get("streams", [])]
    return format_tags, stream_tags


def detect_camera_identity(path: Path) -> tuple[str | None, str | None]:
    format_tags, stream_tags = probe_tags(path)
    make, model = read_camera_tags(format_tags)
    if make or model:
        return normalize_camera_identity(make, model)

    for tags in stream_tags:
        make, model = read_camera_tags(tags)
        if make or model:
            return normalize_camera_identity(make, model)

    inferred_model = scan_insta360_model(path)
    if inferred_model:
        return "Insta360", inferred_model

    return None, None


def read_camera_tags(tags: dict[str, str]) -> tuple[str | None, str | None]:
    make = (
        tags.get("make")
        or tags.get("com.apple.quicktime.make")
    )
    model = (
        tags.get("model")
        or tags.get("com.apple.quicktime.model")
    )
    return make, model


def normalize_camera_identity(
    make: str | None,
    model: str | None,
) -> tuple[str | None, str | None]:
    make = make.strip() if make else None
    model = model.strip() if model else None

    if not make and model and model.startswith("Insta360"):
        make = "Insta360"
    return make, model


def scan_insta360_model(path: Path) -> str | None:
    pattern = re.compile(rb"Insta360(?: [A-Za-z0-9.+_-]+){1,5}")
    overlap = 128
    chunk_size = 1024 * 1024
    tail = b""
    matches: set[str] = set()

    with path.open("rb") as handle:
        while True:
            chunk = handle.read(chunk_size)
            if not chunk:
                break
            data = tail + chunk
            for match in pattern.findall(data):
                candidate = match.decode("ascii", errors="ignore").strip()
                if candidate != "Insta360":
                    matches.add(candidate)
            tail = data[-overlap:]

    if not matches:
        return None
    return sorted(matches, key=len, reverse=True)[0]


def build_ffmpeg_command(
    job: Job,
    max_dimension: int,
    video_encoder: str,
    crf: int,
    preset: str,
    video_bitrate: str,
    video_maxrate: str,
    hw_speed: bool,
    audio_bitrate: str,
) -> list[str]:
    stream_indexes = probe_stream_indexes(job.input_path)
    video_bitrate, video_maxrate = resolve_hw_bitrate(
        job.video_width,
        job.video_height,
        video_bitrate,
        video_maxrate,
    )
    command = [
        "ffmpeg",
        "-y",
        "-hide_banner",
        "-loglevel",
        "error",
        "-i",
        str(job.input_path),
        "-map",
        "0",
        "-map_metadata",
        "0",
        "-map_chapters",
        "0",
        "-movflags",
        "+faststart+use_metadata_tags",
        "-vf",
        (
            f"scale={max_dimension}:{max_dimension}:"
            "force_original_aspect_ratio=decrease:"
            "force_divisible_by=2"
        ),
        "-c:a",
        "aac",
        "-b:a",
        audio_bitrate,
        "-c:s",
        "copy",
        "-metadata",
        f"creation_time={job.creation_time_utc}",
    ]

    if video_encoder == "libx264":
        command.extend(
            [
                "-c:v",
                "libx264",
                "-preset",
                preset,
                "-crf",
                str(crf),
                "-pix_fmt",
                "yuv420p",
            ]
        )
    elif video_encoder == "h264_videotoolbox":
        command.extend(
            [
                "-c:v",
                "h264_videotoolbox",
                "-profile:v",
                "high",
                "-b:v",
                video_bitrate,
                "-maxrate",
                video_maxrate,
                "-bufsize",
                video_maxrate,
                "-pix_fmt",
                "yuv420p",
            ]
        )
        if hw_speed:
            command.extend(
                [
                    "-realtime",
                    "1",
                    "-prio_speed",
                    "1",
                ]
            )
    else:
        raise SystemExit(f"Unsupported video encoder: {video_encoder}")

    if job.camera_make:
        command.extend(["-metadata", f"make={job.camera_make}"])
    if job.camera_model:
        command.extend(["-metadata", f"model={job.camera_model}"])

    for stream_index in stream_indexes:
        command.extend(
            [
                f"-metadata:s:{stream_index}",
                f"creation_time={job.creation_time_utc}",
            ]
        )

    command.append(str(job.output_path))
    return command


def shell_quote(value: str) -> str:
    if not value:
        return "''"
    if re.fullmatch(r"[A-Za-z0-9_./:=+-]+", value):
        return value
    return "'" + value.replace("'", "'\"'\"'") + "'"


def resolve_hw_bitrate(
    width: int,
    height: int,
    requested_bitrate: str,
    requested_maxrate: str,
) -> tuple[str, str]:
    if requested_bitrate != "auto" or requested_maxrate != "auto":
        bitrate = requested_bitrate if requested_bitrate != "auto" else requested_maxrate
        maxrate = requested_maxrate if requested_maxrate != "auto" else requested_bitrate
        return bitrate, maxrate

    if height > width:
        return "20M", "24M"
    return "8M", "12M"


if __name__ == "__main__":
    raise SystemExit(main())
