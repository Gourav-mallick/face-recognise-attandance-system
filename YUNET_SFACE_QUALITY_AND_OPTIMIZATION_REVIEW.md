# YuNet + SFace Registration and Recognition Quality Review

**Project:** Android Face Recognition Attendance System  
**Review role:** Senior Android and Computer Vision Engineer  
**Date:** 26 July 2026  
**Applies to:** Registration, standalone recognition, teacher scan, and student attendance scan

---

## 1. Executive assessment

The current implementation is a good architectural foundation:

- YuNet detects a face and five facial landmarks.
- The five landmarks are used to align the face to SFace's canonical `112 × 112` input.
- Registration uses stricter quality requirements than recognition.
- SFace embeddings are normalized and compared with cosine similarity.
- Duplicate registration checks student and teacher records.
- All five live camera screens show the same five green landmark points.
- All five live camera flows require an open→closed→open blink before SFace processing.

However, **detecting five landmarks does not automatically mean the frame is suitable for
registration or recognition**. A detector may still locate landmarks in a dark, angled, partially
blurred, or low-detail image. Landmark detection and face-quality acceptance must remain separate
decisions.

The present system correctly separates them and now uses multi-observation enrollment, but several
production improvements are still recommended—especially explicit lighting rejection, real-device
calibration of the sample-consistency and recognition thresholds, template versioning, and real
presentation-attack/liveness protection.

---

## 2. What the five landmarks mean

YuNet returns these five points:

1. Right eye
2. Left eye
3. Nose tip
4. Right mouth corner
5. Left mouth corner

The green dots mean:

> “YuNet found the expected facial geometry in this frame.”

They do **not** independently mean:

- The image is sharp.
- The lighting is sufficient.
- The person is looking perfectly forward.
- The face is live rather than a photo or screen.
- Recognition will succeed.
- The face is close enough for a reliable embedding.

The oval color and guidance text represent the quality decision:

| UI state | Meaning |
|---|---|
| Yellow oval | Face is missing or a quality condition failed |
| Blue oval | Five landmarks were found and the system is evaluating stability |
| Green oval | Landmarks, quality, and stability passed |
| Red oval | Recognition failed or a duplicate/invalid result was found |

---

## 3. Can landmarks appear on a blurry face?

**Yes.**

YuNet can sometimes detect the eyes, nose, and mouth corners in a moderately blurred image because
the broad facial structure is still visible. This is expected detector behavior.

The current application handles this in two stages:

1. YuNet detects the face and draws the five green points.
2. `YuNetSFaceEngine.assessQuality()` aligns the face and calculates Laplacian variance to measure
   sharpness.

Current sharpness requirements:

| Workflow | Required sharpness |
|---|---:|
| Registration | `≥ 90` |
| Recognition / teacher / student scan | `≥ 55` |

Therefore, a blurry face may briefly show green landmark dots, but the frame should not be
auto-captured while its sharpness is below the required threshold. The guidance displays:

> “Hold still — image is blurry”

### Important limitation

Laplacian variance changes with camera resolution, noise, exposure, skin texture, and device image
processing. Values `90` and `55` are reasonable starting values, not universal production values.
They must be calibrated using images collected from the actual supported phones.

---

## 4. What happens when lighting changes?

### Current behavior

The recognition and attendance screens calculate average brightness:

| Screen | Low-light warning level |
|---|---:|
| Standalone recognition | Brightness `< 45` |
| Teacher scan | Brightness `< 40` |
| Student scan | Brightness `< 40` |

These thresholds currently produce a warning, but **low brightness does not directly block
embedding extraction**. Registration also does not currently have a dedicated brightness gate.
It depends mainly on landmark confidence and sharpness.

This means YuNet may still produce landmarks in:

- A dark room
- Strong light from one side
- Backlighting
- An overexposed face
- A face with deep shadows around one eye

SFace may then generate a lower-quality embedding even if the landmarks are present.

### Recommended production behavior

Registration should reject a frame when any of these conditions occur:

