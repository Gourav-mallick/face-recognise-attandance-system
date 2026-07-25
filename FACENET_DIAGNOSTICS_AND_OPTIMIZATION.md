# FaceNet False-Positive Diagnostics & Optimization Guide

This document provides a comprehensive technical analysis of the false-positive issue in the **Selfie Attendance System** (where new/unregistered faces are incorrectly flagged as "already registered" during face registration). It details the root causes based on your active codebase implementation, optimal threshold recommendations, benchmarking methodology, and step-by-step code improvements.

---

## 📌 Current System & Pipeline Analysis (From Codebase)

Based on inspection of `FaceNetHelper.kt` and `FaceRegistrationActivity.kt`:

* **Model**: Pre-trained `facenet.tflite` (Float32, 160×160 RGB input).
* **Output Embedding Dimension**: **128** float vector elements.
* **Preprocessing**: Resizing crop bitmap to $160 \times 160$, pixel normalization using `(Color - 127.5f) / 128.0f`.
* **Normalization**: L2 Unit Normalization applied to single frame outputs: $\|e\|_2 = 1.0$.
* **Distance Metric**: **Euclidean Distance** ($d = \sqrt{\sum (e1_i - e2_i)^2}$).
* **Current Threshold (`DIST_THRESHOLD`)**: 
  * `0.80f` in `FaceRegistrationActivity.kt` (Duplicate registration check).
  * `0.60f` in `TeacherScanFragment.kt` (Attendance verification).
* **Averaging Strategy**: 3 captured frames averaged during enrollment: `(e1 + e2 + e3) / 3f`.

---

## 🔍 Question 1: Most Likely Cause of False-Positive Matches

### **Root Cause #1: Critical Threshold Miscalibration (`0.80` is Too Loose)**
The primary reason for false positives is that **`0.80f` Euclidean distance is far too large** for L2-normalized 128-dimensional vectors.

For unit vectors ($\|u\|_2 = 1, \|v\|_2 = 1$), Euclidean distance $d$ and Cosine Similarity $\cos(\theta)$ are mathematically linked:
$$d = \sqrt{2 - 2 \cos(\theta)} \quad \iff \quad \cos(\theta) = 1 - \frac{d^2}{2}$$

| Euclidean Distance ($d$) | Equivalent Cosine Similarity | Match Evaluation | Behavior in Registration |
|---|---|---|---|
| **0.35** | **93.8%** | Very Strict | Minimal false matches |
| **0.45** | **89.9%** | **Recommended (Optimal)** | **Ideal balance for Attendance** |
| **0.50** | **87.5%** | Standard FaceNet Cutoff | Low false acceptance |
| **0.60** | **82.0%** | Loose | Occasional false matches |
| **0.80 (Current)** | **68.0%** | **CRITICAL ERROR** | **Triggers false duplicates constantly** |

At $d = 0.80$, any face pair with a similarity of **68% or greater** is considered a match. Two completely different individuals with similar skin tone, hair outline, lighting, or camera angle easily achieve $> 68\%$ similarity, causing the registration check to incorrectly flag them as duplicate users.

---

### **Root Cause #2: Averaged Embedding Not Re-Normalized**
In `FaceRegistrationActivity.kt`, 3 frame embeddings are averaged:
```kotlin
val avgEmbedding = FloatArray(e1.size) { i -> (e1[i] + e2[i] + e3[i]) / 3f }
```
Averaging three unit vectors produces a vector with length $\|avgEmbedding\|_2 < 1.0$. Because this vector is **no longer normalized to unit length**, calculating Euclidean distance against stored normalized vectors distorts the metric space and shifts distances closer to 0, artificially increasing false matches.

---

### **Root Cause #3: Lack of Facial Landmark Alignment**
Currently, raw bounding box crops are directly scaled to $160 \times 160$. Head rotation, tilt, or background bleed into the image. Background pixels contribute identical features across different users, artificially elevating cosine similarity between distinct faces.

---

### **Root Cause #4: Linear Database Search ($O(N)$ Scaling)**
As the database size ($N$) increases (e.g. 500 to 5,000 students/staff), linear matching tests a new face against all $N$ existing faces. If the threshold is $0.80$ (where individual false acceptance probability is high), the probability of finding *at least one* false match across $N$ candidates approaches 100%:
$$P(\text{False Match in DB}) = 1 - (1 - \text{FAR})^N$$

---

## 🎯 Question 2: Recommended Distance Threshold

For FaceNet (128-d L2-normalized embeddings):

* **Recommended Euclidean Distance Threshold**: **`0.45`** (Range: `0.40` – `0.50`)
* **Equivalent Cosine Similarity Threshold**: **`0.89`** (Range: `0.87` – `0.92`)

### Recommended Threshold Tuning Matrix:
* **High Security / Strict Duplicate Check**: $d = 0.40$ ($\text{Cosine} \ge 0.92$)
* **Balanced Attendance & Registration**: $d = 0.45$ ($\text{Cosine} \ge 0.89$)
* **Low Light / Loose Scanner**: $d = 0.50$ ($\text{Cosine} \ge 0.875$)

---

