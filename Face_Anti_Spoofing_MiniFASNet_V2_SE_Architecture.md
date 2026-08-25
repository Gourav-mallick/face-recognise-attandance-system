# Face Anti-Spoofing Design for Android Face Recognition Attendance

## 1. Document Purpose

This document defines the recommended anti-spoofing architecture for an Android face-recognition attendance system that currently uses:

- YuNet for face detection
- SFace for face recognition
- Local + server-side face templates
- Camera-based attendance capture

### Primary threat

The immediate security problem is a **replay attack**:

> A student displays a prerecorded face video/photo on another phone, the attendance device detects the displayed face, SFace recognizes it, and attendance is incorrectly marked.

The anti-spoofing layer must therefore detect presentation attacks before the system accepts the SFace identity result.

---

# 2. Final Recommendation

## Recommended first production candidate

**MiniFASNet V2-SE**

Recommended deployment candidate:

- MiniFASNet V2-SE
- RGB input
- ONNX or INT8 ONNX deployment
- On-device inference
- YuNet face crop as input
- Temporal aggregation across multiple frames
- Active challenge-response as an additional security layer
- SFace only after liveness passes

A current open-source V2-SE implementation reports:

- 128×128 RGB input
- 1.82 MB ONNX model
- 600 KB INT8 ONNX model
- approximately 98.20% accuracy on 70k+ CelebA-Spoof samples
- ROC-AUC approximately 0.9984
- Apache-2.0 licensing for that implementation

These are useful engineering indicators, but **they are not a guarantee of performance on the application's Android devices**.

Source:
https://github.com/facenox/face-antispoof-onnx

The original Silent-Face-Anti-Spoofing project also provides Android deployment code and describes its approach as detecting faces presented through printed photos, electronic displays, silicone masks and other media. Its MiniFASNetV2 is approximately 0.435M parameters and 0.081 GFLOPs.

Source:
https://github.com/minivision-ai/Silent-Face-Anti-Spoofing

---

# 3. Why MiniFASNet V2-SE Is a Strong Fit

## 3.1 It is designed for face anti-spoofing

This is not a generic image classifier.

The architecture is specifically intended to classify:

- Real face
- Spoof face

The implementation also describes the use of Fourier-domain supervision to learn frequency characteristics useful for distinguishing real facial texture from presentation media.

This is relevant to:

- phone-screen replay
- printed photographs
- display artifacts
- moiré/frequency patterns
- screen reflections

Source:
https://github.com/facenox/face-antispoof-onnx

---

## 3.2 Very small model

The current V2-SE implementation provides:

| Format | Approximate size |
|---|---:|
| PyTorch | 1.95 MB |
| ONNX | 1.82 MB |
| INT8 ONNX | 600 KB |

This is excellent for Android deployment.

A small model means:

- lower APK/model download size
- lower memory pressure
- faster startup
- suitable for low/mid-range Android devices
- practical offline inference

Source:
https://github.com/facenox/face-antispoof-onnx

---

## 3.3 Suitable for edge/on-device processing

The anti-spoofing decision can happen locally:

```text
Camera
  ↓
YuNet
  ↓
Face Crop
  ↓
MiniFASNet V2-SE
  ↓
Real / Spoof
```

The raw camera stream does not need to be uploaded to the server.

This is preferable for:

- latency
- privacy
- offline attendance
- bandwidth
- server cost

---

## 3.4 Compatible with the existing architecture

Current:

```text
Camera
   ↓
YuNet
   ↓
SFace
   ↓
Attendance
```

Recommended:

```text
Camera
   ↓
YuNet
   ↓
Face Quality
   ↓
MiniFASNet V2-SE
   ↓
Liveness Decision
   ↓
SFace
   ↓
Server Validation
   ↓
Attendance
```

This allows the anti-spoofing feature to be introduced without replacing YuNet or SFace.

---

# 4. Important Limitation

Do **not** claim:

