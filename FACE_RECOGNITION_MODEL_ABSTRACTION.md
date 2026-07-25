# Face Model Abstraction Functions (Input / Output Reference)

This document provides the exact functions and parameter specifications for **Embedding Generation** and **Face Recognition Matching**.

---

## 1. Function 1: Get Embedding (Signature)

### **What you pass:**
* **Single Image Mode:** Pass a single cropped face `Bitmap`.
* **Multi-Image Mode (Registration):** Pass a `List<Bitmap>` (e.g., 3 captured images from registration).

### **What you get back:**
* `FloatArray` (The normalized 128-dim or 512-dim face signature vector to store in Database or compare live).

---

```kotlin
/**
 * 🔹 FUNCTION 1A: Get Embedding from a Single Bitmap
 * 
 * PASS: bitmap (Bitmap of cropped face image)
 * GET:  FloatArray (128 or 512 Float values representing face signature)
 */
fun getEmbedding(bitmap: Bitmap): FloatArray {
    val input = preprocess(bitmap) // Resizes to model input size (e.g. 160x160) & normalizes pixels
    val output = Array(1) { FloatArray(MODEL_EMBEDDING_SIZE) }
    
    interpreter.run(input, output)
    
    return normalize(output[0]) // Returns normalized L2 FloatArray signature
}

/**
 * 🔹 FUNCTION 1B: Get Averaged Embedding from Multiple Registration Bitmaps
 * 
 * PASS: images (List<Bitmap> of 3 captured face images from FaceRegistrationActivity)
 * GET:  FloatArray (Final averaged signature ready to save in DB)
 */
fun getEmbeddingFromImages(images: List<Bitmap>): FloatArray {
    val embeddings = images.map { getEmbedding(it) }
    val size = embeddings[0].size
    val avgEmbedding = FloatArray(size)

    for (i in 0 until size) {
        var sum = 0f
        for (emb in embeddings) {
            sum += emb[i]
        }
        avgEmbedding[i] = sum / embeddings.size
    }

    return normalize(avgEmbedding)
}
```

---

## 2. Function 2: Recognise / Match Face

### **What you pass:**
* **`liveSignature`** (`FloatArray`): Signature generated from current camera frame bitmap.
* **`storedSignature`** (`FloatArray`): Signature saved in database for a student/teacher.
* *(Optional)* **`threshold`** (`Float`): Maximum allowed distance for a valid match (default: `0.60f`).

### **What you get back:**
* `Boolean` (`true` if face matches, `false` if face does not match).
* Or `MatchResult` data object containing `isMatch`, `distance`, and `similarityPercentage`.

---

```kotlin
/**
 * 🔹 FUNCTION 2A: 1-to-1 Match (Check if Live Face matches a Stored Face)
 * 
 * PASS: liveSignature   (FloatArray from live camera image)
 * PASS: storedSignature (FloatArray loaded from database)
 * GET:  Boolean (true = Matched, false = Not Matched)
 */
fun isFaceMatched(
    liveSignature: FloatArray,
    storedSignature: FloatArray,
    threshold: Float = DISTANCE_THRESHOLD
): Boolean {
    if (liveSignature.size != storedSignature.size) return false

    val distance = calculateEuclideanDistance(liveSignature, storedSignature)
    return distance <= threshold
}

/**
 * 🔹 FUNCTION 2B: 1-to-N Recognition (Find Matching User from Database List)
 * 
 * PASS: liveSignature (FloatArray from live camera)
 * PASS: userList      (List of cached database users with their saved embeddings)
 * GET:  Matched User object or null if no user matched
 */
data class UserSignature(val userId: String, val userName: String, val embedding: FloatArray)

fun recogniseFace(
    liveSignature: FloatArray,
    userList: List<UserSignature>,
    threshold: Float = DISTANCE_THRESHOLD
): UserSignature? {
    var bestMatch: UserSignature? = null
    var lowestDistance = Float.MAX_VALUE

    for (user in userList) {
        if (liveSignature.size != user.embedding.size) continue
        
        val distance = calculateEuclideanDistance(liveSignature, user.embedding)
        if (distance <= threshold && distance < lowestDistance) {
            lowestDistance = distance
            bestMatch = user
        }
    }

    return bestMatch // Returns matched user or null if unrecognized
}
```

---

## 3. Distance & Model Helper Functions

```kotlin
// Distance Metric Calculation (Euclidean Distance)
private fun calculateEuclideanDistance(e1: FloatArray, e2: FloatArray): Float {
    var sum = 0f
    for (i in e1.indices) {
        val diff = e1[i] - e2[i]
        sum += diff * diff
    }
    return kotlin.math.sqrt(sum)
}

// Vector Normalization
private fun normalize(vector: FloatArray): FloatArray {
    val sumSquare = vector.sumOf { (it * it).toDouble() }.toFloat()
    val norm = kotlin.math.sqrt(sumSquare)
    return if (norm > 0) vector.map { it / norm }.toFloatArray() else vector
}
```

---

## Summary Table

| Goal | Function Name | What You Pass (Inputs) | What You Get (Output) |
|---|---|---|---|
| **Get Embedding for 1 Image** | `getEmbedding()` | `bitmap: Bitmap` | `FloatArray` (Signature vector) |
| **Get Embedding for Registration** | `getEmbeddingFromImages()` | `images: List<Bitmap>` | `FloatArray` (Averaged Signature) |
| **Check 1-to-1 Match** | `isFaceMatched()` | `liveSignature: FloatArray`<br>`storedSignature: FloatArray` | `Boolean` (`true` / `false`) |
| **Recognise 1-to-N from DB** | `recogniseFace()` | `liveSignature: FloatArray`<br>`userList: List<UserSignature>` | `UserSignature?` (User object or `null`) |
