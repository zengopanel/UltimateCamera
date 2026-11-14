# Ultimate Camera (Phases 1-3)

Camera2 app, minSdk 29 (Android 10).

## Open
Android Studio -> Open this folder -> let Gradle sync -> Run on a real phone.

## What works now
- HardwareAnalyzer: reads real capabilities of every camera (RAW, manual ISO/shutter/focus, ranges, OIS/EIS). "Info" button shows the report.
- Camera2 preview + JPEG capture at max resolution (quality 100).
- RAW + JPEG (DNG) capture, only if the camera reports RAW capability.
- AUTO: continuous AF, auto exposure, EV compensation, white-balance presets.
- PRO: manual ISO, manual shutter, manual focus. Controls the camera does not support are not shown.
- Photos saved to Pictures/UltimateCamera (no storage permission needed on Android 10).

## Not yet built
Night/HDR/portrait/astro/panorama modes, video, gallery/editor, histogram/zebra/peaking, zoom.
