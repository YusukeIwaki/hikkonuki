#!/usr/bin/env python3
"""Builds small test fixtures that keep the real container layout of the camera files.

Media data, thumbnails and padding are dropped (so the files do not play), but every
header the app reads or rewrites is the camera's own. Serial numbers are blanked.

The camera originals are not part of the repository; pass one recording from each camera:

    python3 tools/make_fixtures.py --insta360 VID_20260510_132733_019.mp4 --dji DJI_20261107113403_0005_D.MP4

Either may be omitted to leave that fixture as it is. The date and model assertions in
Mp4Test describe the recordings the committed fixtures were made from, so update them
when regenerating from different files.
"""
import argparse, re, struct, subprocess, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "app/src/test/resources/fixtures"
CONTAINERS = {b"moov", b"trak", b"mdia", b"udta"}
# Thumbnails, padding, sample tables, and boxes holding device/file identifiers.
DROP = {b"nail", b"free", b"mdat", b"stbl", b"Xtra", b"btec", b"\xa9uid", b"fsid"}


def boxes(data, start, end):
    pos = start
    while pos + 8 <= end:
        size, kind = struct.unpack(">I4s", data[pos:pos + 8])
        header = 8
        if size == 1:
            size = struct.unpack(">Q", data[pos + 8:pos + 16])[0]
            header = 16
        elif size == 0:
            size = end - pos
        if size < header or pos + size > end:
            break
        yield kind, pos, header, size
        pos += size


def box(kind, payload):
    return struct.pack(">I4s", 8 + len(payload), kind) + payload


def shrink(data, start, end, path=b""):
    out = b""
    for kind, pos, header, size in boxes(data, start, end):
        payload = data[pos + header:pos + size]
        if kind in DROP:
            continue
        if kind in CONTAINERS:
            out += box(kind, shrink(data, pos + header, pos + size, path + kind))
        elif kind == b"ilst":
            # keep text tags, drop cover art
            items = b"".join(data[p:p + s] for _, p, _, s in boxes(data, pos + header, pos + size) if s < 4096)
            out += box(kind, items)
        elif kind == b"meta" and path.endswith(b"udta"):
            out += box(kind, payload[:4] + shrink(data, pos + header + 4, pos + size, path + kind))
        else:
            out += box(kind, payload)
    return out


def skeleton(source, tail_bytes=0):
    with open(source, "rb") as f:
        head = f.read(64)
        f.seek(0, 2)
        length = f.tell()
        # locate top-level boxes without reading mdat
        pos, tops = 0, []
        while pos + 8 <= length:
            f.seek(pos)
            size, kind = struct.unpack(">I4s", f.read(8))
            if size == 1:
                size = struct.unpack(">Q", f.read(8))[0]
            elif size == 0:
                size = length - pos
            tops.append((kind, pos, size))
            pos += size
        out = b""
        for kind, pos, size in tops:
            if kind == b"mdat":
                out += box(b"mdat", b"\0" * 16)
            elif kind == b"moov":
                f.seek(pos)
                moov = f.read(size)
                out += box(b"moov", shrink(moov, 8, len(moov)))
            elif kind == b"inst":
                f.seek(pos + size - tail_bytes)
                out += box(b"inst", f.read(tail_bytes))
            elif size < 4096:
                f.seek(pos)
                out += f.read(size)
        return out


def scrub(data, secrets):
    for secret in secrets:
        data = data.replace(secret, b"X" * len(secret))
    # 32-digit hex ids (file hashes in the Insta360 trailer)
    return re.sub(rb"[0-9a-f]{32}", b"0" * 32, data)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--insta360", type=Path, help="an Insta360 recording (VID_*.mp4)")
    parser.add_argument("--dji", type=Path, help="an Osmo Pocket recording (DJI_*.MP4)")
    args = parser.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    # Serial numbers are looked up in the sources, so none is ever written down here.
    if args.insta360:
        data = skeleton(args.insta360, 2048)
        serials = re.findall(rb"([A-Z0-9]{10,20})[\x00-\x1f]{0,4}Insta360 ", data)
        assert serials, "serial number not found; refusing to write an unscrubbed fixture"
        (OUT / "insta360_skeleton.mp4").write_bytes(scrub(data, serials))
    if args.dji:
        serial = subprocess.run(
            ["ffprobe", "-v", "error", "-show_entries", "format_tags=com.dji.camera.CameraSerialNumber",
             "-of", "default=nw=1:nk=1", str(args.dji)], check=True, capture_output=True).stdout.strip()
        assert serial, "serial number not found; refusing to write an unscrubbed fixture"
        (OUT / "dji_skeleton.mp4").write_bytes(scrub(skeleton(args.dji), [serial]))
    # Playable outputs shaped like what the phone's muxer writes (moov last) and like
    # a "faststart" file (moov first).
    common = ["ffmpeg", "-y", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc=size=64x36:rate=10:duration=1",
              "-f", "lavfi", "-i", "sine=frequency=440:duration=1", "-c:v", "libx264", "-pix_fmt", "yuv420p",
              "-c:a", "aac", "-shortest", "-metadata", "creation_time=2020-01-02T03:04:05Z"]
    subprocess.run(common + [str(OUT / "tiny_moov_last.mp4")], check=True)
    subprocess.run(common + ["-movflags", "+faststart", str(OUT / "tiny_moov_first.mp4")], check=True)
    for path in sorted(OUT.iterdir()):
        print(path.name, path.stat().st_size)


if __name__ == "__main__":
    sys.exit(main())
