# Samsung Camera mode and setting inventory

Reference device: Galaxy S24 `SM-S921W`

Reference app: `com.sec.android.app.camera` 16.5.02.36 (`versionCode 1650236000`)

Inspected: 2026-08-27

This is the source-of-truth inventory used by `CameraCatalog`. It was collected from the live
camera UI, its accessibility hierarchy, and the exact installed `SamsungCamera.apk` resources.
The lists below describe the controls exposed by this installed build, not a generic list copied
from another Galaxy model or One UI release.

## Installed mode list

Samsung's primary rail contains **Portrait, Photo, Video, More**. More contains **Pro, Food,
Night, Panorama, Pro Video, Hyperlapse, Slow Motion, and Portrait Video**. DiveControl presents
the same capture modes in one housing-navigable list, with Track Heading first and Diagnostics
last.

**Expert RAW is not installed.** Samsung shows a disabled/download tile that would install a
separate application, so it is not treated as an available native-camera mode. This build also
does not contain Burst, Single Take, Dual Record, or Night Video as selectable modes.

## Per-mode controls

| Mode | Lenses | Native quick and shooting controls |
|---|---|---|
| Photo | 0.6x, 1x, 2x, 3x | Filters/My Filters, Motion photo, 12M/50M where supported, flash Auto/Off/On, EV, aspect ratio 4:3/16:9/1:1/Full, timer Off/2/5/10 s, gear/settings |
| Portrait | 1x, 2x, 3x | Flash, timer, aspect ratio, EV, Beauty/Skin smoothness, Lighting, background effect, eight-step effect strength, gear/settings. Effects: Blur, Studio, High-key mono, Low-key mono, Backdrop, Color point |
| Video | Mode- and option-dependent | Flash/torch, Super Steady, aspect ratio, video size, 30/60 fps, EV, filters, HDR10+, Log, gear/settings. Normal sizes: 8K/UHD/FHD/HD. Super Steady sizes: QHD/FHD |
| Pro | Ultra-wide, wide, telephoto | RAW/JPEG capture, histogram, filters, metering, photo resolution, aspect ratio, timer, flash, lens, ISO, shutter speed, EV, AF/manual focus, white balance, gear/settings |
| Food | 1x, 2x, 3x | Relative warm/cool colour temperature, movable radial-blur/focus area, EV, aspect ratio, gear/settings |
| Night | 0.6x, 1x, 2x, 3x | Auto/Max capture time, timer, aspect ratio, EV, lens, gear/settings. No flash or megapixel selector |
| Panorama | 0.6x, 1x | Sweep Left/Right/Up/Down, EV, lens, gear/settings |
| Pro Video | Ultra-wide, wide, telephoto | LOG, preview LUT, HDR10+, microphone/source and gain, resolution/FPS, flash/torch, lens, histogram, metering, ISO, shutter, EV, AF/manual focus, white balance, stabilization and assist controls, gear/settings |
| Hyperlapse | 0.6x, 1x, 2x, 3x | Flash/torch, FHD/UHD size, EV, recording time, speed, Night Hyperlapse, lens, gear/settings. Recording limits: Unlimited, 10, 30, 60, 120, 180, 300 seconds. Normal speeds include Auto/5x/10x/15x/30x/60x; Night speeds include Auto/15x/45x/300x |
| Slow Motion | 0.6x, 1x, 3x | FHD/UHD size, 120/240 fps subject to size support, flash/torch, EV, lens, gear/settings. No microphone toggle |
| Portrait Video | 1x, 2x | FHD/UHD at 30 fps, flash/torch, EV, background effect, eight-step effect strength, gear/settings. Effects: Blur, Big bokeh, Color point, Glitch |

Filters and locally created My Filters are device-managed, so their names are not a fixed
catalog. DiveControl retains its functional underwater filter ladder under the same control.

## Global Camera Settings inventory

The native gear screen exposes these settings from the inspected build:

