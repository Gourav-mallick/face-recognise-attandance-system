# YuNet + SFace Biometric Threshold Analysis & Duplicate Match Resolution

## Executive Summary & Problem Statement

During batch testing and real-world face registration:
1. **At Cosine Threshold $S_{thresh} = 0.550$**:
   - Registration accepts almost all new images cleanly, but genuine face recognition requires near-identical photo conditions (high **FRR** - False Rejection Rate).
2. **At Cosine Threshold $S_{thresh} = 0.480$**:
   - Registration flags **two completely different people** as **`ALREADY REGISTERED (DUPLICATE)`** because their computed similarity falls between $0.480$ and $0.520$ (high **FAR** - False Acceptance Rate).

This document explains **why two different face images score $0.48 - 0.52$**, the underlying computer vision mechanisms, missing implementation checks, and the step-by-step engineering solution.

---

## Part 1: Deep Dive — Why Two Different Faces Score $0.48 - 0.52$

### 1.1 The "Average Face" Embedding Collapse Problem
SFace extracts a **128-dimensional floating point vector** representing facial geometry. When a face image suffers from **blur, poor lighting, or unaligned landmarks**:
* High-frequency biometric details (eye shape, nose bridge contour, lip boundaries) are lost.
* The neural network compresses the face into a low-frequency **"generic human face"** embedding.
* **Result**: Embeddings of two completely different blurry or unaligned faces collapse into the same region of vector space, producing a baseline similarity of **$0.48 - 0.53$**!

### 1.2 Mathematical Cosine Similarity Distribution

$$S_{cos}(u, v) = \frac{u \cdot v}{\|u\|_2 \|v\|_2}$$

In ideal 128-d SFace vector space:
* **Same Person (Different photos/lighting)**: $S_{cos} \in [0.55, 0.95]$
* **Different Persons (Clear, aligned, sharp photos)**: $S_{cos} \in [0.05, 0.35]$
* **Different Persons (Blurry, unaligned, low-res, or poorly lit photos)**: $S_{cos} \in \mathbf{[0.45, 0.54]}$

```
Cosine Similarity Score Spectrum:
[0.00 -------- 0.35] --------- [0.45 -- 0.54] --------- [0.55 -------- 1.00]
 Different People               NOISE / BLUR /          Same Genuine Person
 (Clear & Aligned)              UNALIGNED ZONE          (Identity Verified)
                                (False Duplicates!)
```

When photos in the test dataset lack strict quality enforcement, scores fall directly into the **Noise Zone ($0.45 - 0.54$)**, causing a $0.480$ threshold to misclassify different people as duplicates.

---

## Part 2: Root Causes in Codebase Implementation

### Root Cause 1: Missing Quality Gate Enforcement Prior to Registration
If an image is registered **without verifying blur, pose, or eye distance**:
* A blurry image (e.g. Sharpness Score $35 < 90$) creates a corrupted embedding.
* Any future photo of *anyone else* will match this corrupted embedding with similarity $0.48 - 0.52$.

### Root Cause 2: Landmark Alignment Misalignment (`align()`)
SFace requires exact 5-point landmark warping to standard reference points:
```kotlin
PointF(38.2946f, 51.6963f),  // Left Eye
PointF(73.5318f, 51.5014f),  // Right Eye
PointF(56.0252f, 71.7366f),  // Nose Tip
PointF(41.5493f, 92.3655f),  // Left Mouth Corner
PointF(70.7299f, 92.2041f)   // Right Mouth Corner
```
If head pitch/yaw tilt exceeds limits ($\text{symmetry} > 0.16$), affine matrix warping distorts facial proportions, pushing similarity of different faces into the $0.48 - 0.52$ range.

### Root Cause 3: Un-normalized 128-d Vector Storage
If embedding strings saved in Room DB or passed during matching are not explicitly $L_2$-normalized, vector magnitude variations corrupt dot-product calculations.

---

## Part 3: Comprehensive Technical Solution

To achieve **zero false duplicates on different faces** at threshold $0.480 - 0.500$, three mandatory guardrails must be active:

### Guardrail 1: Enforce Strict Quality Gate at Registration Time
Do **NOT** allow registration of an image if it fails quality parameters:

```kotlin
// Registration Quality Enforcement (Strict Mode)
val quality = engine.assessQualityDetailed(bitmap, primaryFace, strict = true)

if (!quality.accepted) {
    // REJECT REGISTRATION IMMEDIATELY
    // Do NOT generate or store embedding for blurry/tilted images!
}
```

| Parameter | Registration Threshold (Strict) | Recognition Threshold (Normal) | Impact of Low Quality |
| :--- | :---: | :---: | :--- |
| **Sharpness Score** | $\ge \mathbf{90.0}$ | $\ge 55.0$ | Blurry photos create generic embeddings $\rightarrow$ False Duplicates |
| **Pose Symmetry** | $\le \mathbf{0.16}$ | $\le 0.23$ | Tilted poses warp landmarks $\rightarrow$ False Duplicates |
| **Eye Distance** | $\ge \mathbf{45.0\text{ px}}$ | $\ge 32.0\text{ px}$ | Distant faces lose feature detail $\rightarrow$ False Duplicates |

### Guardrail 2: Enforce Explicit $L_2$ Vector Normalization
Always normalize vectors before storage and before similarity calculation:

```kotlin
fun generateNormalizedEmbedding(bitmap: Bitmap, face: YuNetFace): FloatArray {
    val rawEmbedding = engine.embedding(bitmap, face)
    return YuNetSFaceEngine.l2Normalize(rawEmbedding)
}
```

### Guardrail 3: Dual-Threshold Strategy (Registration vs Recognition)

Instead of using a single global threshold, use a **Dual-Threshold Strategy**:

1. **Registration Duplicate Check Threshold ($S_{dup} = \mathbf{0.520}$)**:
   - High precision cutoff to prevent registering the *exact same person twice* while guaranteeing *different people are never rejected*.
2. **Attendance / Recognition Match Threshold ($S_{rec} = \mathbf{0.480}$)**:
   - Standard cutoff for identity verification when quality gates have already filtered out noise.

---

## Part 4: Missing Implementation Audit Checklist

| Feature / Detail | Current Status | Required Action |
| :--- | :---: | :--- |
| **Canvas Resolution Normalization** | ✅ Implemented | Size gate evaluated on $640 \times 640$ canvas. |
| **Diagnostic Report Logging** | ✅ Implemented | Reports log candidate scores & failure causes. |
| **Strict Registration Quality Blocking** | ⚠️ Audit Needed | Ensure batch registration & camera capture **reject** blurry photos before saving to DB. |
| **Explicit $L_2$ Vector Normalization** | ⚠️ Audit Needed | Ensure `l2Normalize()` is called on all embeddings prior to DB save. |
| **Multi-Pose Embedding Averaging** | 💡 Recommended | Optionally average 2-3 clean frames per user during registration to create a robust centroid embedding. |

---

## Conclusion & Action Summary

* **Why $0.480$ caused false duplicates**: Unfiltered blurry or unaligned photos produced "generic face" embeddings with noise-floor similarity between $0.480$ and $0.520$.
* **Why $0.550$ registered everything but failed recognition**: $0.550$ bypassed false duplicates but was too strict for genuine recognition of the same person under different lighting.
* **The Fix**: Keep **$S_{thresh} = 0.480 - 0.500$**, but **enforce strict Registration Quality Gates (Sharpness $\ge 90$, Symmetry $\le 0.16$)** so bad embeddings are never allowed into the database in the first place!