- Mean face brightness is too low.
- Mean face brightness is too high.
- One side of the face is significantly darker than the other.
- Highlight clipping covers a large part of the face.
- Shadow clipping hides an eye or mouth corner.

Recommended starting gates, to be calibrated per device:

| Measurement | Suggested starting range |
|---|---:|
| Mean brightness on aligned face | `55–210` |
| Very dark pixels | `< 20%` of aligned face |
| Very bright pixels | `< 15%` of aligned face |
| Left/right illumination difference | `< 25%` |

Recognition can use slightly wider ranges than registration, but it should still avoid matching
frames where important facial detail is lost.

---

## 5. What happens when the camera or head angle changes?

The app rotates the CameraX frame using `imageProxy.imageInfo.rotationDegrees`, so portrait and
landscape device rotation are handled before detection.

The five-point alignment then corrects moderate in-plane head roll. For example, a slightly tilted
head can be rotated into the canonical SFace position.

### Current frontal-face test

The current engine checks whether the nose is horizontally close to:

- The midpoint between both eyes
- The midpoint between both mouth corners

Current horizontal symmetry tolerance:

| Workflow | Maximum horizontal nose offset |
|---|---:|
| Registration | `16%` of eye distance |
| Recognition | `23%` of eye distance |

Minimum eye distance:

| Workflow | Minimum |
|---|---:|
| Registration | `45 px` |
| Recognition | `32 px` |

### Current limitation

This is a useful lightweight yaw check, but it is not a complete head-pose calculation:

- Pitch is not measured explicitly.
- Roll is corrected during alignment but is not limited before capture.
- Large yaw may sometimes pass if the landmark geometry is noisy.
- Camera position below the chin or above the forehead is not measured directly.

### Recommended improvement

Calculate yaw, pitch, and roll from the five landmarks or a lightweight pose model. Suggested
registration limits:

| Pose | Suggested registration limit |
|---|---:|
| Yaw | `±10°` |
| Pitch | `±10°` |
| Roll | `±12°` |

Recognition can initially allow approximately `±15°`, because it must work faster in real use.

---

## 6. When does the current app auto-capture?

Registration captures only after:

1. YuNet detection confidence is at least `0.85`.
2. Five landmarks are available.
3. Eye distance is at least `45 px`.
4. The stricter frontal symmetry check passes.
5. The complete face bounding box is inside the image.
6. Aligned-face sharpness is at least `90`.
7. Face movement remains within approximately `3.5%` of face width.
8. All conditions remain stable for `700 ms`.

Recognition uses looser distance, pose, and sharpness requirements and locks after:

- `550 ms` on `FaceRecogniseActivity`
- Approximately `700 ms` on teacher/student attendance fragments

This difference is sensible: enrollment should be strict because a bad stored template affects
every future recognition attempt.

---

## 7. How much similarity is required to recognize a person?

The current project uses:

```text
SFace cosine similarity ≥ 0.42
```

Cosine similarity interpretation:

| Score | Meaning |
|---:|---|
| Near `1.0` | Very similar embeddings |
| Near `0.0` | Unrelated embeddings |
| Below `0.0` | Strongly dissimilar embeddings |

The application selects the highest-scoring registered face and accepts it only if the score is at
least `0.42`.

The official OpenCV SFace example uses `0.363` as a general cosine threshold. This project uses
`0.42`, which is more conservative and should reduce false acceptance, but it can increase false
rejection under poor lighting, pose changes, aging, masks, or weak registration images.

