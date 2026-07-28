# Static File Registration vs. Live Human Camera Attendance: Biometric Research & Comparison

## Executive Summary

When deploying a production facial recognition attendance system (YuNet + SFace ONNX), testing and registering faces can be done via two distinct workflows:
1. **Static Image Workflow**: Importing pre-existing photo files (JPEG/PNG downloads, gallery images, ID card scans).
2. **Live Human Camera Workflow**: Interactive live camera capture with real-time feedback, liveness verification, and multi-frame averaging.

This research document provides a comprehensive technical breakdown of **what happens under the hood in both workflows during Registration and Recognition**, evaluates whether static image registration is suitable for live human recognition, and defines the recommended production architecture.

---

## Part 1: Step-by-Step Technical Execution Comparison

### 1.1 Static Image Workflow (Downloaded / Gallery Files)

```
[Static File (JPEG/PNG)] ──► [Bitmap Decode] ──► [Canvas Normalization (640x640)]
                                                            │
                                                            ▼
[128-d Unit Vector DB] ◄── [L2 Norm] ◄── [SFace ONNX] ◄── [Affine Align (112x112)]
```

#### During Registration:
1. **File Ingestion**: Decodes static JPEG/PNG file into an ARGB_8888 bitmap.
2. **Canvas Normalization**: Scales bounding boxes and landmark coordinates to detector canvas resolution ($640 \times 640$).
3. **Single-Frame Detection**: YuNet detects 1 face and 5 facial landmarks (Left Eye, Right Eye, Nose, Left Mouth, Right Mouth).
4. **Quality Assessment**: Evaluates Laplacian variance sharpness, pose symmetry, and eye distance on the single frame.
5. **Affine Landmark Alignment**: Warps landmarks to standard $112 \times 112$ SFace reference points.
6. **Feature Extraction**: Passes $112 \times 112$ crop into SFace ONNX to produce a single 128-dimensional raw vector.
7. **$L_2$ Normalization**: Converts raw vector to unit length ($\|v\|_2 = 1.0$) and stores it as a comma-separated string in Room DB.

#### During Recognition:
1. Performs single-frame detection and extraction on the test file.
2. Computes Cosine Similarity $S_{cos}(u, v) = u \cdot v$ against stored DB embeddings.
3. Compares max score against Cosine Threshold ($S_{thresh} = 0.480$).

---

### 1.2 Live Human Camera Workflow (CameraX 30 fps Stream)

```
[CameraX YUV Stream] ──► [YuNet Tracking] ──► [Pose & Stability Gate]
                                                       │
                                                       ▼
[3-Frame Centroid DB] ◄── [L2 Renorm] ◄── [Average 3 Frames] ◄── [Liveness Check]
```

#### During Registration:
1. **Live Camera Preview Stream**: Receives 30 fps YUV420 sensor frames with on-screen face oval overlay.
2. **Real-time Face Tracking & Stability**: Verifies face position stability over consecutive frames (tolerance $< 3.5\%$ width shift).
3. **Liveness & Quality Verification**: Enforces active liveness (eye blink / micro-expression) and strict quality (Sharpness $\ge 90.0$, Symmetry $\le 0.16$, Eye Distance $\ge 45.0\text{px}$).
4. **Multi-Frame Sampling**: Captures **3 consistent high-quality frames** spaced across time.
5. **Centroid Vector Averaging**: Calculates average vector across 3 samples:
   $$\bar{e}_{centroid} = \frac{e_1 + e_2 + e_3}{3}$$
6. **Final $L_2$ Re-Normalization**: Re-normalizes centroid vector to unit length ($\|e_{final}\|_2 = 1.0$) and stores it in Room DB.

#### During Recognition:
1. Evaluates live stream frame by frame.
2. Displays real-time guide feedback ("Move closer", "Hold still", "Low light").
3. Once stable, extracts single-frame embedding and matches against DB centroids ($S_{cos} \ge 0.480$).

---

## Part 2: Comprehensive Technical Comparison Matrix

