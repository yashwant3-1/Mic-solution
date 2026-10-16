package com.wirelesscollarmic.app

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * High-performance, low-latency audio streaming engine.
 * Captures live audio from an external 3.5mm collar mic (or built-in fallback) via AudioRecord
 * and streams it directly to a connected Bluetooth A2DP audio receiver using AudioTrack.
 */
class AudioStreamingEngine(
    private val audioDeviceManager: AudioDeviceManager,
    private val listener: Listener
) {
    interface Listener {
        fun onStreamingStarted()
        fun onStreamingStopped()
        fun onAudioLevelChanged(rmsDb: Float, levelPercent: Int)
        fun onError(errorMessage: String)
        fun onRouteInfoUpdated(inputInfo: String, outputInfo: String)
    }

    companion object {
        private const val TAG = "AudioStreamingEngine"
        const val SAMPLE_RATE = 48000 // 48 kHz native Android hardware HAL standard
        private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val CHUNK_FRAME_COUNT = 512 // ~10.6ms at 48kHz for low latency
    }

    private val isStreaming = AtomicBoolean(false)
    private var streamingThread: Thread? = null

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    @Volatile
    var micGain: Float = 1.0f

    fun isStreaming(): Boolean = isStreaming.get()

    /**
     * Starts capturing audio from the collar mic and routing it to the Bluetooth receiver.
     */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(): Boolean {
        if (isStreaming.get()) {
            Log.w(TAG, "Streaming engine is already running")
            return true
        }

        try {
            val minRecordBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, AUDIO_ENCODING)
            val minTrackBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, AUDIO_ENCODING)

            if (minRecordBufferSize <= 0 || minTrackBufferSize <= 0) {
                listener.onError("Audio hardware configuration not supported by this device.")
                return false
            }

            // Allocate buffer sized comfortably above minimum to prevent underruns
            val recordBufferSize = maxOf(minRecordBufferSize, CHUNK_FRAME_COUNT * 4)
            val trackBufferSize = maxOf(minTrackBufferSize, CHUNK_FRAME_COUNT * 4)

            // 1. Initialize AudioRecord (Audio Input)
            val record = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AUDIO_ENCODING)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL_IN)
                        .build()
                )
                .setBufferSizeInBytes(recordBufferSize)
                .build()

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                listener.onError("Failed to initialize AudioRecord for microphone input.")
                return false
            }

            // 2. Route input: Prefer external 3.5mm wired collar mic
            val preferredInput = audioDeviceManager.getPreferredMicDevice()
            var inputDescription = "Built-in Microphone"
            if (preferredInput != null) {
                record.setPreferredDevice(preferredInput)
                inputDescription = when (preferredInput.type) {
                    AudioDeviceInfo.TYPE_WIRED_HEADSET -> "3.5mm External Collar Mic"
                    AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> "USB External Mic"
                    else -> "Built-in Microphone"
                }
            }

            // 3. Initialize AudioTrack (Audio Output for Bluetooth A2DP)
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA) // Routes through Android media output (A2DP)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setEncoding(AUDIO_ENCODING)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL_OUT)
                .build()

            val track = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(trackBufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            if (track.state != AudioTrack.STATE_INITIALIZED) {
                record.release()
                track.release()
                listener.onError("Failed to initialize AudioTrack for Bluetooth audio output.")
                return false
            }

            // 4. Route output: Prefer Bluetooth A2DP if available
            val btOutput = audioDeviceManager.getBluetoothA2dpOutputDevice()
            var outputDescription = "System Media Route"
            if (btOutput != null) {
                val routed = track.setPreferredDevice(btOutput)
                outputDescription = if (routed) "Bluetooth A2DP (${btOutput.productName})" else "Bluetooth A2DP Sink"
            } else {
                outputDescription = "Bluetooth / Default Media Output"
            }

            this.audioRecord = record
            this.audioTrack = track

            listener.onRouteInfoUpdated(inputDescription, outputDescription)

            // 5. Start audio pipelines
            record.startRecording()
            track.play()

            isStreaming.set(true)

            // 6. Launch dedicated high-priority audio pump thread
            streamingThread = Thread({
                runStreamingLoop(record, track)
            }, "WirelessCollarMicAudioThread").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }

            listener.onStreamingStarted()
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Exception starting audio engine", e)
            cleanup()
            listener.onError("Failed to start streaming: ${e.localizedMessage}")
            return false
        }
    }

    /**
     * Dedicated audio processing loop running at THREAD_PRIORITY_URGENT_AUDIO.
     * Continuously transfers PCM data with applied digital gain and updates live VU meter.
     */
    private fun runStreamingLoop(record: AudioRecord, track: AudioTrack) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        val buffer = ShortArray(CHUNK_FRAME_COUNT)
        var lastMeterTime = 0L

        try {
            while (isStreaming.get()) {
                val shortsRead = record.read(buffer, 0, buffer.size)
                if (shortsRead <= 0) {
                    if (shortsRead == AudioRecord.ERROR_INVALID_OPERATION || shortsRead == AudioRecord.ERROR_BAD_VALUE) {
                        Log.e(TAG, "AudioRecord read error: $shortsRead")
                        break
                    }
                    continue
                }

                // Apply mic gain and prevent digital clipping (hard clamp)
                val currentGain = micGain
                var sumSquares = 0.0

                for (i in 0 until shortsRead) {
                    var sample = buffer[i]
                    if (currentGain != 1.0f) {
                        val scaled = (sample * currentGain).toInt()
                        sample = scaled.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                        buffer[i] = sample
                    }
                    sumSquares += (sample * sample).toDouble()
                }

                // Write directly to AudioTrack output
                val shortsWritten = track.write(buffer, 0, shortsRead)
                if (shortsWritten < 0) {
                    Log.e(TAG, "AudioTrack write error: $shortsWritten")
                    break
                }

                // Calculate RMS level for UI VU meter ~20 times per second
                val now = System.currentTimeMillis()
                if (now - lastMeterTime >= 50) {
                    lastMeterTime = now
                    val meanSquare = sumSquares / shortsRead
                    val rms = sqrt(meanSquare).toFloat()
                    val normalized = (rms / Short.MAX_VALUE).coerceIn(0.0001f, 1.0f)
                    val db = (20 * log10(normalized.toDouble())).toFloat().coerceIn(-60f, 0f)
                    val percent = (((db + 60f) / 60f) * 100).toInt().coerceIn(0, 100)

                    listener.onAudioLevelChanged(db, percent)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in streaming audio loop", e)
            if (isStreaming.get()) {
                listener.onError("Streaming interrupted: ${e.message}")
            }
        } finally {
            cleanup()
            listener.onStreamingStopped()
        }
    }

    /**
     * Stops the audio streaming thread and safely releases resources.
     */
    @Synchronized
    fun stop() {
        if (!isStreaming.getAndSet(false)) {
            return
        }

        streamingThread?.interrupt()
        streamingThread = null
        cleanup()
        listener.onAudioLevelChanged(-60f, 0)
        listener.onStreamingStopped()
    }

    private fun cleanup() {
        try {
            audioRecord?.let {
                if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    it.stop()
                }
                it.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioRecord", e)
        } finally {
            audioRecord = null
        }

        try {
            audioTrack?.let {
                if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    it.stop()
                    it.flush()
                }
                it.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioTrack", e)
        } finally {
            audioTrack = null
        }
    }
}
