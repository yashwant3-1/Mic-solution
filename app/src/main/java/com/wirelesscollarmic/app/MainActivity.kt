package com.wirelesscollarmic.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.wirelesscollarmic.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity(),
    BluetoothReceiverManager.Listener,
    AudioStreamingEngine.Listener {

    private lateinit var binding: ActivityMainBinding

    private lateinit var bluetoothReceiverManager: BluetoothReceiverManager
    private lateinit var audioDeviceManager: AudioDeviceManager
    private lateinit var audioStreamingEngine: AudioStreamingEngine

    private var pairedDevicesList: List<BluetoothDevice> = emptyList()
    private var selectedDevice: BluetoothDevice? = null
    private var isConnectedToReceiver: Boolean = false

    // Runtime permissions launcher
    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordAudioGranted = permissions[Manifest.permission.RECORD_AUDIO] == true
        val bluetoothConnectGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions[Manifest.permission.BLUETOOTH_CONNECT] == true
        } else {
            true
        }

        if (recordAudioGranted && bluetoothConnectGranted) {
            setupManagers()
        } else {
            showPermissionRationaleDialog()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        checkAndRequestPermissions()
    }

    private fun setupUI() {
        // Start / Stop MIC Button
        binding.btnToggleMic.setOnClickListener {
            if (audioStreamingEngine.isStreaming()) {
                stopStreaming()
            } else {
                startStreaming()
            }
        }

        // Connect Bluetooth Receiver button
        binding.btnConnectBt.setOnClickListener {
            if (selectedDevice == null) {
                Toast.makeText(this, "No paired receiver selected. Please pair one in Bluetooth Settings.", Toast.LENGTH_SHORT).show()
                openBluetoothSettings()
            } else {
                bluetoothReceiverManager.checkCurrentConnectionState(selectedDevice)
                if (!isConnectedToReceiver) {
                    Toast.makeText(
                        this,
                        "Connecting to A2DP receiver… Opening Bluetooth settings if direct connection is pending.",
                        Toast.LENGTH_SHORT
                    ).show()
                    openBluetoothSettings()
                } else {
                    Toast.makeText(this, "Receiver is already connected and ready for audio!", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Open Bluetooth Settings button
        binding.btnBtSettings.setOnClickListener {
            openBluetoothSettings()
        }

        // Mic Gain Slider
        binding.seekMicGain.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // Progress: 0 to 30 -> Gain: 0.0x to 3.0x (default 10 -> 1.0x)
                val gain = progress / 10.0f
                binding.tvGainValue.text = String.format("%.1fx", gain)
                if (::audioStreamingEngine.isInitialized) {
                    audioStreamingEngine.micGain = gain
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Spinner selection listener
        binding.spinnerPairedDevices.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            @SuppressLint("MissingPermission")
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position in pairedDevicesList.indices) {
                    selectedDevice = pairedDevicesList[position]
                    bluetoothReceiverManager.checkCurrentConnectionState(selectedDevice)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                selectedDevice = null
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            setupManagers()
        }
    }

    private fun showPermissionRationaleDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.permission_title)
            .setMessage(R.string.permission_description)
            .setPositiveButton(R.string.permission_grant_btn) { _, _ ->
                checkAndRequestPermissions()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupManagers() {
        // Initialize Bluetooth manager
        bluetoothReceiverManager = BluetoothReceiverManager(this, this)
        bluetoothReceiverManager.initialize()

        // Initialize Audio Device Manager for 3.5mm mic detection
        audioDeviceManager = AudioDeviceManager(this) { isExternalMic, deviceDescription ->
            runOnUiThread {
                updateMicrophoneStatus(isExternalMic, deviceDescription)
            }
        }
        audioDeviceManager.initialize()

        // Initialize Audio Streaming Engine
        audioStreamingEngine = AudioStreamingEngine(audioDeviceManager, this)
        val initialGain = binding.seekMicGain.progress / 10.0f
        audioStreamingEngine.micGain = initialGain
    }

    private fun startStreaming() {
        if (!hasRequiredPermissions()) {
            checkAndRequestPermissions()
            return
        }

        if (!isConnectedToReceiver) {
            AlertDialog.Builder(this)
                .setTitle("Bluetooth Receiver Not Connected")
                .setMessage("No active Bluetooth A2DP audio receiver was detected. If you start now, audio may output from the phone's built-in speaker or wait for receiver connection. Would you like to proceed anyway?")
                .setPositiveButton("Start Anyway") { _, _ ->
                    executeStartStreaming()
                }
                .setNegativeButton("Connect Receiver First") { _, _ ->
                    openBluetoothSettings()
                }
                .show()
            return
        }

        executeStartStreaming()
    }

    private fun executeStartStreaming() {
        val started = audioStreamingEngine.start()
        if (started) {
            WirelessMicService.startService(this)
        }
    }

    private fun stopStreaming() {
        audioStreamingEngine.stop()
        WirelessMicService.stopService(this)
    }

    private fun hasRequiredPermissions(): Boolean {
        val micOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val btOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        return micOk && btOk
    }

    private fun openBluetoothSettings() {
        try {
            val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open Bluetooth settings", Toast.LENGTH_SHORT).show()
        }
    }

    // --- BluetoothReceiverManager.Listener Callbacks ---

    @SuppressLint("MissingPermission")
    override fun onBondedDevicesRefreshed(devices: List<BluetoothDevice>) {
        runOnUiThread {
            pairedDevicesList = devices
            if (devices.isEmpty()) {
                val emptyAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("No paired audio receivers"))
                binding.spinnerPairedDevices.adapter = emptyAdapter
                selectedDevice = null
                updateBluetoothStatus(false, getString(R.string.no_paired_devices))
            } else {
                val names = devices.map { device ->
                    val name = device.name ?: "Unknown Device"
                    val address = device.address
                    "$name ($address)"
                }
                val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
                binding.spinnerPairedDevices.adapter = adapter

                // Select first connected or first in list
                val connectedDevice = devices.firstOrNull { bluetoothReceiverManager.isDeviceConnected(it) }
                val defaultIndex = if (connectedDevice != null) devices.indexOf(connectedDevice) else 0

                binding.spinnerPairedDevices.setSelection(defaultIndex)
                selectedDevice = devices[defaultIndex]
                bluetoothReceiverManager.checkCurrentConnectionState(selectedDevice)
            }
        }
    }

    override fun onA2dpConnectionStateChanged(device: BluetoothDevice?, isConnected: Boolean, statusText: String) {
        runOnUiThread {
            this.isConnectedToReceiver = isConnected
            updateBluetoothStatus(isConnected, statusText)
        }
    }

    override fun onBluetoothStateChanged(isEnabled: Boolean) {
        runOnUiThread {
            if (!isEnabled) {
                updateBluetoothStatus(false, getString(R.string.status_bt_unavailable))
                if (audioStreamingEngine.isStreaming()) {
                    stopStreaming()
                }
            } else {
                bluetoothReceiverManager.refreshPairedDevices()
            }
        }
    }

    // --- AudioDeviceManager UI Updates ---

    private fun updateMicrophoneStatus(isExternal: Boolean, description: String) {
        binding.tvMicStatus.text = description
        if (isExternal) {
            binding.containerMicStatus.setBackgroundResource(R.drawable.badge_connected)
            binding.viewMicStatusDot.setBackgroundColor(ContextCompat.getColor(this, R.color.status_green))
        } else {
            binding.containerMicStatus.setBackgroundResource(R.drawable.badge_warning)
            binding.viewMicStatusDot.setBackgroundColor(ContextCompat.getColor(this, R.color.status_amber))
        }
    }

    private fun updateBluetoothStatus(isConnected: Boolean, statusText: String) {
        binding.tvBtStatus.text = statusText
        if (isConnected) {
            binding.containerBtStatus.setBackgroundResource(R.drawable.badge_connected)
            binding.viewBtStatusDot.setBackgroundColor(ContextCompat.getColor(this, R.color.status_green))
            binding.btnConnectBt.text = "Receiver Ready"
            binding.btnConnectBt.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_green))
        } else {
            binding.containerBtStatus.setBackgroundResource(R.drawable.badge_disconnected)
            binding.viewBtStatusDot.setBackgroundColor(ContextCompat.getColor(this, R.color.status_red))
            binding.btnConnectBt.text = getString(R.string.btn_connect_bt)
            binding.btnConnectBt.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primary_sky))
        }
    }

    // --- AudioStreamingEngine.Listener Callbacks ---

    override fun onStreamingStarted() {
        runOnUiThread {
            binding.btnToggleMic.text = getString(R.string.btn_stop_mic)
            binding.btnToggleMic.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_red))
            binding.tvStreamBadge.text = "STREAMING LIVE"
            binding.tvStreamBadge.setTextColor(ContextCompat.getColor(this, R.color.status_green))
            binding.tvStreamingStatus.text = getString(R.string.status_streaming)
        }
    }

    override fun onStreamingStopped() {
        runOnUiThread {
            binding.btnToggleMic.text = getString(R.string.btn_start_mic)
            binding.btnToggleMic.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primary_sky))
            binding.tvStreamBadge.text = "STANDBY"
            binding.tvStreamBadge.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
            binding.tvStreamingStatus.text = getString(R.string.status_idle)
            binding.progressAudioLevel.progress = 0
            binding.tvAudioLevel.text = "0 dB"
        }
    }

    override fun onAudioLevelChanged(rmsDb: Float, levelPercent: Int) {
        runOnUiThread {
            binding.progressAudioLevel.progress = levelPercent
            binding.tvAudioLevel.text = String.format("%.0f dB", rmsDb)
        }
    }

    override fun onError(errorMessage: String) {
        runOnUiThread {
            Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show()
            binding.tvStreamingStatus.text = "Error: $errorMessage"
        }
    }

    override fun onRouteInfoUpdated(inputInfo: String, outputInfo: String) {
        runOnUiThread {
            binding.tvDiagnostics.text = "Audio format: 48,000 Hz, 16-bit PCM Mono\nInput Route: $inputInfo\nOutput Route: $outputInfo\nInternal Latency Target: ~10.6ms hardware block"
        }
    }

    override fun onResume() {
        super.onResume()
        if (::bluetoothReceiverManager.isInitialized) {
            bluetoothReceiverManager.refreshPairedDevices()
        }
        if (::audioDeviceManager.isInitialized) {
            audioDeviceManager.evaluateInputDevices()
        }
    }

    override fun onDestroy() {
        if (::audioStreamingEngine.isInitialized) {
            audioStreamingEngine.stop()
        }
        if (::bluetoothReceiverManager.isInitialized) {
            bluetoothReceiverManager.release()
        }
        if (::audioDeviceManager.isInitialized) {
            audioDeviceManager.release()
        }
        super.onDestroy()
    }
}