| Technical Metric | Static Image Workflow (Gallery / Downloaded) | Live Human Camera Workflow | Biometric Impact & Analysis |
| :--- | :---: | :---: | :--- |
| **Input Frames Available** | Exactly **1 static frame** | Continuous stream (**30 frames/sec**) | Live stream allows selecting peak quality frames; static workflow is locked to 1 file. |
| **Image Resolution & Aspect Ratio** | Varying ($400 \times 600$ to $3000 \times 4000$) | Standardized Camera Sensor ($720 \times 1280$ or $1080 \times 1920$) | Gallery images require canvas normalization ($640 \times 640$) to prevent size gate rejections. |
| **JPEG Compression Noise** | Often **High** (WhatsApp/Web compression) | **Low / None** (Direct camera sensor buffer) | Compressed static images lose high-frequency edge detail, reducing Laplacian sharpness score ($60 - 80$). |
| **Embedding Type** | Single-frame vector | **3-Frame Centroid Vector** | Centroid vectors average out single-frame lighting/shadow noise, boosting recognition accuracy. |
| **Liveness Protection** | **None** (Susceptible to photo spoofing) | **Active / Passive Liveness Verified** | Live workflow prevents photo-replay attack fraud. |
| **Registration Speed** | Instant bulk batch processing | 3 - 5 seconds interactive capture | Static is ideal for initial bulk roster creation; live is ideal for daily enrollment. |

---

## Part 3: Is Static Image Registration Good for Live Human Attendance Recognition?

### The Core Question:
> *"Is registering users using static downloaded/gallery images effective when recognizing live humans in daily attendance scanning?"*

### Research Answer: **YES, BUT WITH CRITICAL CONDITIONS AND LIMITATIONS.**

#### 1. Why Static Image Registration WORKS:
* **Core Deep Feature Invariance**: SFace is trained on deep spatial facial geometry (eye spacing, nose structure, jawline contours). These deep features remain invariant whether extracted from a static photo or a live camera feed.
* **Rapid Bulk Seeding**: Enables importing 500+ student/teacher profiles from school/college database records in a single batch operation without requiring physical presence.

#### 2. The Domain Shift Gaps (Why Errors Occur):
If static image registration is used without quality filtering, **three major failure modes occur during live attendance**:

1. **Blur Collapse & False Duplicate Matches**:
   - If the static registration photo is compressed or blurry (Sharpness $< 90$), its embedding collapses into a "generic face" representation.
   - When a live human scans their face, the system computes $0.48 - 0.52$ similarity against the blurry static embedding, triggering **false duplicate rejections**.
2. **Pose Distortion (Angle Mismatch)**:
   - Static photos taken from a side angle or tilted head distort landmark alignment.
   - When a student looks straight at the attendance tablet camera, recognition fails (**High FRR / False Rejection**).
3. **Lighting Shift**:
   - Static ID photos taken with flash or studio lighting differ significantly from indoor corridor/classroom ambient lighting.

---

## Part 4: Recommended Production Architecture & Hybrid Strategy

To maximize accuracy across both static batch enrollment and live human scanning, enforce the following **Hybrid Production Strategy**:

```
                       ┌─────────────────────────────────────────────────┐
                       │          System Enrollment Strategy             │
                       └────────────────────────┬────────────────────────┘
                                                │
                 ┌──────────────────────────────┴──────────────────────────────┐
                 ▼                                                             ▼
   [Option A: Bulk Static Image Roster]                         [Option B: Live Camera Enrollment]
   • Enforce Strict Quality Gate (Sharpness ≥ 70)              • Interactive on-screen guide oval
   • Filter out blurry ID photos BEFORE import                 • 3-Frame Centroid Averaging (||v|| = 1)
   • Store L2-Normalized 128-d vectors                         • Active Liveness Verified
                 │                                                             │
                 └──────────────────────────────┬──────────────────────────────┘
                                                ▼
                               ┌─────────────────────────────────┐
                               │   Daily Live Attendance Scan    │
                               │   • Threshold S_cos ≥ 0.480     │
                               │   • Quality Gate Normal (S ≥ 55)│
                               └─────────────────────────────────┘
```

### Production Checklist for Maximum Accuracy:

1. **Enforce Registration Quality Gate for Static Imports**:
   - Reject any static image during batch registration if Sharpness $< 70.0$, Pose Symmetry $> 0.16$, or Eye Distance $< 45.0\text{px}$.
2. **Use Multi-Frame Centroid Averaging for Live Registration**:
   - When users register in person via camera, capture 3 consistent frames and average them into a single $L_2$-normalized centroid vector.
3. **Set Thresholds by Mode**:
   - **Registration Duplicate Check**: $S_{dup} = 0.520$
   - **Live Attendance Scan Threshold**: $S_{rec} = 0.480$

---

## Conclusion

* **Static Image Registration** is excellent for initial bulk onboarding of student/staff rosters, provided **strict quality filtering** is enabled during batch import to block blurry images.
* **Live Human Camera Capture** provides superior recognition robustness because it creates a **3-frame centroid embedding** under real-world camera lighting and verifies liveness.
* For optimal production performance, combine static batch onboarding with live camera re-enrollment for users whose initial static ID photos fail quality gates.
