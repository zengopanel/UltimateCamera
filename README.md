# Ultimate Camera

A native Camera2 camera app for Android 10 and later.

## Open

Open this folder in Android Studio, sync Gradle, and run it on a camera-equipped phone. Camera controls and modes are derived from the active camera's reported capabilities.

## Capture

- **Photo:** JPEG capture at selectable supported sizes and aspect ratios; flash auto/on/off, exposure compensation, tap-to-focus/metering when supported, composition grid, 3/10-second timer, and 5-frame burst.
- **Pro:** manual ISO and shutter speed, manual focus, white-balance presets, and exposure compensation where Camera2 reports support. RAW + JPEG is offered only for RAW-capable cameras.
- **Video:** supported MediaRecorder size/frame-rate profiles, optional microphone audio, hardware-supported torch and stabilization, and a live recording timer.
- **Portrait, Night, and HDR:** available only when the camera exposes compatible Camera2 extensions or scene modes. If an extension fails to configure, the app falls back to a supported scene mode or standard capture.

The STAB control requests the camera's advertised OIS and/or EIS modes. It is disabled when neither is available. Software stabilization is not implemented. Video sizes are discovered from MediaRecorder outputs, advertised FPS ranges, and per-size frame duration; a device can still reject a stream combination at session configuration, in which case the app reports the failure rather than silently changing quality.

## Files and build

Photos and DNG files are saved under `Pictures/UltimateCamera`; videos are saved under `Movies/UltimateCamera`. The existing GitHub Actions workflow builds a debug APK.