## 📊 Question 3: How to Benchmark FAR & FRR on Your Custom Dataset

### Definitions:
* **FAR (False Acceptance Rate)**: Unregistered/different person wrongly accepted as a match ($d \le \text{Threshold}$).
* **FRR (False Rejection Rate)**: Legitimate registered person rejected ($d > \text{Threshold}$).

### Evaluation Protocol:

1. **Collect Test Dataset**:
   * Gather $P$ distinct persons with 3 to 5 images per person under real operating conditions (varying lighting, angles, expressions).
2. **Generate Distance Pairs**:
   * **Intra-Class Pairs (Genuine Pairs)**: Distance between images of the *same* person.
   * **Inter-Class Pairs (Imposter Pairs)**: Distance between images of *different* persons.
3. **Compute Metrics Across Thresholds**:
   Run a sweep of threshold $T$ from $0.20$ to $0.90$ in steps of $0.02$:
   $$\text{FAR}(T) = \frac{\text{Count of Imposter Pairs with } d \le T}{\text{Total Imposter Pairs}}$$
   $$\text{FRR}(T) = \frac{\text{Count of Genuine Pairs with } d > T}{\text{Total Genuine Pairs}}$$
4. **Identify Equal Error Rate (EER)**:
   The threshold where $\text{FAR}(T) \approx \text{FRR}(T)$ is your model's EER point. Set your operating threshold slightly below EER to prioritize zero false duplicates during registration.

---

## 📐 Question 4: Does L2-Normalization Matter?

**YES, L2-normalization is MANDATORY for FaceNet.**

FaceNet was trained using Triplet Loss with the constraint that all output embeddings lie on a hypersphere of unit radius $\|e\|_2 = 1.0$.

### Correct Implementation:

```kotlin
// 1. Re-normalize single vector
fun normalize(embedding: FloatArray): FloatArray {
    val norm = sqrt(embedding.map { it * it }.sum())
    return if (norm > 0f) embedding.map { it / norm }.toFloatArray() else embedding
}

// 2. Re-normalize after averaging multi-frame captures
val avgEmbedding = FloatArray(e1.size) { i -> (e1[i] + e2[i] + e3[i]) / 3f }
val finalNormalizedEmbedding = helper.normalize(avgEmbedding) // REQUIRED!
```

---

## ⚡ Question 5: Matching Strategy for Database Size ($N$ Faces)

| Database Size ($N$) | Recommended Matching Strategy | Complexity | Search Latency |
|---|---|---|---|
| **$N < 1,000$** | **Flat Linear Search + Tight Cutoff ($d \le 0.45$)** | $O(N)$ | $< 5\text{ ms}$ on mobile |
| **$1,000 \le N \le 10,000$** | **Indexed Vector Search (HNSW / SQLite-VSS / ObjectBox Vector)** | $O(\log N)$ | $< 2\text{ ms}$ |
| **$N > 10,000$** | **Server-side FAISS / Milvus Vector Indexing** | $O(\log N)$ | $< 10\text{ ms}$ network |

For your application size ($N < 1,000$), flat linear search is completely fine **provided the threshold is corrected to `0.45`**.

---

## 🛠️ Concrete Code Improvements

### 1. Update `FaceRegistrationActivity.kt`
Change `DIST_THRESHOLD` from `0.80f` to `0.45f` and re-normalize `avgEmbedding`:

```kotlin
// In FaceRegistrationActivity.kt
private val DIST_THRESHOLD = 0.45f  // Changed from 0.80f to 0.45f

// Inside liveCaptureLauncher:
val avgEmbedding = FloatArray(e1.size) { i ->
    (e1[i] + e2[i] + e3[i]) / 3f
}
// 🔹 CRITICAL FIX: Re-normalize averaged embedding to unit length
val finalEmbedding = helper.normalize(avgEmbedding)

saveFace(id, finalEmbedding)
```

### 2. Update `FaceNetHelper.kt` (Expose Re-normalization)

```kotlin
class FaceNetHelper(context: Context) {
    // ...
    
    /** Expose normalize so averaged embeddings can be re-normalized */
    fun normalize(embedding: FloatArray): FloatArray {
        val sumSq = embedding.fold(0f) { acc, v -> acc + v * v }
        val norm = sqrt(sumSq)
        if (norm == 0f) return embedding
        return FloatArray(embedding.size) { i -> embedding[i] / norm }
    }
}
```

### 3. Synchronize Threshold across Scan Fragments
Ensure `TeacherScanFragment.kt`, `StudentScanFragment.kt`, `FaceRecogniseActivity.kt`, and `FaceRegistrationActivity.kt` use a consistent `DIST_THRESHOLD = 0.45f`.

---

## 📑 Summary of Action Items

1. **Change Threshold**: Lower `DIST_THRESHOLD` in `FaceRegistrationActivity.kt` from **`0.80f`** to **`0.45f`**.
2. **Re-normalize Averaged Embeddings**: Call `helper.normalize(avgEmbedding)` after 3-frame capture.
3. **Facial Alignment**: Ensure crops centered on faces (via ML Kit Face Detector bounds) are padded symmetrically before feeding into `160x160` FaceNet input.
