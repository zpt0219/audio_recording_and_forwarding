package com.adna.audiorecorderwatch

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

class AudioRecordingController (
    private val callbacks: Callbacks
){
    interface Callbacks{
        fun sendAudioChunk(byteArray: ByteArray,size:Int): Boolean
        fun onStreamingStarted()
        fun onStreamingFinished()
    }

    companion object{
        const val SAMPLE_HZ=16000
        const val TAG="AudioRecodingController"
    }

    private var audioRecord: AudioRecord?=null
    private var audioRecordThread: AudioRecordThread?=null
    private var bufferSize=0

    @Volatile
    var isStreamingAudio: Boolean=false

    @SuppressLint("MissingPermission")
    fun start(){
        Log.i(TAG, "start recording audio")
        if(isStreamingAudio){
            Log.i(TAG, "audio already streaming at background, return")
            return
        }
        val minBufferSize= AudioRecord.getMinBufferSize(SAMPLE_HZ, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT)
        if(minBufferSize<=0){
            Log.e(TAG, "startStreaming: Failed to initialize microphone")
            return
        }

        bufferSize=minBufferSize.coerceAtLeast(4096)
        val recorder= AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize)

        if(recorder.state!= AudioRecord.STATE_INITIALIZED){
            recorder.release()
            Log.e(TAG, "microphone is not available")
            return
        }

        audioRecord=recorder
        isStreamingAudio=true
        callbacks.onStreamingStarted()

        audioRecordThread=AudioRecordThread()
        audioRecordThread?.start()
    }

    fun stop(){
        Log.i(TAG, "stop recording audio")
        isStreamingAudio=false
        if(audioRecord==null){
            return
        }
        val recorder=audioRecord!!
        audioRecord=null

        try{
            if(recorder.recordingState== AudioRecord.RECORDSTATE_RECORDING){
                recorder.stop()
            }

        }catch (e: Exception){
            Log.e(TAG, "stopStreaming: ", e)
        }finally {
            recorder.release()
        }

        if(audioRecordThread!=Thread.currentThread()){
            audioRecordThread?.join(300)
        }
        audioRecordThread=null
        callbacks.onStreamingFinished()
    }

    inner class AudioRecordThread : Thread(){
        override fun run() {
            try {
                if(audioRecord==null) {
                    return
                }
                val recorder=audioRecord!!
                recorder.startRecording()
                val buffer = ByteArray(bufferSize)

                while (isStreamingAudio) {
                    val bytesRead = recorder.read(buffer, 0, buffer.size)
                    if (bytesRead > 0) {
                        val writeSucceeded = callbacks.sendAudioChunk(buffer, bytesRead)
                        if (!writeSucceeded) {
                            break
                        }
                    } else if (bytesRead < 0) {
                        Log.e(TAG, "AudioRecord read failed: $bytesRead")
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Audio streaming failed", e)
            }
        }
    }
}