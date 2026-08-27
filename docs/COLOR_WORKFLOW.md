# DiveControl Samsung Log grading workflow

DiveControl cannot request Samsung's privileged native Log pipeline. Pro Video therefore records a
standards-compliant 10-bit HEVC BT.2020 HLG master and never labels that file as Samsung Log.

Enable **10-bit HLG / Log grade**. On the measured SM-S921 1x path, with ISO and shutter on Auto,
the app applies a −1.5 EV command: the analytic −1.443 EV target rounded outward to the S24's
0.1 EV hardware step. The EV dial remains a creative offset relative to that baseline.
With either ISO or shutter manual, the app does not alter either value: set exposure until the
read-only `L` meter is centred at `0.0`.

The DCTL expects normalized, encoded BT.2020 HLG RGB **before** an input CST or colour-management
conversion. In Resolve, use a non-colour-managed source path for this first node (or bypass the
clip's automatic input conversion); retain the file's correct HLG metadata. Apply
`DiveControl_HLG_to_Samsung_Log.dctl` first for an SM-S921 1x capture. The transform performs the
analytic HLG inverse, applies the constrained chart-derived S24 1x colour matrix, maps the protected
exposure to Samsung's published scene-linear domain `0..12`, and applies Samsung's published Log
formula. For any other phone or lens, use
`DiveControl_HLG_to_Samsung_Log_TransferOnly.dctl`; unmeasured paths never inherit the S24 exposure
calibration. Samsung Log creative LUTs or a Samsung Log colour-managed input may follow it.

The −1.443 EV target was derived from the controlled Galaxy S24 1x chart pair at ISO 50,
1/125 s, 5300 K and UHD 30 fps. It protects approximately +6.06 stops over 18% scene grey. It is
not evidence that the third-party CameraX path becomes Samsung's hidden ISP pipeline; lens shading,
noise reduction, gamut mapping and chroma rendering can still differ.

The constrained 3x3 matrix reduced equal-exposure signal RMSE from 0.06289 to 0.05536. Six
held-column folds improved from 0.06098 to 0.05699 mean RMSE, and a later protected-exposure clip
improved from 0.07811 to 0.07458. On the final −1.5 EV validation clip it reduced RMSE from 0.08098
to 0.07649; the final 100 Mbit/s device check improved from 0.05612 to 0.05053. An affine/3D fit was
rejected because it could overfit the chart and damage blacks or unseen colours.

The S24 file remains UHD30 HEVC Main10, limited-range BT.2020 HLG. The native Samsung Log reference
measured about 98.9 Mbit/s, so Log mode requests a 100 Mbit/s video target through CameraX's public
`Recorder.Builder.setTargetVideoEncodingBitRate` API. The final SM-S921W device clip measured
98.41 Mbit/s container / 98.15 Mbit/s video over 9.93 seconds. Recording segments are staged in the
app's non-backed-up private files area because Android is allowed to evict `cacheDir` during a live
high-bitrate recording. The encoder may still vary the actual rate according to device capability
and scene complexity; retagging or transcoding after capture cannot recover detail discarded by the
original encode.

Log also uses a strict maximum-information capture contract. The S24 repeating graph contains only
Preview and HLG10 VideoCapture; the still and 8-bit RGBA analysis surfaces are omitted. On this
device the request/result echo confirms the intended physical camera ID `5`, sensor-domain minimal
noise reduction, edge enhancement off and electronic stabilization off. Optical stabilization is
left to the lens because it does not require a digital crop. ISO, shutter, EV, focus and white
balance remain live Camera2 controls during recording. EV is a creative adjustment while AE owns
ISO and shutter; after either exposure axis becomes manual, Camera2 AE compensation no longer
exists and the same `L` tile honestly becomes a read-only light meter.

The extra analysis stream is what powers Auto Underwater scene estimation, so that estimator does
not update in the maximum-information Log graph. OEM continuous/shutter AWB and the complete manual
2300–10000 K white-balance rail remain available. The final on-device contract clip measured
99.91 Mbit/s container / 99.65 Mbit/s video and decoded as UHD30 HEVC Main 10, 10-bit 4:2:0,
limited-range BT.2020 HLG. A one-segment recording is now reviewed and published from its already
finalized MP4 rather than being pointlessly remuxed first; this preserves the encoded samples and
removes one large temporary-file requirement. Multi-segment pause/resume sessions still require a
lossless timestamp remux.

References: [Samsung Log for Galaxy](https://developer.samsung.com/mobile/samsung-log-video.html),
[Samsung APV](https://developer.samsung.com/mobile/apv.html). APV is not the S24 acquisition path.
