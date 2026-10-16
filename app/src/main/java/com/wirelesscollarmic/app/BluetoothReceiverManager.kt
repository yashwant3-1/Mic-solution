package com.wirelesscollarmic.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log

/**
 * Manages detection of paired Bluetooth audio devices and monitors
 * active connection states using the official Android A2DP profile proxy.
 *
 * NOTE: Bluetooth audio sinks (speakers, receivers, headphones) operate via A2DP profile.
 * They cannot and should not be connected using generic RFCOMM BluetoothSockets.
 */
class BluetoothReceiverManager(
    private val context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onBondedDevicesRefreshed(devices: List<BluetoothDevice>)
        fun onA2dpConnectionStateChanged(device: BluetoothDevice?, isConnected: Boolean, statusText: String)
        fun onBluetoothStateChanged(isEnabled: Boolean)
    }

    companion object {
        private const val TAG = "BtReceiverManager"
    }

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private var a2dpProfileProxy: BluetoothProfile? = null
    private var isReceiverRegistered = false

    private val profileServiceListener = object : BluetoothProfile.ServiceListener {
        @SuppressLint("MissingPermission")
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.A2DP) {
                Log.d(TAG, "A2DP Profile Proxy connected")
                a2dpProfileProxy = proxy
                checkCurrentConnectionState()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.A2DP) {
                Log.d(TAG, "A2DP Profile Proxy disconnected")
                a2dpProfileProxy = null
            }
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    val isEnabled = (state == BluetoothAdapter.STATE_ON)
                    listener.onBluetoothStateChanged(isEnabled)
                    if (isEnabled) {
                        refreshPairedDevices()
                    }
                }

                // A2DP connection state changes
                "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED",
                BluetoothDevice.ACTION_ACL_CONNECTED,
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    checkCurrentConnectionState(device)
                }
            }
        }
    }

    fun initialize() {
        bluetoothAdapter?.getProfileProxy(context, profileServiceListener, BluetoothProfile.A2DP)

        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED")
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        context.registerReceiver(bluetoothReceiver, filter)
        isReceiverRegistered = true

        refreshPairedDevices()
    }

    fun release() {
        if (isReceiverRegistered) {
            try {
                context.unregisterReceiver(bluetoothReceiver)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering receiver", e)
            }
            isReceiverRegistered = false
        }

        a2dpProfileProxy?.let { proxy ->
            bluetoothAdapter?.closeProfileProxy(BluetoothProfile.A2DP, proxy)
            a2dpProfileProxy = null
        }
    }

    /**
     * Retrieves all bonded Bluetooth devices that are audio sinks (speakers, receivers, headsets).
     */
    @SuppressLint("MissingPermission")
    fun getPairedAudioReceivers(): List<BluetoothDevice> {
        val adapter = bluetoothAdapter ?: return emptyList()
        if (!adapter.isEnabled) return emptyList()

        val bonded = adapter.bondedDevices ?: return emptyList()
        return bonded.filter { device ->
            isAudioDevice(device)
        }
    }

    /**
     * Checks if a BluetoothDevice is an audio receiver/sink based on its BluetoothClass.
     */
    @SuppressLint("MissingPermission")
    fun isAudioDevice(device: BluetoothDevice): Boolean {
        val btClass = device.bluetoothClass ?: return true // If unspecified, permit inclusion
        val major = btClass.majorDeviceClass
        if (major == BluetoothClass.Device.Major.AUDIO_VIDEO) {
            return true
        }
        // Also allow wearable headsets or peripheral audio
        val deviceClass = btClass.deviceClass
        return deviceClass == BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET ||
                deviceClass == BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES ||
                deviceClass == BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER ||
                deviceClass == BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO
    }

    fun refreshPairedDevices() {
        val devices = getPairedAudioReceivers()
        listener.onBondedDevicesRefreshed(devices)
        checkCurrentConnectionState()
    }

    /**
     * Checks whether a specific device or any paired A2DP audio device is currently connected.
     */
    @SuppressLint("MissingPermission")
    fun isDeviceConnected(device: BluetoothDevice?): Boolean {
        val proxy = a2dpProfileProxy ?: return false
        if (device == null) {
            return proxy.connectedDevices.isNotEmpty()
        }
        val state = proxy.getConnectionState(device)
        return state == BluetoothProfile.STATE_CONNECTED
    }

    @SuppressLint("MissingPermission")
    fun checkCurrentConnectionState(targetDevice: BluetoothDevice? = null) {
        val proxy = a2dpProfileProxy
        if (proxy == null) {
            listener.onA2dpConnectionStateChanged(targetDevice, false, "A2DP Service Initializing…")
            return
        }

        val connectedList = proxy.connectedDevices
        if (targetDevice != null) {
            val state = proxy.getConnectionState(targetDevice)
            val isConnected = (state == BluetoothProfile.STATE_CONNECTED)
            val desc = when (state) {
                BluetoothProfile.STATE_CONNECTED -> "Connected (A2DP Audio Output)"
                BluetoothProfile.STATE_CONNECTING -> "Connecting…"
                BluetoothProfile.STATE_DISCONNECTING -> "Disconnecting…"
                else -> "Disconnected"
            }
            listener.onA2dpConnectionStateChanged(targetDevice, isConnected, desc)
        } else {
            if (connectedList.isNotEmpty()) {
                val active = connectedList.first()
                listener.onA2dpConnectionStateChanged(active, true, "Connected: ${active.name}")
            } else {
                listener.onA2dpConnectionStateChanged(null, false, "Disconnected")
            }
        }
    }
}
