# RawCam — RAW (DNG) camera for Xiaomi 15 / HyperOS

Camera2 app: RAW_SENSOR -> DNG, with all optional ISP steps disabled
(noise reduction, sharpening, hot-pixel, shading, aberration, distortion correction).
Pro UI: EV / ISO / shutter / manual focus ruler, lens switcher, tap-to-focus, grid, histogram, timer, torch.
Files go to Pictures/RawCam.

## Build
- Android Studio: open folder, Build > Build APK(s).
- CLI: `./gradlew assembleRelease` (needs JDK 17 + Android SDK 35) -> app/build/outputs/apk/release/
- GitHub: push the folder, Actions > "Build APK" builds and uploads the APK artifact.

## v1.1
- Modes: RAW (all ISP stages off) / JPG / JPG+RAW (JPG shares the request, so it keeps normal processing)
- ISO and shutter are independent; AUTO on one is computed from the camera's AE so exposure stays correct
- Fast = shutter fixed 1/300, ISO auto. Normal = full auto
- Film emulation (RAW modes): 10 Kodak looks, develops the RAW on-device into an extra FILM_*.jpg
- Lenses that are only a crop of another sensor are hidden; Menu > Diagnostics lists every camera/physical id
