package com.adna.audiorecorderwatch

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.UUID

class MainActivity : AppCompatActivity() {

    companion object{
        const val TAG="AudioRecorderWatch"
        const val PERMISSION_REQUEST_CODE=1

    }
    private val appUuid = UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66")

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var mainTextView: TextView
    private lateinit var connectButton: Button
    private lateinit var recordingButton: Button

    private lateinit var bluetoothConnectionController: BluetoothConnectionController
    private lateinit var audioRecordingController: AudioRecordingController

    private var bondedPhone: BluetoothDevice?=null

    private val requiredPermissions:Array<String>
        get() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+ requires specific granular permissions to connect and scan.
                return arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.RECORD_AUDIO
                )
            }
            return arrayOf(Manifest.permission.RECORD_AUDIO)
        }



    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        mainTextView=findViewById(R.id.main_text_view)
        connectButton=findViewById(R.id.connect_button)
        recordingButton=findViewById(R.id.recording_button)

        val bluetoothManager = applicationContext.getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter


        bluetoothConnectionController = BluetoothConnectionController(
            bluetoothAdapter,
            appUuid,
            callbacks = object : BluetoothConnectionController.Callbacks{
                override fun onConnected() {
                    runOnUiThread { recordingButton.isEnabled=true }
                }

                override fun onDisconnected() {
                    runOnUiThread { recordingButton.isEnabled=false }
                }

                override fun onTextReceived(text: String) {
                    runOnUiThread { mainTextView.text="${mainTextView.text}$text" }
                }
            }
        )

        audioRecordingController = AudioRecordingController(
            callbacks = object : AudioRecordingController.Callbacks{
                override fun sendAudioChunk(byteArray: ByteArray, size: Int): Boolean {
                    return bluetoothConnectionController.sendAudioChunk(byteArray,size)
                }

                override fun onStreamingStarted() {
                    runOnUiThread {
                        mainTextView.text="..."
                        recordingButton.text="Stop Recording"
                    }
                }

                override fun onStreamingFinished() {
                    runOnUiThread { recordingButton.text="Start Recording" }
                }
            }
        )

        connectButton.setOnClickListener {
            prepareForRecording()
        }

        recordingButton.setOnClickListener {
            if (audioRecordingController.isStreamingAudio) {
                audioRecordingController.stop()
            } else {
                audioRecordingController.start()
            }
        }


        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        requestMissingPermissions()
    }

    override fun onDestroy() {
        super.onDestroy()
        audioRecordingController.stop()
        bluetoothConnectionController.stop()
    }

    @SuppressLint("MissingPermission")
    private fun prepareForRecording(){
        if (bondedPhone == null) {
            initPhoneConnection()
            if(bondedPhone==null){
                return
            }
        }

        bluetoothConnectionController.start(bondedPhone)
    }

    private fun requestMissingPermissions() {
        val missingPermissions=buildList {
            requiredPermissions.forEach { permission->
                if (ActivityCompat.checkSelfPermission(applicationContext, permission) != PackageManager.PERMISSION_GRANTED) {
                    add(permission) // Build our list
                }
            }
        }

        if(missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this,missingPermissions.toTypedArray(),PERMISSION_REQUEST_CODE)
        }
    }


    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                initPhoneConnection()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun initPhoneConnection() {
        val bondedDevices = bluetoothAdapter.bondedDevices.toList().filter{
            Log.e("TAG", "scanBluetoothConnection: ${it.name}")
            it.bluetoothClass.deviceClass== BluetoothClass.Device.PHONE_SMART
        }


        if (bondedDevices.isEmpty()) {
            bondedPhone = null
            return
        }

        //Auto-select if only one device is paired!
        if (bondedDevices.size == 1) {
            bondedPhone=bondedDevices.first()
            return
        }

        showDeviceSelectionDialog(bondedDevices)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun showDeviceSelectionDialog(devices: List<BluetoothDevice>) {
        val labels = devices.map { device -> "${device.name ?: "Unknown device"}\n${device.address}" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Choose paired device")
            .setItems(labels) { _, which ->
                bondedPhone=devices[which]
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

}