> "MiniFASNet V2-SE makes the application 100% anti-spoof."

No RGB-only model can provide that guarantee.

The model's published performance is dependent on:

- dataset
- camera
- lighting
- attack medium
- distance
- face size
- display technology
- preprocessing
- threshold

The current V2-SE implementation itself states that it works best with well-lit, frontal faces.

Therefore:

> The model must be validated on the actual Android devices and attack scenarios used by the attendance application.

---

# 5. Attack Model

The system should explicitly defend against the following.

## Priority 1 — Critical

- Video displayed on another phone
- Video displayed on tablet
- Video displayed on laptop
- High-resolution replay video
- Replay video recorded from another phone
- Photo displayed on phone
- Screenshot displayed on phone

## Priority 2

- Printed photograph
- Low-quality printed photograph
- Different display brightness
- OLED display
- LCD display
- Different viewing angles

## Advanced

- 3D mask
- Deepfake displayed on a screen
- Camera-frame injection
- Rooted/modified device
- Virtual camera

---

# 6. Complete Anti-Spoofing Architecture

```text
                    CameraX
                       │
                       ▼
                    YuNet
                       │
                       ▼
              Face Quality Check
                       │
          ┌────────────┴────────────┐
          │                         │
          ▼                         ▼
   Passive Liveness           Face Landmarks
   MiniFASNet V2-SE                 │
          │                         ▼
          │                  Active Challenge
          │                  Turn / Blink / Look
          │                         │
          └────────────┬────────────┘
                       ▼
                Liveness Gate
                       │
                ┌──────┴──────┐
                │             │
              SPOOF           LIVE
                │             │
              Reject          ▼
                         SFace Recognition
                               │
                               ▼
                        Identity Match
                               │
                               ▼
                       Server Validation
                               │
                               ▼
                      Attendance Created
```

---

# 7. Do Not Run SFace First

A common mistake is:

```text
Face
 ↓
SFace
 ↓
Student identified
 ↓
Liveness
```

Do not use this as the acceptance flow.

Use:

```text
Face
 ↓
Liveness
 ↓
Identity
```

The identity result should never be sufficient by itself.

A spoofed face can be correctly recognized by SFace.

SFace answers:

> Who does this face resemble?

Anti-spoofing answers:

> Is the biometric presentation coming from a live person?

These are different security questions.

---

# 8. Recommended Attendance Flow

## Step 1 — Start attendance session

Server creates:

```json
{
  "sessionId": "ATT-2026-08-25-001",
  "challengeId": "random-value",
  "expiresAt": "short-lived-time"
}
```

The challenge should be unpredictable.

---

## Step 2 — Camera starts

Use CameraX.

Recommended target:

- front camera
- stable exposure
- sufficient face size
- multiple frames instead of a single frame

---

## Step 3 — YuNet detection

YuNet provides:

- bounding box
- facial landmarks

Reject if:

- no face
- multiple faces
- face too small
- face heavily cropped
- excessive blur
- extreme pose

---

# 9. Face Quality Gate

Before anti-spoofing:

```text
Face Size
Blur
Brightness
Pose
Occlusion
Landmark validity
```

Example:

```text
if faceSize < minimum:
    reject

if blurScore < minimum:
    reject

if brightness outside range:
    reject

if yaw > allowedRange:
    reject
```

Do not use arbitrary values permanently.

Calibrate them using your real device fleet.

---

# 10. MiniFASNet V2-SE Input

Use the exact preprocessing required by the selected model artifact.

One current V2-SE implementation specifies:

- 128×128 RGB input

Another upstream-derived V2 ONNX conversion specifies an 80×80 BGR input and a 2.7× face crop scale.

Therefore:

> Never assume preprocessing from one MiniFASNet repository applies to another model file.

The preprocessing, model weights and inference code must be treated as one versioned package.

Sources:

https://github.com/facenox/face-antispoof-onnx

https://huggingface.co/garciafido/minifasnet-v2-anti-spoofing-onnx

