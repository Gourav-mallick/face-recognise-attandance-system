# Face Registration and Recognition Developer Guide

## 1. Purpose

This document explains the face biometric pipeline currently implemented in the Android
application. It describes what happens from the camera frame through face registration and face
recognition, which models are used, which checks can reject a frame, and the exact thresholds in
the source code.

The application does **not** register or compare the original photograph. It converts an accepted
face into a normalized **128-number SFace embedding** (also called a face template or face
signature). Registration stores this template. Recognition creates a new template and compares it
with stored templates.

## 2. Main components

| Component | Responsibility |
|---|---|
| `FaceRegistrationActivity` | Selects the student/teacher, pre-syncs data, launches capture, checks for duplicate registrations, and sends the final template to the server. |
| `CameraCaptureActivity` | Runs the live registration camera, requires exactly one face, performs liveness and strict quality checks, and combines three samples. |
| `FaceRecogniseActivity` | Performs general student/teacher recognition against a selected local gallery. |
| `FaceVerificationActivity` | Verifies one specific student against that student's stored template. |
| `TeacherScanFragment` | Recognizes a teacher before starting an attendance session. |
| `StudentScanFragment` | Recognizes students/teachers during an attendance session. |
| `YuNetSFaceEngine` | Runs YuNet detection, five-landmark alignment, SFace feature extraction, normalization, and cosine comparison. |
| `ActiveLivenessVerifier` | Uses ML Kit eye-open probabilities to require an open → closed → open blink. |
| `FaceLandmarkOverlay` | Displays the five detected facial landmarks to the user. |

## 3. Models used

### 3.1 Face detection and landmarks

- Model: **YuNet**
- Asset: `models/face_detection_yunet_2023mar.onnx`
- Runtime: ONNX Runtime
- Output used by the app:
  - Face bounding box
  - Detection confidence
  - Five landmarks:
    1. Left eye
    2. Right eye
    3. Nose
    4. Left mouth corner
    5. Right mouth corner

The detector input width and height are read from the ONNX model. If the shape is dynamic or
unavailable, the code falls back to **640 × 640**.

YuNet candidates are produced at strides **8, 16, and 32**. The confidence calculation is:

```text
detection score = sqrt(classification score × objectness score)
```

Candidates below `0.85` are removed. Non-maximum suppression then removes overlapping boxes whose
intersection-over-union is greater than `0.30`.

### 3.2 Face recognition

- Model: **SFace**
- Asset: `models/face_recognition_sface_2021dec_int8.onnx`
- Runtime: ONNX Runtime
- Aligned input: **112 × 112**
- Output template: **128 floating-point values**
- Final output: L2-normalized to unit length

### 3.3 Active liveness

Active liveness uses the on-device **Google ML Kit Face Detection** API, separately from YuNet.
ML Kit is configured with:

- Fast performance mode
- Eye classification enabled
- Landmark mode disabled
- Minimum relative face size: `0.15`

The liveness check is a blink challenge. It is useful against a static printed photograph or a
static image on another screen, but it is **not** a complete presentation-attack detector and does
not guarantee protection against sophisticated video replay, masks, or deepfakes.

## 4. Common camera and model pipeline

The main capture and recognition screens use the front CameraX camera with
`STRATEGY_KEEP_ONLY_LATEST`. This prevents old frames from forming a queue when inference is slower
than the camera.

For each analyzed frame:

1. CameraX supplies a YUV frame.
2. The frame is converted to NV21 and then decoded to a bitmap.
3. Camera rotation metadata is applied so the bitmap is upright.
4. The front-camera bitmap is horizontally mirrored so it matches the preview.
5. YuNet resizes the frame to its detector input size.
6. YuNet receives NCHW floating-point pixel data in BGR order.
7. YuNet returns face boxes, confidence values, and five landmarks.
8. The screen applies its face-count rule, liveness check, quality rules, and motion-stability rule.
9. When all gates pass, the five landmarks are used to align the face.
10. SFace converts the aligned face into a normalized 128-value embedding.

For SFace, the aligned bitmap is supplied as NCHW RGB values in the `0–255` range. The code mirrors
OpenCV SFace preprocessing (`scale=1`, `mean=0`, and RGB channel order); model-side normalization
remains inside the ONNX graph.

## 5. Face alignment

The app does not simply crop the face bounding box. It calculates a least-squares similarity
transform from the five detected landmarks to SFace's canonical five landmark positions:

```text
Left eye:          (38.2946, 51.6963)
Right eye:         (73.5318, 51.5014)
Nose:              (56.0252, 71.7366)
Left mouth corner: (41.5493, 92.3655)
Right mouth corner:(70.7299, 92.2041)
```

The similarity transform permits rotation, uniform scale, and translation, but not shear. The
result is drawn onto a black `112 × 112` bitmap before SFace inference. This makes matching less
sensitive to small changes in camera distance, position, and in-plane head rotation.

