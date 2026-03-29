package com.adna.audiorecorderphone

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.util.UUID

class AudioForwardingService : Service(){

    private val TAG="AudioForwardingService"
    private val channelId="AudioForwardingServiceChannel"
    private val notificationId=1
    private var bluetoothConnectionController: BluetoothConnectionController?=null
    private var translationServerController: TranslationServerController?=null
    private val appUuid= UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66")
    private val serviceName="AndroidRecorderPhone"

    private val binder: Binder=LocalBinder(this)

    class LocalBinder(private val service: AudioForwardingService) : Binder(){
        fun getService(): AudioForwardingService{
            return service
        }
    }

    interface UiCallbacks{
        fun onTranscriptionReceived(message:String)
        fun updateStatus(message: String)
    }

    private var uiCallbacks: UiCallbacks?=null


    override fun onBind(intent: Intent?): IBinder? {
        return binder
    }

    fun setUiCallbacks(callbacks: UiCallbacks?){
        uiCallbacks=callbacks
    }

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

        val bluetoothManager=getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothConnectionController= BluetoothConnectionController(bluetoothManager.adapter,appUuid,serviceName,bluetoothCallbacks)
        translationServerController= TranslationServerController(translationServerCallbacks)
    }

    private val bluetoothCallbacks= object : BluetoothConnectionController.Callbacks{
        override fun onConnected() {
            uiCallbacks?.updateStatus("Bluetooth connected")
        }

        override fun onDisconnected() {
            translationServerController?.flush()
            uiCallbacks?.updateStatus("Bluetooth Disconnected")
        }

        override fun onAudioPayloadReceived(payload: ByteArray, size: Int) {
            translationServerController?.sendAudioChunk(payload,size)

        }

        override fun onTextReceived(text: String) {
            uiCallbacks?.updateStatus("Received text from watch: $text")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ip=intent?.getStringExtra("EXTRA_IP")
        val port=intent?.getIntExtra("EXTRA_PORT",0)?:0

        if(ip!=null &&port>0){
            translationServerController?.connect(ip,port)
        }

        startForegroundServiceNotification()
        bluetoothConnectionController?.start()
        uiCallbacks?.updateStatus("Server started in background")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        bluetoothConnectionController?.stop()
        translationServerController?.release()
        super.onDestroy()
    }

    private val translationServerCallbacks=object : TranslationServerController.Callbacks{
        override fun onStatusUpdated() {
            uiCallbacks?.updateStatus("")
        }

        override fun onNewTranscriptReceived(text: String) {
            uiCallbacks?.onTranscriptionReceived(text)
            bluetoothConnectionController?.sendTextChunk(text)
        }
    }


    private fun startForegroundServiceNotification() {
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Audio Forwarding Active")
            .setContentText("Now open app on watch to speak")
            .setSmallIcon(R.mipmap.ic_launcher) // The little icon in the status bar
            .setOngoing(true) // Makes it so the user can't swipe it away
            .build()

        // Android 10+ (API 29) requires us to declare exactly what "type" of foreground service this is
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(notificationId, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val serviceChannel = NotificationChannel(
            channelId,
            "Audio Forwarding Service",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(android.content.Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(serviceChannel)
    }
}