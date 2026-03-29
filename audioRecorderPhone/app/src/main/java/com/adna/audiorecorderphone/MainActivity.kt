package com.adna.audiorecorderphone

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private companion object{
        const val TAG="MainActivity"
        const val PERMISSION_REQUEST_CODE=1
    }
    lateinit var transcriptionTextView: TextView
    lateinit var statusTextView: TextView
    lateinit var ipAddressEditText: EditText
    lateinit var ipPortEditText: EditText
    lateinit var startServerButton: Button

    lateinit var mainViewModel: MainViewModel

    private var watchDevice: BluetoothDevice?=null

    lateinit var bluetoothAdapter: BluetoothAdapter
    private var audioForwardingService: AudioForwardingService?=null

    private val uiCallbacks=object:AudioForwardingService.UiCallbacks{
        override fun onTranscriptionReceived(message: String) {
            mainViewModel.appendTranscriptionText(message)
        }

        override fun updateStatus(message: String) {
            mainViewModel.updateStatus(message)
        }
    }

    private val serviceConnection = object : ServiceConnection{
        override fun onServiceConnected(
            name: ComponentName?,
            service: IBinder?
        ) {
            audioForwardingService=(service as AudioForwardingService.LocalBinder).getService()
            audioForwardingService?.setUiCallbacks(uiCallbacks)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            audioForwardingService?.setUiCallbacks(null)
            audioForwardingService=null
        }
    }

    private val requiredPermissions: Array<String> get() {
        return buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mainViewModel= ViewModelProvider(this).get(MainViewModel::class.java)
        val bluetoothManager=getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter=bluetoothManager.adapter

        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        transcriptionTextView=findViewById(R.id.transcription_text_view)
        statusTextView=findViewById(R.id.status_text_view)
        ipAddressEditText=findViewById(R.id.ip_address_edit_text)
        ipPortEditText=findViewById(R.id.ip_port_edit_text)
        startServerButton=findViewById(R.id.start_server_button)

        setupObservers()
        startServerButton.setOnClickListener { onServerButtonClicked() }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    override fun onStart() {
        super.onStart()
        val intent= Intent(this, AudioForwardingService::class.java)
        bindService(intent,serviceConnection,BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        if(!hasAllPermissions()){
            requestMissingPermissions()
        }
        updateButtons()
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        if(audioForwardingService!=null){
            unbindService(serviceConnection)
        }
    }

    private fun onServerButtonClicked(){
        if (!ensurePermissionsReady()) {
            return
        }

        val host = ipAddressEditText.text?.toString()
        val port = ipPortEditText.text?.toString()?.toInt()?:0

        val intent = Intent(this, AudioForwardingService::class.java).apply {
            putExtra("EXTRA_IP", host)
            putExtra("EXTRA_PORT", port)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        mainViewModel.updateStatus("Starting Background Server...")
        updateButtons()
    }

    private fun setupObservers(){
        mainViewModel.statusText.observe(this){
            text-> statusTextView.text=text
        }
        mainViewModel.transcriptionText.observe(this){
            text->transcriptionTextView.text=text
        }
        mainViewModel.isButtonEnabled.observe(this){
            isEnabled->startServerButton.isEnabled=isEnabled
        }
    }

    @SuppressLint("MissingPermission")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                updateButtons()
                if (hasAllPermissions() && bluetoothAdapter.isEnabled) {
                    scanBluetoothConnection()
                }
            } else {
                updateButtons()
                mainViewModel.updateStatus("Required permissions ${permissions.first()}.")
            }
        }
    }

    fun updateButtons(){
        mainViewModel.setButtonEnabled(hasAllPermissions())
    }

    private fun hasAllPermissions(): Boolean {
        return requiredPermissions.all { permission ->
            ActivityCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestMissingPermissions() {
        val missingPermissions = buildList {
            requiredPermissions.forEach { permission ->
                if (ActivityCompat.checkSelfPermission(applicationContext, permission) != PackageManager.PERMISSION_GRANTED) {
                    add(permission)
                }
            }
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    private fun ensurePermissionsReady(): Boolean {
        if (!hasAllPermissions()) {
            requestMissingPermissions()
            return false
        }

        if (!bluetoothAdapter.isEnabled) {
            mainViewModel.updateStatus("Turn Bluetooth on first")
            return false
        }

        return true
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN])
    private fun scanBluetoothConnection() {
        val bondedDevices = bluetoothAdapter.bondedDevices
            ?.sortedBy { it.name?.lowercase(Locale.getDefault()) ?: it.address }
            .orEmpty()

        if (bondedDevices.isEmpty()) {
            watchDevice = null
            mainViewModel.updateStatus("No paired device found.")
            updateButtons()
            return
        }

        if (bondedDevices.size == 1) {
            selectTargetDevice(bondedDevices.first())
            return
        }

        showDeviceSelectionDialog(bondedDevices)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun showDeviceSelectionDialog(devices: List<BluetoothDevice>) {
        val labels = devices.map { device ->
            "${device.name ?: "Unknown device"}\n${device.address}"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Choose paired phone")
            .setItems(labels) { _, which ->
                selectTargetDevice(devices[which])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun selectTargetDevice(device: BluetoothDevice) {
        watchDevice = device
        updateButtons()
    }


}