## 6. Active liveness sequence

The same liveness state machine is used for registration and recognition:

1. Wait until both eyes are open.
2. Wait until both eyes are closed.
3. Wait until both eyes reopen.
4. Mark the liveness challenge as passed.

Exact thresholds:

| Rule | Value |
|---|---:|
| Both eyes considered open | Each eye probability `>= 0.65` |
| Both eyes considered closed | Each eye probability `<= 0.35` |
| Challenge timeout | `12,000 ms` |
| Face missing before liveness reset | `800 ms` |
| Passed liveness validity window | `7,000 ms` |

ML Kit selects its largest face for the blink state machine. Registration separately enforces the
YuNet exactly-one-face rule before it allows the liveness state to advance.

## 7. Face quality gates

`YuNetSFaceEngine.assessQuality()` applies the following hard gates.

| Quality rule | Registration (`strict=true`) | Recognition (`strict=false`) |
|---|---:|---:|
| Required landmarks | Exactly 5 | Exactly 5 |
| Minimum distance between eye landmarks | `45 px` | `32 px` |
| Horizontal pose/symmetry limit | `16%` of eye distance | `23%` of eye distance |
| Complete face box inside the bitmap | Required | Required |
| Minimum Laplacian sharpness variance | `90` | `55` |

The pose/symmetry test calculates the midpoint of the eyes and the midpoint of the mouth. It rejects
the frame when the nose's horizontal position differs from either midpoint by more than the
configured percentage of eye distance. This is a landmark-based frontal-face check; it is not a
head-pose angle measured in degrees.

Sharpness is calculated on the aligned `112 × 112` face using grayscale Laplacian variance. A
higher value means more edge detail. Values below the applicable threshold are treated as blurry.

Important implementation details:

- "Inside the guide" currently means the complete YuNet face box is inside the image boundaries.
  The code does not mathematically test whether the box is inside the visible oval.
- Brightness values are shown as warnings on recognition/attendance screens, but brightness is not
  currently a hard acceptance gate.
- There is no separate hard threshold for exposure, highlight clipping, or shadow balance.

## 8. Registration: exact end-to-end behavior

### 8.1 Before opening the camera

1. The operator selects Student or Staff.
2. The operator selects Add, Update, or Delete.
3. A local user must be selected.
4. Add/Update requires camera permission.
5. The app requires a network connection and active internet access.
6. The app runs a server pre-sync and waits for it to finish.
7. Only after successful pre-sync does it open `CameraCaptureActivity`.

### 8.2 Registration frame processing

Each live registration frame follows this order:

1. Convert, rotate, and mirror the front-camera frame.
2. Run YuNet and obtain the complete post-NMS face list.
3. Apply the face-count rule:
   - **0 faces:** ask the user to place a face in the oval.
   - **Exactly 1 face:** continue.
   - **More than 1 face:** reject the frame and show "Only one person can be in the camera."
4. For multiple faces, immediately clear:
   - Stability progress
   - Previous tracked face
   - All collected registration samples
   - Last sample time
   - Temporary feedback state
   - Liveness progress
5. With exactly one face, run the blink liveness state machine.
6. Run strict registration quality checks.
7. Check motion stability against the previous detected face position.
8. When liveness, quality, and stability remain valid long enough, extract one SFace sample.

Registration is automatic and uses continuous video frames; the user does not press a shutter
button. The old "choose the largest face" behavior is no longer used during registration. A second
YuNet face that passes the `0.85` detection threshold anywhere in the frame invalidates the current
registration sample set.

If no face remains visible for at least **600 ms**, collected samples and liveness progress are
cleared.

### 8.3 Registration stability and capture thresholds

| Registration rule | Value |
|---|---:|
| Position movement allowed between frames | `< 3.5%` of current face-box width on both X and Y |
| Continuous valid lock before sampling | `700 ms` |
| Required samples | `3` |
| Minimum interval between samples | `350 ms` |
| Minimum sample-to-sample cosine similarity | `0.55` |
| Face absence that clears the sample set | `600 ms` |
| Inconsistent-sample warning duration | `1,000 ms` |

The first detected face frame cannot be stable because there is no previous face position. On later
frames, both center-X and center-Y movement must be below the movement tolerance.

### 8.4 Three-sample registration

The app collects three independently generated SFace embeddings. Before adding a new sample, it
compares that sample with every sample already in the set.

```text
minimum similarity to existing samples must be >= 0.55
```

If any comparison is below `0.55`:

1. The old set is discarded.
2. The newest good sample becomes sample 1 of a fresh set.
3. The user sees "Samples did not agree."
4. Capture continues without leaving the screen.

When three consistent samples are available:

