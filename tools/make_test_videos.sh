#!/bin/sh
# Synthetic clips for the on-device tests, shaped like the two cameras' recordings:
#   - Osmo Pocket: 4K HEVC Main10 (10-bit), limited range, ~40 Mbps, container date set
#   - the same in 8-bit, for emulators (their decoders have no 10-bit support)
#   - Insta360:    1080p HEVC Main (8-bit), full range, ~50 Mbps, no container date
#   - colour patch clips in each of the cameras' colour encodings
# Requires ffmpeg with libx265.
set -eu
cd "$(dirname "$0")/.."
OUT=app/src/androidTest/assets
mkdir -p "$OUT"
NOISE="noise=alls=30:allf=t"
ffmpeg -y -loglevel error -f lavfi -i "testsrc2=size=3840x2160:rate=30000/1001:duration=2" \
  -f lavfi -i "sine=frequency=440:sample_rate=48000:duration=2" \
  -vf "$NOISE,format=yuv420p10le" -c:v libx265 -preset ultrafast -b:v 40M -tag:v hvc1 \
  -x265-params "log-level=error:colorprim=bt709:transfer=bt709:colormatrix=bt709:range=limited:vbv-maxrate=42000:vbv-bufsize=42000" \
  -c:a aac -b:a 256k -shortest -metadata creation_time=2026-11-07T02:34:03Z \
  "$OUT/osmo_like_4k_10bit.mp4"
ffmpeg -y -loglevel error -f lavfi -i "testsrc2=size=1920x1080:rate=30000/1001:duration=2" \
  -f lavfi -i "sine=frequency=880:sample_rate=48000:duration=2" \
  -vf "$NOISE,format=yuvj420p" -c:v libx265 -preset ultrafast -b:v 50M -tag:v hvc1 \
  -x265-params "log-level=error:colorprim=bt709:transfer=bt709:colormatrix=bt709:range=full:vbv-maxrate=52000:vbv-bufsize=52000" \
  -c:a aac -b:a 192k -shortest \
  "$OUT/insta360_like_1080p.mp4"
ffmpeg -y -loglevel error -f lavfi -i "testsrc2=size=3840x2160:rate=30000/1001:duration=1" \
  -f lavfi -i "sine=frequency=660:sample_rate=48000:duration=1" \
  -vf "$NOISE,format=yuv420p" -c:v libx265 -preset ultrafast -b:v 40M -tag:v hvc1 \
  -x265-params "log-level=error:colorprim=bt709:transfer=bt709:colormatrix=bt709:range=limited:vbv-maxrate=42000:vbv-bufsize=42000" \
  -c:a aac -b:a 256k -shortest -metadata creation_time=2026-11-08T07:11:05Z \
  "$OUT/osmo_like_4k_8bit.mp4"
ffmpeg -y -loglevel error -f lavfi -i "testsrc2=size=1600x1200:duration=1" -frames:v 1 \
  -metadata creation_time=2026-11-07T02:34:03Z "$OUT/photo.jpg"

# Flat colour patches with known values, to check that colours survive conversion:
# #282828 #808080 #DCDCDC #C83232 #32C832 #3232C8, each 200 px wide.
PATCHES="color=c=0x282828:s=200x720:r=30[a];color=c=0x808080:s=200x720:r=30[b];color=c=0xDCDCDC:s=200x720:r=30[c];color=c=0xC83232:s=200x720:r=30[d];color=c=0x32C832:s=200x720:r=30[e];color=c=0x3232C8:s=200x720:r=30[f];[a][b][c][d][e][f]hstack=inputs=6"
X265="log-level=error:colorprim=bt709:transfer=bt709:colormatrix=bt709"
ffmpeg -y -loglevel error -f lavfi -i "$PATCHES,scale=out_color_matrix=bt709:out_range=pc,format=yuvj420p" -t 1 \
  -c:v libx265 -preset ultrafast -crf 10 -tag:v hvc1 -x265-params "$X265:range=full" -color_range pc "$OUT/colors_full_range.mp4"
ffmpeg -y -loglevel error -f lavfi -i "$PATCHES,scale=out_color_matrix=bt709:out_range=tv,format=yuv420p" -t 1 \
  -c:v libx265 -preset ultrafast -crf 10 -tag:v hvc1 -x265-params "$X265:range=limited" -color_range tv "$OUT/colors_limited_range.mp4"
ffmpeg -y -loglevel error -f lavfi -i "$PATCHES,scale=out_color_matrix=bt709:out_range=tv,format=yuv420p10le" -t 1 \
  -c:v libx265 -preset ultrafast -crf 10 -tag:v hvc1 -x265-params "$X265:range=limited" -color_range tv "$OUT/colors_limited_range_10bit.mp4"
ls -l "$OUT"