- Intelligent features
  - Scan documents and text
  - Scan QR codes
  - Shot suggestions
  - Photo enhancer: Prioritize quality, Balanced, Prioritize speed
  - Custom filters
- Photos
  - Photo format / high-efficiency pictures
  - Pro picture format: JPEG, RAW, RAW + JPEG
  - Watermark
    - Model name, date, time, custom text
    - Overlay on picture / show in frame
    - Alignment, frame style/colour, and font
  - Motion photos
- Videos
  - Video format: H.264, HEVC, and APV where supported
  - HEVC (high efficiency)
  - Auto FPS: Off, 30 fps, 30 and 60 fps
  - Video stabilization
  - Audio options
    - Zoom-in microphone
    - Bluetooth microphone mix
    - 360 Bluetooth microphone recording where supported
  - Save to external USB storage
- General
  - Tracking auto-focus
  - Composition guide
  - Grid lines and level
  - Location tags
  - Save selfies as previewed
  - Swipe preview up/down to: switch cameras or show quick controls
  - Shooting methods
    - Press volume keys to
    - Voice commands
    - Floating shutter button
    - Show palm
  - Settings to keep
    - Camera mode, selfie angle, filters, high-resolution pictures, Portrait zoom,
      Super Steady, exposure value, and shutter-button action where supported
  - Vibration feedback
  - Camera Assistant
  - Privacy
  - Permissions
  - Reset settings
  - About Camera

## DiveControl implementation boundary

All controls above that map to public CameraX/Camera2 functions are exposed through the Options
menu and housing controls: lens, flash/torch, EV, timer, aspect ratio, photo resolution, video
resolution/FPS, focus, ISO, shutter, white balance, filters, HDR/Log, audio recording, grid, and
standard stabilization.

### Verified rear-video resolution map (Galaxy S24 SM-S921W)

| Samsung label | Encoded dimensions | DiveControl public-camera status |
|---|---:|---|
| 8K | 7680×4320 | Verified HEVC through the Samsung vendor session on public camera ID 0 at 24/30 fps |
| UHD | 3840×2160 | Supported as `UHD 4K` at 24/30 fps |
| FHD | 1920×1080 | Supported as `FHD` at 24/30/60/120/240 fps |
| HD / 720p | 1280×720 | One resolution, supported as `HD 720p` at 24/30/60/120/240 fps |
| SD / 480p | 720×480 | Supported as `SD 480p` at 24/30 fps |
| Constrained FHD | 1920×824 | Samsung high-speed-only stream, supported at 120 fps |

The public Camera2 stream map stops at 3840×2160 and Samsung Camera uses hidden logical camera ID
56 for its own 8K path. On SM-S921 firmware, however, Samsung's vendor session parameters promote
an exact 7680×4320 MediaRecorder surface through public camera ID 0. DiveControl keeps an ordinary
FHD/30 CameraX graph while idle, gives the direct Camera2 recorder sole ownership of the 8K surface
while the shutter is active, and rejects the segment unless its muxed track verifies as 7680×4320
HEVC. Device testing verified native 24 and 30 fps capture. Pro Video pairs those with exact
23.976/24 and 29.97/30 playback clocks through encoded-sample timestamp remuxing; it neither
duplicates nor interpolates frames. The custom 1920×824 stream is likewise kept out of the idle
graph and is bound only by the constrained high-speed recorder while the shutter is active.

Samsung computational features do not have public third-party APIs. Portrait/background effects,
Beauty, Food radial blur, Samsung multi-frame Night capture, panorama stitching, Hyperlapse/Night
Hyperlapse processing, Motion Photo packaging, HEIF/RAW vendor output, Super Steady's wide crop,
and Samsung-only QHD encoder profiles are retained in the parity inventory but are clipped from the
live selector when the capture path cannot prove support. The app does not claim that CameraX
reproduces Samsung's private image-processing pipeline.

Photo's manual focus, focus assist, focus curve, and underwater filter choices are retained as
explicit DiveControl housing enhancements. They are additional to—not replacements for—the
native Photo controls.