1. Each of the 128 positions is averaged across the three embeddings.
2. The averaged 128-value vector is L2-normalized.
3. This one final template is returned to `FaceRegistrationActivity`.

No original camera photograph is returned or stored by this flow.

### 8.5 Duplicate-face protection

Before saving an Add or Update, the final embedding is compared with all valid 128-value student and
teacher templates in the local database.

- Similarity method: cosine similarity
- Duplicate threshold: `>= 0.42`
- Stored templates with a dimension other than 128 are skipped.
- The single best student/teacher result is used.

Behavior:

- **Add:** blocked if the face matches any existing user, including the selected user.
- **Update:** blocked if the face matches a different user.
- **Update of the same user:** allowed so the user's existing template can be replaced.
- If Add is selected but the chosen user already has a template, Add is blocked.
- If Update is selected but the chosen user has no template, Update is blocked.

### 8.6 Server and local storage

The normalized embedding is converted to comma-separated text and sent as:

```json
{
  "userRegParamData": {
    "userType": "student or staff",
    "registrationType": "Biometric",
    "regParamData": [
      {
        "userId": "selected user ID",
        "metricType": "faceSignature",
        "fingerType": "faceSignature",
        "template": "128 comma-separated floating-point values"
      }
    ]
  }
}
```

The local Room student/teacher record is updated only when the server response contains
`successStatus = TRUE`. After that, the app refreshes its local user cache/counters and schedules an
automatic sync worker.

## 9. Recognition: exact end-to-end behavior

### 9.1 Building the recognition gallery

In `FaceRecogniseActivity`, the operator first chooses:

- Students or Teachers
- For students, either All Classes or one class

The app loads matching local database records, parses only templates containing exactly 128
floating-point values, L2-normalizes them, and caches them in memory. Recognition remains paused if
the selected gallery contains no valid registered faces.

### 9.2 Recognition frame processing

The general recognition screen processes at most one frame every **160 ms**:

1. Read average Y-plane brightness for UI feedback.
2. Convert, rotate, and mirror the camera frame.
3. Run the ML Kit blink liveness check.
4. Run YuNet face detection.
5. If YuNet detects multiple faces, select the face with the largest bounding-box area.
6. Run the less-strict recognition quality gates on that face.
7. Compare its center with the previous face position.
8. Require liveness + quality + stability continuously for **550 ms**.
9. Align the five landmarks and create one normalized SFace embedding.
10. Compare that embedding with every template in the selected gallery.
11. Choose the user with the highest cosine similarity.
12. Accept only when the best score is `>= 0.42`.

Unlike registration, the current recognition screens do **not** reject multiple YuNet faces. They
select the largest detected face. This should be considered when explaining the intended use: the
person being recognized should be closest to and centered in the camera.

### 9.3 Recognition stability and feedback

| Recognition rule | Value |
|---|---:|
| Frame processing interval | `160 ms` |
| Position movement allowed between frames | `< 4.5%` of current face-box width on both X and Y |
| Continuous valid lock | `550 ms` |
| Match threshold | Cosine similarity `>= 0.42` |
| Low-light UI warning | Average Y-plane brightness `< 45` |
| Retry delay after no match | `1,800 ms` |

The `< 45` brightness rule only displays a warning. It does not prevent recognition when all hard
quality gates pass.

### 9.4 Matching decision

For captured embedding `A` and stored embedding `B`, cosine similarity is:

```text
similarity = dot(A, B) / (length(A) × length(B))
```

The app selects the highest score:

```text
best score >= 0.42  → recognized
best score < 0.42   → not recognized
```

The displayed percentage is the cosine score multiplied by 100 and converted to an integer. It is
a similarity score, not a statistically calibrated probability that the identity is correct.

After a recognition result, the scanner resets its liveness and tracking state before accepting the
next person.

## 10. Other recognition/verification screens

All recognition screens share YuNet, five-landmark alignment, SFace, the `0.42` match threshold,
non-strict quality rules, and the blink liveness gate. Their operational differences are:

| Screen | Gallery/target | Frame interval | Stable lock | Multiple-face behavior |
|---|---|---:|---:|---|
| `FaceRecogniseActivity` | Selected role and optional class | `160 ms` | `550 ms` | Largest YuNet face |
| `FaceVerificationActivity` | One selected student's template | `160 ms` | `700 ms` | Largest YuNet face |
| `TeacherScanFragment` | Registered teachers | `130 ms` | `700 ms` | Largest YuNet face |
| `StudentScanFragment` | Session teacher plus students from allowed classes | `160 ms` | `700 ms` | Largest YuNet face |

Additional behavior:

- `FaceVerificationActivity` permits a maximum of **3 failed attempts**.
- Teacher recognition checks that the matched teacher has assigned classes.
- Student attendance recognition searches the session's permitted student gallery and also handles
  the session teacher according to attendance workflow rules.