---

# 11. Do Not Decide From One Frame

This is critical.

Bad:

```text
Frame 1
 ↓
MiniFAS
 ↓
0.91
 ↓
LIVE
```

Better:

```text
Frames 1..N
 ↓
MiniFASNet V2-SE
 ↓
scores
 ↓
temporal aggregation
 ↓
liveness decision
```

Example:

```text
0.93
0.95
0.94
0.91
0.96
0.94
```

Then aggregate.

Possible approach:

```text
median(livenessScores)
```

or use a robust percentage rule such as:

```text
at least X of N frames must pass
```

The exact N and threshold must be calibrated from your validation dataset.

---

# 12. Active Challenge-Response

Passive liveness alone should not be your only defense.

Add a random challenge.

Examples:

```text
TURN_LEFT
TURN_RIGHT
BLINK_TWICE
LOOK_UP
LOOK_DOWN
SMILE
```

Better:

```text
TURN_LEFT
      ↓
BLINK
      ↓
LOOK_UP
```

The sequence should be randomly generated.

Do not always use:

```text
BLINK
```

because a prerecorded video can contain a blink.

The challenge should be:

- random
- short-lived
- generated after attendance starts
- validated from landmarks
- associated with the attendance session

---

# 13. Why Active Challenge Helps

Attack:

```text
Student A records video
        ↓
Video played on Student B phone
        ↓
Attendance camera
```

If the application randomly asks:

```text
TURN LEFT → BLINK → LOOK UP
```

the prerecorded video is unlikely to contain the exact required sequence at the correct time.

This adds a second independent signal.

---

# 14. Head Pose Verification

Use YuNet landmarks and/or OpenCV `solvePnP()`.

Estimate:

```text
Yaw
Pitch
Roll
```

Example challenge:

```text
TURN_LEFT
```

Expected:

```text
yaw changes from approximately neutral
to left-side range
and remains there briefly
```

Do not validate only one frame.

Validate the movement over time.

---

# 15. Blink Verification

Calculate Eye Aspect Ratio (EAR) from eye landmarks.

Conceptually:

```text
Eye open
   ↓
EAR high

Eye closes
   ↓
EAR decreases

Eye opens
   ↓
EAR increases
```

A blink should therefore be:

```text
open → closed → open
```

not simply:

```text
EAR < threshold
```

Calibrate thresholds per camera/environment rather than using a universal value.

---

# 16. Liveness + Challenge Decision

Do not use:

```text
livenessScore > 0.5
```

as the only decision.

Use multiple conditions:

```text
Face quality       PASS
MiniFAS            PASS
Challenge          PASS
Temporal stability PASS
Identity           PASS
Device integrity   PASS
Server validation  PASS
```

Then:

```text
MARK ATTENDANCE
```

---

# 17. Suggested Decision Engine

```text
                    Candidate Face
                          │
                          ▼
                    Quality Gate
                          │
                    PASS / FAIL
                          │
                         PASS
                          ▼
                 MiniFAS V2-SE
                          │
                 Real / Spoof
                          │
                         REAL
                          ▼
                 Active Challenge
                          │
                    PASS / FAIL
                          │
                         PASS
                          ▼
                    SFace Match
                          │
                    Match / No Match
                          │
                        MATCH
                          ▼
                  Device Integrity
                          │
                         PASS
                          ▼
                  Server Validation
                          │
                         PASS
                          ▼
                  MARK ATTENDANCE
```

---

# 18. Do Not Use a Fixed Threshold From the Internet

This is one of the most important engineering rules.

Do not copy:

```text
threshold = 0.85
```

from a GitHub demo.

The correct threshold depends on:

- camera
- face crop
- lighting
- model version
- preprocessing
- quantization
- students
- attack devices

You should derive the threshold from your own validation dataset.

---

# 19. Build Your Own Anti-Spoof Dataset

This is mandatory before production.

## Genuine samples

