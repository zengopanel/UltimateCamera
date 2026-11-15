# Ultimate Camera

A native Camera2 camera app for Android 10 and later.

## Open

Open this folder in Android Studio, sync Gradle, and run it on a camera-equipped phone. Camera controls and modes are derived from the active camera's reported capabilities.

## Capture

- **Photo:** JPEG capture at selectable supported sizes and aspect ratios; flash auto/on/off, exposure compensation, tap-to-focus/metering when supported, composition grid, 3/10-second timer, and 5-frame burst.
- **Pro Photo:** manual ISO/shutter, focus, exposure compensation, white-balance presets, Kelvin gains, supported highlight/shadow tone curves, focus peaking, histogram, level, AE/AF lock, and JPEG or RAW + JPEG where the camera supports them. Unsupported controls are omitted.
- **Pro Video:** switches between supported resolution/FPS profiles, H.264/H.265 encoders, three bitrate tiers, audio on/off and balanced/high recording, manual sensor controls, live histogram, audio meter, exposure/readout, free-space estimate, and recording timer.
- **Stabilization:** the top-bar control cycles Auto, Off, and individually supported EIS/OIS modes. Only one hardware mode is requested at a time; Auto prefers EIS, then OIS. It is unavailable when neither mode is advertised. This Camera2/MediaRecorder pipeline does not implement software stabilization.
- **Single-camera devices:** use the one enumerated camera and its digital crop zoom. The camera-switch control is hidden when no second camera exists; no lens stops are invented.
- **Portrait, Night, and HDR:** available only when the camera exposes compatible Camera2 extensions or scene modes. If an extension fails to configure, the app falls back to a supported scene mode or standard capture.

Video sizes, supported FPS targets and their Camera2 AE ranges, and compatible hardware encoders are discovered from the active camera and Android codec registry. A device can still reject a stream combination at session configuration, in which case the app reports the failure rather than silently changing quality. Kelvin white balance uses the camera's manual color-correction capability and sensor calibration; generic Natural/Vivid/LOG profiles are not exposed because Camera2 does not provide portable profile semantics and the app has no LOG pipeline.

## Files and build

Photos and DNG files are saved under `Pictures/UltimateCamera`; videos are saved under `Movies/UltimateCamera`. The existing GitHub Actions workflow builds a debug APK.
