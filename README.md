# Hikkonuki

English | [日本語](README.ja.md)

An Android app that pulls photos and videos off an Osmo Pocket 4P or Insta360 Ace Pro 2 over **USB**.
Plug the camera into the phone, pick what you want, and export: videos are converted to 2K HEVC, photos are copied as they are, and everything lands in `DCIM/Hikkonuki`, where Google Photos picks it up.

- Package: `app.hikkonuki.android`
- Languages: English (default), Japanese, Chinese (Simplified and Traditional), Korean. The app follows the device language, or the per-app language chosen in system settings (Android 13+).

## Video conversion

The rules come from an ffmpeg workflow that was tuned on a Mac for each camera, ported to run on the phone.

| | |
| --- | --- |
| Resolution | Long edge capped at 2560, aspect ratio kept, never upscaled (Insta360 1080p stays 1080p) |
| Codec | HEVC. 10-bit sources stay Main10 when the phone's encoder supports it |
| Scaling | Lanczos-3 on the GPU, in two passes |
| Colour | Y'CbCr to RGB is done explicitly in the shader where `GL_EXT_YUV_target` is available, so the Insta360's full-range video is not mistaken for limited range |
| Bitrate | 8 Mbps at 1080p30, 12 Mbps at 1440p30. For the Osmo Pocket it rises up to 4x with the source bitrate, which tracks how detailed the footage is |
| Audio | Copied, not re-encoded |
| Capture date | Decided from the container date and the timestamp in the file name, then written into the MP4 (`mvhd` / `tkhd` / `mdhd`) together with the camera make and model |
| Not converted | HDR/Log footage, clips that would not shrink by at least 20%, and clips that fail to convert are imported as the original |

Conversion tries `direct-10bit`, then `direct-8bit`, then Media3 Transformer; if all fail the original is imported.

### How the capture date is chosen

The container date (UTC) is compared with the timestamp in the file name (the camera's wall clock).

- They differ by a plausible time zone offset (a multiple of 15 minutes): the container date is used, and the difference is recorded as the camera's time zone.
- They show the same digits: DJI's container date is trusted; otherwise the file name is read in the phone's time zone.
- Anything else (an unset 1904/1970 date, for example): the file name is used.

Android's media scanner, and so the order in Google Photos, takes a video's date from inside the file, which is why it is written before the file is published.

## Structure

The screens and the export logic do not know about each other. Screens render the lists exposed by `ExportController` (`jobs`, `batches`) and send commands.

```
core/      Plain Kotlin with no Android dependencies, covered by JVM unit tests
  ExportQueue    The export list and its rules (batches, de-duplication)
  ExportEngine   Imports one file at a time: interruption, USB removal, cancel, fallbacks
  SlimPlanner    Resolution and bitrate decisions
  CaptureDate    Capture date decisions
  Mp4            Reading and writing MP4 metadata
platform/  Android implementations (USB folders, MediaStore, SQLite, MediaCodec + OpenGL, service)
ui/        Compose screens and the ViewModel (depend only on MediaLibrary / ExportController)
```

### Export state

- A running batch (ACTIVE) has one row per file, listed on the export status screen.
- When everything has finished the batch becomes DONE: the rows are deleted and only the result ("M of N succeeded") is kept. At most two batches are stored.
- Cancelling removes the row. "Clear history" removes everything.

How it copes with interruptions:

- State is written to SQLite as it changes. If the process is killed while the camera stays attached, the next start puts the interrupted file back in line and carries on. If the camera is gone by then, the remaining files are failed and the batch is closed at startup.
- Output is written straight into a pending MediaStore file, so a half-written file is never visible to other apps. Leftovers are removed on the next start.
- USB removal is detected by system broadcasts (`USB_DEVICE_DETACHED`, `MEDIA_EJECT` / `UNMOUNTED` / `REMOVED` / `BAD_REMOVAL`) and by a watchdog that checks the source file every second (two misses in a row count as removed).
- When the camera is unplugged mid-export, the file in flight and every waiting file fail together and the batch ends (unplugged during the third of four: "2 of 4 succeeded, 2 failed"). Exporting the same selection again after reconnecting skips what was already imported.

## Reading the camera

The camera's `DCIM` folder is granted once through the system folder picker. Grants are remembered per SD card, so a second camera also only asks the first time. No storage permission is used.

## Languages

All text is in string resources: `values/` (English), `values-ja/`, `values-zh/` (Simplified Chinese), `values-b+zh+Hant/` (Traditional Chinese) and `values-ko/`. `xml/locales_config.xml` lists them for the system's per-app language setting. The product name "Hikkonuki" is not translated.

Export failures are stored as reason codes, not sentences, so a row written in one language reads correctly after the language changes. `LocalizationTest` checks that every string is translated in every language and that format placeholders match.

## Build and test

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew :app:assembleDebug

# Unit tests (JVM / Robolectric): export logic, dates, MP4, ViewModel, screens, translations
./gradlew :app:testDebugUnitTest

# Device tests: conversion with real codecs, MediaStore, dates, colour, the whole app flow
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest
```

The device tests need no camera. Emulators only encode HEVC up to 512x512 and cannot decode 10-bit, so three tests are skipped there and run only on a phone:

- `directPipelineTenBitTwoK` (4K 10-bit to 2K HEVC Main10)
- `tenBitColorsSurvive`
- `fullRangeColorsSurvive` (Insta360's full range; emulators get the range wrong, with Media3 as well)

To run them on a phone, connect only the phone and run `ANDROID_SERIAL=<serial> ./gradlew :app:connectedDebugAndroidTest`.

### Trying the UI without a camera

Debug builds treat files placed in the app's `usb_inbox` folder as an attached camera.

```bash
adb push clip.mp4 /sdcard/Android/data/app.hikkonuki.android/files/usb_inbox/
```

### Regenerating test files

```bash
# MP4s with only the headers of real camera files (unit tests). The recordings are not in the repository.
python3 tools/make_fixtures.py --insta360 VID_xxx.mp4 --dji DJI_xxx.MP4
tools/make_test_videos.sh        # synthetic clips shaped like the cameras' recordings (device tests)
python3 design/generate_icon.py  # launcher icon
```