Collect:

- multiple students
- multiple Android devices
- different lighting
- different distances
- different face angles
- glasses/no glasses
- normal classroom conditions

## Spoof samples

For each student's face, create:

```text
Phone replay
Tablet replay
Laptop replay
Printed photo
Screenshot
High-quality video
Low-quality video
Different brightness
Different viewing angles
```

---

# 20. Most Important Test

Your exact attack:

```text
Student A
   ↓
Record face video
   ↓
Play video on Student B phone
   ↓
Student B points attendance phone at screen
   ↓
System attempts attendance
```

Repeat this using different:

- replay phones
- attendance phones
- video qualities
- screen brightness
- viewing angles
- distances
- lighting

This should become your primary security benchmark.

---

# 21. Metrics

Do not evaluate only with accuracy.

Use:

## APCER

Attack Presentation Classification Error Rate.

Question:

> How often does the system incorrectly accept a spoof?

This is extremely important for attendance.

## BPCER

Bona Fide Presentation Classification Error Rate.

Question:

> How often does the system incorrectly reject a genuine student?

## ACER

Combined error measure.

ISO/IEC 30107-3:2023 defines principles and methods for evaluating and reporting biometric presentation attack detection performance.

Source:
https://committee.iso.org/standard/79520.html

---

# 22. Security Priority

For your attendance system:

```text
APCER
  ↓
Highest priority

BPCER
  ↓
Second priority

Latency
  ↓
Third priority
```

Why?

False rejection:

```text
Real student
 ↓
Rejected
 ↓
Student retries
```

False acceptance:

```text
Spoof
 ↓
Accepted
 ↓
False attendance
```

False acceptance is the more serious security failure.

---

# 23. Device Diversity Testing

Do not train and test only on one phone.

Example:

```text
TRAIN:
Samsung
Xiaomi

TEST:
OnePlus
Vivo
Oppo
Realme
Samsung different model
```

This tests generalization.

A model that has memorized one display/camera signature is not sufficiently robust.

---

# 24. Lighting Test Matrix

Test at least:

```text
Bright indoor
Normal classroom
Low light
Backlight
Window light
Warm artificial light
Cool LED light
Uneven lighting
```

Also test:

```text
screen brightness 20%
screen brightness 50%
screen brightness 100%
```

---

# 25. Distance Test

Test:

```text
20 cm
30 cm
40 cm
50 cm
60 cm
```

The exact acceptable range should be defined by your application UX.

Do not allow an extremely tiny face ROI because anti-spoofing performance will degrade.

---

# 26. Multiple Face Protection

If attendance is supposed to verify one student:

```text
faces == 0
    reject

faces > 1
    reject

faces == 1
    continue
```

Do not silently choose the largest face.

This prevents ambiguity.

---

# 27. Prevent Student A From Marking Student B

Your system should enforce:

```text
1 attendance session
        +
1 live face
        +
1 identity
        +
1 device/session
```

The server should be authoritative.

Example:

```text
sessionId
studentId
deviceId
attendancePeriodId
timestamp
livenessResult
faceMatchScore
challengeResult
```

Then enforce a unique server-side attendance constraint.

---

# 28. Replay the Same Successful Capture

Do not trust a previously successful local result.

Every attendance attempt should contain a fresh:

```text
session nonce
challenge
timestamp
device/session binding
```

The server should reject stale submissions.

---

# 29. Device Integrity

Anti-spoofing does not protect against a compromised Android application.

Add:

- Google Play Integrity
- APK signature validation
- root detection where appropriate
- debugger/tamper detection
- emulator policy
- server-issued nonce
- TLS
- certificate pinning where appropriate
- no hard-coded server secrets

The security architecture is:

```text
Biometric Security
+
Application Security
+
Device Security
+
Server Security
```

---

# 30. Camera Injection Threat

A face anti-spoofing model primarily addresses presentation attacks at the camera.

A different threat is:

