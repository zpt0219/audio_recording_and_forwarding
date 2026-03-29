package com.adna.audiorecorderphone

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.UUID


class BluetoothConnectionController (
    private val bluetoothAdapter: BluetoothAdapter,
    private val appUuid: UUID,
    private val serviceName:String,
    private val uiCallbacks:Callbacks
){
    interface Callbacks{
        fun onConnected()
        fun onDisconnected()
        fun onAudioPayloadReceived(payload: ByteArray,size:Int)
        fun onTextReceived(text:String)
    }

    private companion object {
        const val TAG="BluetoothConnectionController"
        const val MAX_PAYLOAD_BYTES=32*1024
        const val MESSAGE_TYPE_AUDIO=1
        const val MESSAGE_TYPE_TEXT=2

        const val MESSAGE_TYPE_END_RECORDING=3
    }

    private var serverThread:ServerThread?=null
    private var connectedThread: ConnectedThread?=null

    @SuppressLint("MissingPermission")
    inner class ServerThread : Thread(){
        private val bluetoothServerSocket: BluetoothServerSocket by lazy (LazyThreadSafetyMode.NONE){
            bluetoothAdapter.listenUsingRfcommWithServiceRecord(serviceName,appUuid)
        }

        override fun run(){
            val socket: BluetoothSocket? =try{
                bluetoothServerSocket.accept()
            }catch (e: IOException){
                Log.e(TAG, "Bluetooth Server Socker IO Error", e)
                null
            }

            if(socket==null){
                return
            }

            createConnectedThread(socket)
            try{
                bluetoothServerSocket.close()
            }catch (e: IOException){
                Log.e(TAG, "Close socket error", e)
            }

            serverThread=null
        }

        fun cancel(){
            try{
                bluetoothServerSocket.close()
            }catch (e: IOException){
                Log.e(TAG, "Close socket error", e)
            }
            serverThread=null
            uiCallbacks.onDisconnected()
        }
    }

    inner class ConnectedThread(private val bluetoothSocket: BluetoothSocket) : Thread(){
        private val inStream= DataInputStream(bluetoothSocket.inputStream)
        private val outStream= DataOutputStream(bluetoothSocket.outputStream)

        override fun run(){
            while(true){
                try {
                    val messageType=inStream.readUnsignedByte()
                    val payloadSize=inStream.readInt()

                    if(payloadSize<=0|| payloadSize>MAX_PAYLOAD_BYTES){
                        Log.e(TAG, "run: Invalid payload size $payloadSize")
                        break
                    }

                    val payload= ByteArray(payloadSize)
                    inStream.readFully(payload)

                    when(messageType){
                        MESSAGE_TYPE_AUDIO->uiCallbacks.onAudioPayloadReceived(payload,payloadSize)
                        MESSAGE_TYPE_TEXT->uiCallbacks.onTextReceived(payload.toString(charset = Charsets.UTF_8))
                        else-> Log.e(TAG, "ConnectedThread: unexpected message type: $messageType")
                    }
                }catch (e: IOException){
                    Log.e(TAG, "ConnectedThread: input stream disconnected ", e)
                    uiCallbacks.onDisconnected()
                    clearConnection()
                    break
                }
            }
        }
        fun cancel() {
            try {
                bluetoothSocket.close()
            } catch (e: IOException) {
                Log.e(TAG, "Unable to close connected socket", e)
            } finally {
                clearConnection()
            }
        }

        @Synchronized
        fun writeTextChunk(text:String): Boolean{
            if(!bluetoothSocket.isConnected){
                cancel()
                return false
            }

            try{
                val bytes=text.toByteArray(Charsets.UTF_8)
                outStream.writeByte(MESSAGE_TYPE_TEXT)
                outStream.writeInt(bytes.size)
                outStream.write(bytes,0,bytes.size)
                outStream.flush()
                return true
            }catch (e: IOException){
                Log.e(TAG, "writeAudioChunk ERROR", e)
                cancel()
                return false
            }
        }
    }

    private var bluetoothSocket: BluetoothSocket?=null

    fun start(){
        if(serverThread!=null){
            Log.i(TAG, "Server already running")
            return
        }

        serverThread = ServerThread()
        serverThread?.start()
    }

    fun stop(){
        connectedThread?.cancel()
        serverThread?.cancel()
    }

    fun sendTextChunk(text:String): Boolean{
        return connectedThread?.writeTextChunk(text)==true
    }

    private fun createConnectedThread(socket: BluetoothSocket){
        connectedThread?.cancel()
        bluetoothSocket=socket
        connectedThread= ConnectedThread(socket)
        connectedThread?.start()

        serverThread?.cancel()
        serverThread=null

        uiCallbacks.onConnected()
    }

    private fun clearConnection(){
        bluetoothSocket=null
        connectedThread=null
        serverThread=null

        uiCallbacks.onDisconnected()
    }


}