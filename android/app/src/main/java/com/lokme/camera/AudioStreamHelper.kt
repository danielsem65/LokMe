package com.lokme.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.lokme.network.WsClient
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class AudioStreamHelper(private val context: Context) {

    private var audioRecord: AudioRecord? = null
    private val isRecording = AtomicBoolean(false)
    private var recordThread: Thread? = null

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val TAG = "AudioStream"
    }

    fun startStream(wsClient: WsClient, deviceId: String) {
        if (isRecording.get()) return

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission not granted")
            return
        }

        try {
            val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize * 2
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                audioRecord?.release()
                audioRecord = null
                return
            }

            isRecording.set(true)
            audioRecord?.startRecording()

            recordThread = Thread {
                val buffer = ByteArray(4096)
                val pcmBuffer = ByteArrayOutputStream()

                while (isRecording.get() && !Thread.currentThread().isInterrupted) {
                    try {
                        val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                        if (read > 0) {
                            pcmBuffer.write(buffer, 0, read)

                            if (pcmBuffer.size() >= 8192) {
                                val audioData = pcmBuffer.toByteArray()
                                pcmBuffer.reset()
                                wsClient.sendAudioFrame(deviceId, audioData)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Read error: ${e.message}")
                    }
                }

                try {
                    val remaining = pcmBuffer.toByteArray()
                    if (remaining.isNotEmpty()) {
                        wsClient.sendAudioFrame(deviceId, remaining)
                    }
                } catch (_: Exception) {}

                pcmBuffer.reset()
            }
            recordThread?.isDaemon = true
            recordThread?.start()

            Log.d(TAG, "Audio stream started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio: ${e.message}")
            isRecording.set(false)
        }
    }

    fun stopStream() {
        isRecording.set(false)
        recordThread?.interrupt()
        recordThread = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
        Log.d(TAG, "Audio stream stopped")
    }

    fun isRecording(): Boolean = isRecording.get()
}