```text
Fake camera frame
       ↓
Camera pipeline
       ↓
Application
```

This cannot be solved reliably by simply increasing the liveness threshold.

For high-security deployments, separately address:

- rooted devices
- virtual cameras
- modified APKs
- instrumentation
- debug builds
- compromised OS

---

# 31. Local Storage Security

Your face embeddings should be treated as sensitive biometric data.

Recommended:

```text
Android Keystore
      ↓
Encryption key
      ↓
Encrypted local database
      ↓
Encrypted SFace template
```

Do not store:

```text
plain JSON
plain SQLite embedding
unencrypted face image
```

unless there is a justified requirement.

---

# 32. Server Architecture

Recommended:

```text
Android
   │
   │ HTTPS
   ▼
API Gateway
   │
   ▼
Attendance Service
   │
   ├── Session validation
   ├── Nonce validation
   ├── Device validation
   ├── Period lock
   ├── Student validation
   └── Attendance transaction
```

The server should never blindly trust:

```json
{
  "present": true
}
```

from the device.

Instead, send evidence/results and let the server apply business rules.

---

# 33. Recommended Attendance Payload

Example:

```json
{
  "attendanceSessionId": "SESSION_ID",
  "studentId": "STUDENT_ID",
  "deviceSessionId": "DEVICE_SESSION_ID",
  "challengeId": "CHALLENGE_ID",
  "challengePassed": true,
  "livenessPassed": true,
  "livenessScore": 0.94,
  "faceMatchScore": 0.91,
  "timestamp": 1787660000
}
```

Do not treat these fields as cryptographically trustworthy merely because they exist.

Bind the request to a server-issued nonce/session and validate the transaction server-side.

---

# 34. Model Versioning

Every model must have:

```text
modelName
modelVersion
modelHash
preprocessingVersion
thresholdVersion
```

Example:

```text
model:
  name: MiniFASNetV2-SE
  version: 1.0.0
  sha256: <HASH>

preprocessing:
  version: 1.0.0

threshold:
  version: 1.0.0
```

This prevents a future model update from silently changing attendance behavior.

---

# 35. Quantized vs FP32

Test both:

```text
FP32 ONNX
INT8 ONNX
```

Do not assume quantization has no effect in your application.

The current V2-SE implementation reports no accuracy drop between its FP and quantized versions on its CelebA-Spoof evaluation, but your own validation should still confirm this. 

Source:
https://github.com/facenox/face-antispoof-onnx

Recommended process:

```text
FP32
 ↓
Evaluate
 ↓
INT8
 ↓
Evaluate
 ↓
Compare APCER/BPCER
 ↓
Benchmark Android
 ↓
Choose
```

---

# 36. Recommended Production Pipeline

```text
CameraX
   │
   ▼
YuNet
   │
   ├── Face Count
   ├── Bounding Box
   └── Landmarks
   │
   ▼
Quality Gate
   │
   ├── Size
   ├── Blur
   ├── Brightness
   ├── Pose
   └── Occlusion
   │
   ▼
MiniFASNet V2-SE
   │
   ▼
Temporal Liveness
   │
   ▼
Active Challenge
   │
   ▼
SFace
   │
   ▼
Identity Match
   │
   ▼
Device Integrity
   │
   ▼
Server Nonce Validation
   │
   ▼
Period/Session Lock
   │
   ▼
Attendance Transaction
```

---

# 37. Recommended Threshold Strategy

Do not start with a universal production threshold.

Use:

```text
Development threshold
        ↓
Validation dataset
        ↓
ROC / DET analysis
        ↓
Select operating point
        ↓
Attack-focused validation
        ↓
Pilot
        ↓
Production threshold
```

Because attendance prioritizes security, select the operating point based on a very low acceptable APCER while keeping BPCER acceptable for real students.

---

# 38. Go/No-Go Criteria

Do not approve the model simply because:

```text
Accuracy = 98%
```

Approve only when:

