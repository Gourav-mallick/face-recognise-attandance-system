package com.digitaledu.selfieattendance.utility

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DataSource
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

class EncryptedMediaDataSource(
    private val encryptedFile: File,
    private val ivBase64: String
) : BaseDataSource(/* isNetwork = */ false) {

    private var inputStream: InputStream? = null
    private var tempFile: File? = null
    private var uri: Uri? = null
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)

        val temp = File.createTempFile("temp_datasource_", ".mp4")
        if (!EncryptionManager.decryptToFile(encryptedFile, temp, ivBase64)) {
            temp.delete()
            throw java.io.IOException("Failed to decrypt file for EncryptedMediaDataSource: ${encryptedFile.absolutePath}")
        }
        tempFile = temp
        val fis = FileInputStream(temp)
        this.inputStream = fis

        if (dataSpec.position > 0) {
            fis.skip(dataSpec.position)
        }

        if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            bytesRemaining = dataSpec.length
        } else {
            bytesRemaining = temp.length() - dataSpec.position
        }

        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val bytesToRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            Math.min(bytesRemaining, length.toLong()).toInt()
        }

        val bytesRead = inputStream?.read(buffer, offset, bytesToRead) ?: -1
        if (bytesRead == -1) {
            if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
                throw java.io.EOFException()
            }
            return C.RESULT_END_OF_INPUT
        }

        if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            bytesRemaining -= bytesRead
        }
        bytesTransferred(bytesRead)
        return bytesRead
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try {
            inputStream?.close()
        } catch (e: Exception) {
            Log.e("EncryptedDataSource", "Error closing stream", e)
        } finally {
            inputStream = null
            tempFile?.let {
                if (it.exists()) it.delete()
            }
            tempFile = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    class Factory(
        private val encryptedFile: File,
        private val ivBase64: String
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource {
            return EncryptedMediaDataSource(encryptedFile, ivBase64)
        }
    }
}
