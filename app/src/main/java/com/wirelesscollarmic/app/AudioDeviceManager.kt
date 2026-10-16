package com.wirelesscollarmic.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * Monitors and detects audio input sources, specifically preferring external
 * 3.5mm wired microphones (or USB-C lapel mics) over the built-in device microphone.
 */
class AudioDeviceManager(
    private val context: Context,
    private val onDeviceStatusChanged: (isExternalMic: Boolean, deviceDescription: String) -> Unit
) {
    companion object {
        private const val TAG = "AudioDeviceManager"
    }

    val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var audioDeviceCallback: AudioDeviceCallback? = null
    private var headsetReceiver: BroadcastReceiver? = null

    fun initialize() {
        // Register AudioDeviceCallback (API 23+)
        audioDeviceCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                evaluateInputDevices()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                evaluateInputDevices()
            }
        }
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)

        // Also listen to ACTION_HEADSET_PLUG for maximum vendor compatibility
        headsetReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_HEADSET_PLUG) {
                    val state = intent.getIntExtra("state", -1)
                    val hasMicrophone = intent.getIntExtra("microphone", 0) == 1
                    Log.d(TAG, "Headset plug event: state=$state, hasMicrophone=$hasMicrophone")
                    evaluateInputDevices()
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_HEADSET_PLUG)
        context.registerReceiver(headsetReceiver, filter)

        // Initial evaluation
        evaluateInputDevices()
    }

    fun release() {
        audioDeviceCallback?.let {
            audioManager.unregisterAudioDeviceCallback(it)
            audioDeviceCallback = null
        }
        headsetReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering headset receiver", e)
            }
            headsetReceiver = null
        }
    }

    /**
     * Inspects available input devices and returns the best available microphone device.
     * Prefers TYPE_WIRED_HEADSET (3.5mm TRRS mic), followed by USB headset, followed by built-in mic.
     */
    fun getPreferredMicDevice(): AudioDeviceInfo? {
        val inputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)

        // 1. Look for 3.5mm wired headset mic
        val wiredMic = inputDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }
        if (wiredMic != null) return wiredMic

        // 2. Look for USB headset / lapel mic adapter
        val usbMic = inputDevices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_DEVICE
        }
        if (usbMic != null) return usbMic

        // 3. Fallback to built-in microphone
        return inputDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
    }

    /**
     * Checks if an external 3.5mm or USB microphone is currently attached.
     */
    fun isExternalMicAttached(): Boolean {
        val inputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        return inputDevices.any {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE
        }
    }

    /**
     * Finds the active Bluetooth A2DP audio output device if connected.
     */
    fun getBluetoothA2dpOutputDevice(): AudioDeviceInfo? {
        val outputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return outputDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
    }

    fun evaluateInputDevices() {
        val inputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val wiredDevice = inputDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }
        val usbDevice = inputDevices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_DEVICE
        }

        when {
            wiredDevice != null -> {
                onDeviceStatusChanged(true, "3.5mm External Collar Mic (Preferred)")
            }
            usbDevice != null -> {
                onDeviceStatusChanged(true, "USB-C External Collar Mic (Preferred)")
            }
            else -> {
                onDeviceStatusChanged(false, "Built-in Microphone (Collar Mic Not Detected)")
            }
        }
    }
}
