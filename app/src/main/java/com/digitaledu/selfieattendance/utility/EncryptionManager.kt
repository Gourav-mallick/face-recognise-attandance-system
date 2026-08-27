package com.digitaledu.selfieattendance.utility

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object EncryptionManager {

    private const val TAG = "EncryptionManager"
    private const val KEY_ALIAS = "SelfieAttendanceVideoKey"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128
    private const val BUFFER_SIZE = 64 * 1024

    @Synchronized
    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) {
            val entry = keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry
            return entry.secretKey
        }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        val keyGenSpec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()

        keyGenerator.init(keyGenSpec)
        return keyGenerator.generateKey()
    }

    /**
     * Encrypts plain sourceFile into target encryptedFile using AES-256 GCM cipher.
     * Returns Base64 encoded Initialization Vector (IV).
     */
    fun encryptFile(sourceFile: File, encryptedFile: File): String? {
        if (!sourceFile.exists() || sourceFile.length() == 0L) {
            Log.e(TAG, "Source file does not exist or is empty: ${sourceFile.absolutePath}")
            return null
        }

        return try {
            val secretKey = getOrCreateSecretKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)

            FileInputStream(sourceFile).use { fis ->
                FileOutputStream(encryptedFile).use { fos ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int
                    while (fis.read(buffer).also { bytesRead = it } != -1) {
                        val output = cipher.update(buffer, 0, bytesRead)
                        if (output != null && output.isNotEmpty()) {
                            fos.write(output)
                        }
                    }
                    val finalBytes = cipher.doFinal()
                    if (finalBytes != null && finalBytes.isNotEmpty()) {
                        fos.write(finalBytes)
                    }
                    fos.flush()
                }
            }
            Log.d(TAG, "File encrypted successfully to: ${encryptedFile.absolutePath} (${encryptedFile.length()} bytes)")
            ivBase64
        } catch (e: Exception) {
            Log.e(TAG, "Encryption failed for file: ${sourceFile.absolutePath}", e)
            if (encryptedFile.exists()) encryptedFile.delete()
            null
        }
    }

    /**
     * Decrypts encryptedFile into destPlainFile using Cipher update/doFinal.
     * Guarantees 100% correct GCM block & tag authentication.
     */
    fun decryptToFile(encryptedFile: File, destPlainFile: File, ivBase64: String): Boolean {
        if (!encryptedFile.exists() || encryptedFile.length() == 0L) {
            Log.e(TAG, "Encrypted file does not exist or is empty: ${encryptedFile.absolutePath}")
            return false
        }

        return try {
            val secretKey = getOrCreateSecretKey()
            val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

            FileInputStream(encryptedFile).use { fis ->
                FileOutputStream(destPlainFile).use { fos ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int
                    while (fis.read(buffer).also { bytesRead = it } != -1) {
                        val output = cipher.update(buffer, 0, bytesRead)
                        if (output != null && output.isNotEmpty()) {
                            fos.write(output)
                        }
                    }
                    val finalBytes = cipher.doFinal()
                    if (finalBytes != null && finalBytes.isNotEmpty()) {
                        fos.write(finalBytes)
                    }
                    fos.flush()
                }
            }
            Log.d(TAG, "Decrypted file successfully to: ${destPlainFile.absolutePath} (${destPlainFile.length()} bytes)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Decrypt to file failed for: ${encryptedFile.absolutePath}", e)
            if (destPlainFile.exists()) destPlainFile.delete()
            false
        }
    }
}