- Replay attacks are consistently rejected
- High-quality screen replay is rejected
- Unseen display devices are tested
- Unseen attendance phones are tested
- Low-light performance is acceptable
- Genuine students are not excessively rejected
- Android latency is acceptable
- Model license is acceptable
- Model artifact is reproducible
- Threshold is calibrated on your data
- Security testing has been completed

---

# 39. Final Model Decision

## Candidate

**MiniFASNet V2-SE**

## Status

**Recommended for prototype + controlled validation**

## Not yet acceptable as

**"100% spoof-proof production model."**

The reason is not that the model is weak.

The reason is that no published benchmark can prove performance on your exact:

- Android devices
- cameras
- classroom lighting
- replay phones
- student videos
- screen technologies

Your own attack dataset must make the final decision.

---

# 40. Recommended Final Architecture

```text
                 ┌───────────────────┐
                 │      CameraX      │
                 └─────────┬─────────┘
                           │
                           ▼
                 ┌───────────────────┐
                 │       YuNet       │
                 │ Detection + Land. │
                 └─────────┬─────────┘
                           │
                           ▼
                 ┌───────────────────┐
                 │   Quality Gate    │
                 └─────────┬─────────┘
                           │
                           ▼
                 ┌───────────────────┐
                 │ MiniFASNet V2-SE  │
                 │ Passive Liveness  │
                 └─────────┬─────────┘
                           │
                           ▼
                 ┌───────────────────┐
                 │ Temporal Decision │
                 └─────────┬─────────┘
                           │
                           ▼
                 ┌───────────────────┐
                 │ Active Challenge  │
                 │ Random Action     │
                 └─────────┬─────────┘
                           │
                           ▼
                 ┌───────────────────┐
                 │   SFace Match     │
                 └─────────┬─────────┘
                           │
                           ▼
                 ┌───────────────────┐
                 │ Device Integrity  │
                 └─────────┬─────────┘
                           │
                           ▼
                 ┌───────────────────┐
                 │ Server Validation │
                 └─────────┬─────────┘
                           │
                           ▼
                 ┌───────────────────┐
                 │ Attendance DB     │
                 └───────────────────┘
```

---

# 41. Key Engineering Principle

Do not try to solve the entire security problem with one neural network.

Use **defense in depth**:

```text
MiniFASNet
    +
Temporal behavior
    +
Random challenge
    +
Face quality
    +
SFace
    +
Device integrity
    +
Server nonce
    +
Server-side attendance lock
```

This is much more robust than:

```text
YuNet + SFace + AntiSpoof Model
```

alone.

---

# 42. References

- MiniFASNet / Silent Face Anti-Spoofing:
  https://github.com/minivision-ai/Silent-Face-Anti-Spoofing

- MiniFASNet V2-SE ONNX implementation:
  https://github.com/facenox/face-antispoof-onnx

- MiniFASNet V1SE/V2 ONNX utilities:
  https://github.com/yakhyo/face-anti-spoofing

- MiniFASNet V2 ONNX model card:
  https://huggingface.co/garciafido/minifasnet-v2-anti-spoofing-onnx

- ISO/IEC 30107-3:2023 PAD testing/reporting:
  https://committee.iso.org/standard/79520.html

---

# Final Recommendation

**Select MiniFASNet V2-SE as the first anti-spoofing model to integrate and benchmark.**

Do not finalize it solely from published accuracy.

The production approval should happen only after your own test proves:

```text
                    REAL STUDENT
                         │
                         ▼
                    ACCEPTED
                         ✓

                    VIDEO REPLAY
                         │
                         ▼
                    REJECTED
                         ✓

                    PHOTO REPLAY
                         │
                         ▼
                    REJECTED
                         ✓

                 HIGH QUALITY REPLAY
                         │
                         ▼
                    REJECTED
                         ✓
```

The most important acceptance metric for your application is **low APCER against real-world replay attacks**, followed by an acceptable BPCER for genuine students.

