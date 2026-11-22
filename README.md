# LineTracker Ball AutoTap (experimental)

Kotlin Android project for a **bright baseball** detector, not a general AI object detector. It scans a user-drawn search ROI, groups near-white pixels into connected components, associates candidates frame-to-frame, checks whether the ball edge intersects a user-drawn target rectangle/line, and dispatches one accessibility tap. It can miss fast pitches or misidentify other bright objects.

## Setup
1. Upload the contents of this folder (not the enclosing folder) to a GitHub repository.
2. Actions > Build APK > Run workflow. Download the `LineTracker-debug-apk` artifact.
3. Install APK, enable the LineTracker accessibility service.
4. Open the baseball game, then return to LineTracker and use **공 탐색 범위 그리기** to select a narrow area covering the ball's path. Draw **접촉 선/면** where contact should trigger. Select the actual batting button via **화면에서 터치 위치 찍기**.
5. Start screen capture and switch to the game. Stop from LineTracker.

## Accuracy and limitations
- No TensorFlow model is included; this uses white-object segmentation and motion association. Requires video-specific calibration.
- Target is a rectangle; draw it very thin to approximate a line. The ball's estimated radius is included in contact testing.
- Frame capture, processing, and accessibility gesture all have variable latency. No guaranteed frame-perfect tap.
- Only one candidate is tracked. Occlusion, white UI, trails, zoom, and moving camera can cause mistakes. Use a narrow search ROI.
- On Android 14+, screen capture requires a new consent for each session; protected games may block capture.
- Not built or device-tested in this environment.