- Teacher/student attendance screens display a low-light warning below average brightness `40`;
  this remains a warning, not a hard gate.

## 11. Complete threshold reference

| Category | Setting | Current value used | Recommended range |
|---|---|---|---|
| YuNet | Detection confidence | `0.85` | `0.80 - 0.85` |
| YuNet | NMS IoU | `0.30` | `0.30 - 0.40` |
| YuNet | Detector strides | `8, 16, 32` | `8, 16, 32` (Fixed model spec) |
| YuNet | Fallback input size | `640 × 640` | `640 × 640` |
| SFace | Aligned input size | `112 × 112` | `112 × 112` (Fixed model spec) |
| SFace | Embedding dimensions | `128` | `128` (Fixed vector spec) |
| Matching | Recognition/duplicate cosine threshold | `0.42` | `0.40 - 0.44` (`0.40` for 1 photo; `0.42 - 0.44` for 3-5 photos) |
| Registration quality | Minimum eye distance | `45 px` | `50 - 60 px` |
| Recognition quality | Minimum eye distance | `32 px` | `36 - 40 px` |
| Registration quality | Nose symmetry limit | `16%` of eye distance | `12% - 15%` of eye distance ($\approx 8^\circ - 10^\circ$ yaw) |
| Recognition quality | Nose symmetry limit | `23%` of eye distance | `16% - 18%` of eye distance ($\approx 12^\circ - 15^\circ$ yaw) |
| Registration quality | Minimum sharpness | `90` | `100 - 120` (Laplacian variance) |
| Recognition quality | Minimum sharpness | `55` | `80 - 100` (Laplacian variance) |
| Registration | Required detected faces | Exactly `1` | Exactly `1` |
| Registration | Stability movement | `< 3.5%` of face width per axis | `< 3.0% - 3.5%` |
| Registration | Stable lock | `700 ms` | `500 - 700 ms` |
| Registration | Samples | `3` | `3 - 5` samples |
| Registration | Sample interval | `350 ms` | `300 - 400 ms` |
| Registration | Sample consistency | `0.55` | `0.60 - 0.65` ($S_{cos}$ pairwise) |
| Registration | Missing face reset | `600 ms` | `500 - 800 ms` |
| Recognition | Stability movement | `< 4.5%` of face width per axis | `< 4.0% - 4.5%` |
| General recognition | Stable lock | `550 ms` | `400 - 550 ms` |
| Other verification flows | Stable lock | `700 ms` | `500 - 700 ms` |
| Liveness | ML Kit minimum relative face size | `0.15` | `0.15 - 0.20` |
| Liveness | Eye-open probability | Each eye `>= 0.65` | `>= 0.60 - 0.70` |
| Liveness | Eye-closed probability | Each eye `<= 0.35` | `<= 0.30 - 0.35` |
| Liveness | Challenge timeout | `12 s` | `10 - 15 s` |
| Liveness | Missing face reset | `800 ms` | `600 - 800 ms` |
| Liveness | Passed-state validity | `7 s` | `5 - 10 s` |
| Verification | Maximum failed attempts | `3` | `3 - 5` |

## 12. Manager-level summary

### Registration

The app verifies that exactly one person is visible, asks that person to blink, checks that their
face is large enough, frontal, fully visible, sharp, and stable, and then creates three consistent
SFace measurements. It averages those measurements into one 128-value biometric template. Before
saving, it checks all students and teachers for a duplicate face. The template is sent to the
server and is stored locally only after server success. The captured photograph itself is not
stored by this pipeline.

### Recognition

The app asks the person to blink, detects and aligns the largest visible face, checks quality and
stability, and creates a new 128-value SFace template. It compares this temporary template with the
eligible registered templates and selects the highest cosine similarity. A score of at least
`0.42` is accepted; otherwise the face is not recognized. The temporary camera frame is recycled
after processing.

## 13. Source-of-truth files

- `app/src/main/java/com/digitaledu/selfieattendance/ml/YuNetSFaceEngine.kt`
- `app/src/main/java/com/digitaledu/selfieattendance/ml/ActiveLivenessVerifier.kt`
- `app/src/main/java/com/digitaledu/selfieattendance/view/CameraCaptureActivity.kt`
- `app/src/main/java/com/digitaledu/selfieattendance/view/FaceRegistrationActivity.kt`
- `app/src/main/java/com/digitaledu/selfieattendance/view/FaceRecogniseActivity.kt`
- `app/src/main/java/com/digitaledu/selfieattendance/view/FaceVerificationActivity.kt`
- `app/src/main/java/com/digitaledu/selfieattendance/view/TeacherScanFragment.kt`
- `app/src/main/java/com/digitaledu/selfieattendance/view/StudentScanFragment.kt`

This guide describes the constants and behavior in the source code at the time it was generated.
If thresholds are changed in code, this document should be updated at the same time.
