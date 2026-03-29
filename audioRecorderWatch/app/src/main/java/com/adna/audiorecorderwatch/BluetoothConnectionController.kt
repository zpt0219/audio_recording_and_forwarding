package com.adna.audiorecorderwatch

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import androidx.annotation.RequiresPermission
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.UUID

class BluetoothConnectionController (
    private val bluetoothAdapter: BluetoothAdapter,
    private val appUuid: UUID,
    private val callbacks:Callbacks){
    interface Callbacks {
        fun onConnected()
        fun onDisconnected()
        fun onTextReceived(text:String)
    }

    private companion object {
        const val TAG="BluetoothConnectionController"
        const val MSG_AUDIO=1
        const val MSG_TEXT=2

        const val MAX_PAYLOAD_BYTES=32*1024
    }

    private var clientThread: ClientThread? = null
    private var connectedThread: ConnectedThread? = null

    @Volatile
    private var bluetoothSocket: BluetoothSocket? = null

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN])
    fun start(device: BluetoothDevice?) {
        if (device == null) {
            return
        }

        // If we are already running a client thread, cancel the second click.
        if (clientThread != null) {
            Log.e(TAG, "Client connection is already in progress")
            return
        }

        clientThread = ClientThread(device).also { it.start() }
        callbacks.onConnected()
    }

    fun stop() {
        connectedThread?.cancel()
        clientThread?.cancel()
    }

    fun sendAudioChunk(audioBytes: ByteArray, size: Int): Boolean {
        return connectedThread?.writeAudioChunk(audioBytes, size) == true
    }

    @SuppressLint("MissingPermission")
    private fun handleConnectedSocket(socket: BluetoothSocket, message: String) {
        // Cancel the old stream if there happened to be one.
        connectedThread?.cancel()

        // This is now the "real" active socket.
        bluetoothSocket = socket

        // Boot up the two-way talking/listening thread!
        connectedThread = ConnectedThread(socket).also { it.start() }

        // Because the socket is already established, we don't need the server or client anymore.
        clientThread = null

        // Notify the MainActivity that we successfully connected.
        callbacks.onConnected()
    }

    private fun clearConnectionState() {
        if(bluetoothSocket==null){
            return
        }

        bluetoothSocket = null
        connectedThread = null
        clientThread = null

        callbacks.onDisconnected()
    }

    inner class ClientThread(private val device: BluetoothDevice) : Thread() {
        private val bluetoothSocket: BluetoothSocket? by lazy(LazyThreadSafetyMode.NONE) {
            device.createRfcommSocketToServiceRecord(appUuid)
        }

        @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN])
        override fun run() {
            bluetoothAdapter.cancelDiscovery()
            Log.d(TAG, "Client thread started")
            bluetoothSocket?.let { socket ->
                try {
                    socket.connect()
                    handleConnectedSocket(socket, "Connected to ${device.name ?: device.address}")

                } catch (e: IOException) {
                    Log.e(TAG, "Client connect failed", e)
                    // Wipe the state
                    clearConnectionState()
                    try {
                        socket.close()
                    } catch (closeException: IOException) {
                        Log.e(TAG, "Unable to close failed client socket", closeException)
                    }
                } finally {
                    clientThread = null
                }
            }
        }

        fun cancel() {
            try {
                bluetoothSocket?.close()
            } catch (e: IOException) {
                Log.e(TAG, "Unable to close client socket", e)
            } finally {
                clientThread = null
            }
        }
    }

    inner class ConnectedThread(private val bluetoothSocket: BluetoothSocket) : Thread() {
        private val inStream = DataInputStream(bluetoothSocket.inputStream)
        private val outStream = DataOutputStream(bluetoothSocket.outputStream)

        override fun run() {
             while (true) {
                try {
                    val messageType = inStream.readUnsignedByte()
                    val payloadSize = inStream.readInt()

                    if (payloadSize <= 0 || payloadSize > MAX_PAYLOAD_BYTES) {
                        throw IOException("Invalid payload size: $payloadSize")
                    }
                    val payload = ByteArray(payloadSize)
                    inStream.readFully(payload)

                    when (messageType) {
                        MSG_TEXT -> {
                            val textString = String(payload, Charsets.UTF_8)
                            callbacks.onTextReceived(textString)
                        }
                        else -> Log.w(TAG, "Unsupported message type: $messageType")
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Input stream disconnected", e)
                    clearConnectionState()
                    break
                }
            }
        }

        @Synchronized
        fun writeAudioChunk(audioBytes: ByteArray, size: Int): Boolean {
            if (!bluetoothSocket.isConnected) {
                cancel()
                return false
            }

            try {
                outStream.writeByte(MSG_AUDIO)
                outStream.writeInt(size)
                outStream.write(audioBytes, 0, size)
                outStream.flush()
                return true
            } catch (e: IOException) {
                Log.e(TAG, "Error occurred while sending audio", e)
                cancel()
                return false
            }
        }

        // Close the active socket
        fun cancel() {
            try {
                bluetoothSocket.close()
            } catch (e: IOException) {
                Log.e(TAG, "Unable to close connected socket", e)
            } finally {
                clearConnectionState()
            }
        }
    }
}