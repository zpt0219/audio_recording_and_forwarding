package com.adna.audiorecorderphone

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class MainViewModel : ViewModel() {
    private val _statusText = MutableLiveData<String>()
    val statusText: LiveData<String> = _statusText

    private val _transcriptionText= MutableLiveData<String>()
    val transcriptionText:LiveData<String> = _transcriptionText

    private val _isButtonEnabled= MutableLiveData<Boolean>()
    val isButtonEnabled: LiveData<Boolean> = _isButtonEnabled

    fun updateStatus(text:String){
        _statusText.postValue(text)
    }

    fun setTranscriptionText(text:String){
        _transcriptionText.postValue(text)
    }

    fun appendTranscriptionText(text:String){
        val current=_transcriptionText.value.orEmpty().trim()
        _transcriptionText.postValue(current+"\n\n"+text)
    }

    fun setButtonEnabled(isEnabled: Boolean){
        _isButtonEnabled.postValue(isEnabled)
    }

}