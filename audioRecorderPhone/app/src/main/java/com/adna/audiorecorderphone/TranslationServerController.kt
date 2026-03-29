package com.adna.audiorecorderphone

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class TranslationServerController (
    private val uiCallbacks:Callbacks
){
    interface Callbacks {
        fun onStatusUpdated()
        fun onNewTranscriptReceived(text:String)
    }

    private companion object{
        const val CLIENT_AUDIO_CHUNK=0x01
        const val CLIENT_FLUSH=0x02
        const val CLIENT_CLOSE=0x03
        const val SERVER_TRANSCRIPT=0x11
        const val SERVER_ERROR=0x12
        const val CONNECT_TIMEOUT_MS=3000
        const val MAX_SERVER_PAYLOAD_BYTES=256*1024

        const val TAG:String="TranslationServerClient"
    }

    private val executor: ExecutorService= Executors.newSingleThreadExecutor()
    @Volatile
    private var socket: Socket?=null
    @Volatile
    private var outputStream: DataOutputStream?=null
    @Volatile
    private var inputStream: DataInputStream?=null
    @Volatile
    private var hasSentAudioSinceLastFlush: Boolean=false

    @Volatile
    private var readerThread:Thread?=null

    private var host=""
    private var port=0

    fun connect(host:String,port:Int){
        this.host=host
        this.port=port
        executor.execute {
            ensureConnected(host,port)
        }
    }

    fun sendAudioChunk(payload: ByteArray,size:Int){
        if(size<=0){
            return
        }

        executor.execute {
            if(!ensureConnected(host,port)){
                return@execute
            }

            try {
                outputStream?.writeByte(CLIENT_AUDIO_CHUNK)
                outputStream?.writeInt(size)
                outputStream?.write(payload,0,size)
                outputStream?.flush()

                hasSentAudioSinceLastFlush=true;
            }catch (e: IOException){
                Log.e(TAG, "sendAudioChunk: Failed",e )
                close()
            }
        }
    }

    fun flush(){
        executor.execute {
            if(!hasSentAudioSinceLastFlush){
                return@execute
            }

            try{
                outputStream?.writeByte(CLIENT_FLUSH)
                outputStream?.writeInt(0)
                outputStream?.flush()
                hasSentAudioSinceLastFlush=false
            }catch (e: IOException){
                Log.e(TAG, "flush: ",e )
                close()
            }
        }
    }

    fun release(){
        executor.execute {
            try{
                if (hasSentAudioSinceLastFlush){
                   flush()
                }

                outputStream?.writeByte(CLIENT_CLOSE)
                outputStream?.writeInt(0)
                outputStream?.flush()
            }catch (e: IOException){
                Log.e(TAG, "release: ", e)
            }
            close()
        }
        executor.shutdownNow()
    }

    private fun ensureConnected(host:String,port:Int) : Boolean{
        if(socket!=null && socket!!.isConnected &&!socket!!.isClosed){
            return true
        }

        close()
        try{
            val newSocket= Socket()
            newSocket.connect(InetSocketAddress(host,port),CONNECT_TIMEOUT_MS)
            newSocket.tcpNoDelay=true
            socket=newSocket
            outputStream= DataOutputStream(BufferedOutputStream(newSocket.outputStream))
            inputStream= DataInputStream(BufferedInputStream(newSocket.inputStream))
            hasSentAudioSinceLastFlush=false

            startReaderThread()
        }catch (e: IOException){
            Log.e(TAG, "ensureConnected: ", e)
            close()
            return false
        }

        return true
    }

    private fun startReaderThread(){
        readerThread= Thread{
            try {
                while (true){
                    val messageType=inputStream?.readUnsignedByte()?:break
                    val payloadSize=inputStream?.readInt()?:break

                    if(payloadSize<0||payloadSize>MAX_SERVER_PAYLOAD_BYTES){
                        Log.e(TAG, "Invalid payload size: $payloadSize ")
                        throw IOException("Invalid payload size: $payloadSize")
                    }

                    val payload= ByteArray(payloadSize)
                    inputStream?.read(payload)
                    handleServerMessage(messageType,payload)
                }
            }catch (e: IOException){
                Log.e(TAG, "startReaderThread: ", e)
            }finally {
                close()
            }
        }.apply {
            name="TranslationServerReader"
            isDaemon=true
            start()
        }
    }

    private fun handleServerMessage(messageType:Int,payload: ByteArray){
        val text=payload.toString(Charsets.UTF_8).trim()
        when(messageType){
            SERVER_TRANSCRIPT->{
                if(text.isNotEmpty()){
                    uiCallbacks.onNewTranscriptReceived(text)
                    Log.i(TAG, "Received Message: $text")
                }
            }
            SERVER_ERROR->{
                Log.e(TAG, "Received Error Message: $text")
            }
            else -> Log.e(TAG, "Unknown Message Type $messageType")
        }
    }

    private fun close(){
        try {
            inputStream?.close()
            outputStream?.close()
            socket?.close()
        }catch (e: Exception){
            Log.e(TAG, "Encountered Exception while close the client connection", e)
        }

        inputStream = null
        outputStream = null
        socket = null
        hasSentAudioSinceLastFlush = false
    }

}