Reference: [OpenCV Zoo SFace implementation](https://github.com/opencv/opencv_zoo/blob/main/models/face_recognition_sface/sface.py)

### Do not choose the final threshold by guesswork

The production threshold must be calibrated on representative data:

1. Collect multiple samples from each enrolled person.
2. Include different lighting, phones, distances, glasses, hairstyles, and moderate pose changes.
3. Calculate genuine scores: same person versus same person.
4. Calculate impostor scores: different person versus different person.
5. Select a threshold based on the required False Acceptance Rate and False Rejection Rate.

Suggested validation targets for attendance:

- False Acceptance Rate: preferably below `0.1%`
- False Rejection Rate: preferably below `2–5%`
- Measure separately for each supported device class

Until calibration is complete, keep `0.42` and log anonymized similarity distributions during
controlled testing.

---

## 8. Is the current registration process good?

### What is good

- Registration is automatic rather than dependent on a manual shutter press.
- It applies stricter quality rules than recognition.
- It uses five-point alignment before SFace extraction.
- It blocks a captured face already registered to another user.
- Duplicate results display the existing user's name, ID, role, and match percentage.
- SFace vectors are L2-normalized.
- Camera and model processing run away from the UI thread.

### What should be improved

#### 8.1 Store more than one high-quality observation

Registration now captures three high-quality frontal observations over approximately `1–2
seconds`. Samples are spaced by at least `350 ms`, and each new normalized SFace vector must have
cosine similarity `≥ 0.55` with the earlier observations. If any observation disagrees, the set is
rejected and capture restarts from the current high-quality frame.

After three samples pass:

1. The three normalized embeddings are averaged.
2. The averaged vector is L2-normalized again.
3. The existing registration and duplicate-face flow receives the single final 128-D template.

The Room schema, API payload, and recognition template format remain unchanged. This is more robust
than deliberately asking for large left/right head turns, because SFace expects well-aligned,
mostly frontal faces.

#### 8.2 Add explicit exposure and illumination gates

Landmark confidence and sharpness are not enough. Add brightness, clipping, and face-side balance
checks directly to `FaceQuality`.

#### 8.3 Add model/template version metadata

Legacy FaceNet and new SFace templates both contain 128 numbers but occupy different vector spaces.
Vector length cannot identify the model.

Recommended database/server fields:

```text
templateModel = "sface_2021dec_int8"
templateVersion = 1
templateCreatedAt = timestamp
qualityScore = value
```

Without version metadata, mixed FaceNet/SFace data can produce unreliable comparisons.

#### 8.4 Make duplicate checking model-aware

Only compare SFace queries against confirmed SFace templates. Legacy templates should be marked for
re-enrollment, not included in the duplicate search.

#### 8.5 Reject multiple faces

The current live pipeline selects the largest detected face. During registration, it should instead
require exactly one face. Otherwise a second person in the background can create ambiguity and a
poor user experience.

#### 8.6 Active liveness is implemented; add presentation-attack detection for stronger security

The application now blocks SFace registration and matching until ML Kit observes one complete
open→closed→open blink.

The challenge expires after `12 seconds`, resets when the face disappears, and remains valid for
only a short window. This blocks a static printed photo or a static face image displayed on another
phone.

This active challenge is still not a complete presentation-attack detector. A sophisticated replay
video or deepfake may reproduce a blink. For security-sensitive deployment, add
one or more of:

- Dedicated anti-spoofing ONNX model.
- Randomized blink count or another server-selected challenge for higher-risk deployments.
- Depth/IR checks on supported hardware.
- Screen, moiré, reflection, and replay analysis.

Describe the current feature as **active liveness**, not full spoof-proof biometric security.

---

## 9. Is the current recognition process good?

### What is good

- The same detector, alignment, preprocessing, and SFace model are shared across screens.
- Recognition is faster and less strict than registration.
- Class/user-type filtering reduces irrelevant comparisons.
- Cosine matching over approximately 5,000 normalized 128-D vectors is reasonable on-device.
- Green dots make face tracking understandable to users.

### What should be improved

#### 9.1 Calibrate the `0.42` threshold

Keep it as the initial threshold, then replace it with a measured value based on actual users and
devices.

#### 9.2 Add an ambiguity margin

Do not accept only because the best score exceeds `0.42`. Also compare the best and second-best
matches.

Example rule:

```text
bestScore ≥ calibratedThreshold
AND
bestScore - secondBestScore ≥ 0.05
```

This avoids accepting an uncertain result where two users have nearly identical scores.

#### 9.3 Require short multi-frame confirmation

Accept the identity only when the same user wins in two or three recent quality frames. This reduces
one-frame errors without making the UI slow.

#### 9.4 Add per-user cooldown

After attendance is recorded, temporarily ignore the same user for several seconds. This prevents
duplicate scans and repeated dialogs.

#### 9.5 Improve camera preprocessing performance

The current implementation converts YUV to JPEG and then decodes it into a Bitmap. This adds CPU
time, memory allocation, and compression artifacts.

Recommended:

- Convert `YUV_420_888` directly to RGB.
- Reuse input buffers and Bitmaps.
- Avoid allocating large arrays for every frame.
- Reuse model sessions at application or lifecycle-owner scope where safe.

#### 9.6 Use PreviewView's real transformation matrix

The landmark overlay currently approximates `FILL_CENTER` mapping. Use CameraX's output transform or
coordinate transformer so green dots remain exact across device aspect ratios, rotations, and
preview cropping.

---

## 10. Recommended quality score

Instead of multiple independent pass/fail checks only, calculate a combined quality score:

```text
quality =
    25% detection confidence +
    25% sharpness +
    20% frontal pose +
    15% illumination +
    10% face size +
     5% frame stability
```

Suggested behavior:

- Registration requires quality `≥ 85/100`.
- Recognition requires quality `≥ 65/100`.
- Save the registration quality with the template.
- Show one actionable instruction corresponding to the weakest component.

The exact weights and thresholds must be validated experimentally.

---

## 11. Prioritized engineering roadmap

### Priority 0 — required before production rollout

1. Add SFace template model/version metadata.
2. Re-enroll legacy FaceNet users.
3. Calibrate the similarity threshold on real institutional data.
4. Add explicit registration lighting/exposure rejection.
5. Reject registration when more than one face is visible.

### Priority 1 — accuracy and security

1. Validate and tune the implemented three-frame consistency threshold on real devices.
2. Add best-versus-second-best ambiguity margin.
3. Confirm recognition across two or three frames.
4. Add a dedicated anti-spoofing model to strengthen the implemented active-liveness challenge.
5. Calculate explicit yaw, pitch, and roll.

### Priority 2 — performance and maintainability

1. Replace JPEG frame conversion with direct YUV-to-RGB conversion.
2. Reuse frame and tensor buffers.
3. Centralize duplicated CameraX frame conversion.
4. Use CameraX coordinate transformations for landmark overlays.
5. Add instrumentation for inference time, quality rejection reason, and similarity distribution.

---

## 12. Recommended test matrix

Test registration and recognition under:

| Condition | Variations |
|---|---|
| Lighting | Dim, normal, bright, backlit, one-sided light |
| Pose | Frontal, yaw, pitch, roll |
| Distance | Too far, acceptable, too close |
| Motion | Still, walking, camera shake |
| Appearance | Glasses, mask, beard, hairstyle changes |
| Device | Low-end, mid-range, high-end Android phones |
| Orientation | Portrait, both landscapes, upside-down sensor rotation |
| Attack | Printed photo, phone display, recorded video |
| Population | Similar-looking users, twins where applicable, varied skin tones and ages |

For every test, record:

- YuNet confidence
- Sharpness
- Lighting metrics
- Pose
- Best similarity
- Second-best similarity
- Accepted/rejected result
- Processing time
- Device model

---

## 13. Final recommendation

The current implementation is suitable for controlled pilot testing, but it should not yet be
treated as a fully calibrated or spoof-resistant production biometric system.

The most important next work is:

1. Add template versioning.
2. Add explicit illumination quality checks.
3. Calibrate the implemented three-frame consistency rule on real devices.
4. Calibrate the `0.42` similarity threshold.
5. Add multi-frame recognition and a dedicated anti-spoofing model.

These improvements will provide a larger accuracy and security benefit than simply changing the
similarity threshold or making the landmark dots more sensitive.
