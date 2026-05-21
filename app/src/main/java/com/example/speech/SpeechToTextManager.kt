package com.example.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class SpeechToTextManager(private var context: Context) {
    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var restartRunnable: Runnable? = null
    
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

    private val _partialText = MutableStateFlow("")
    val partialText: StateFlow<String> = _partialText

    private val _finalText = MutableStateFlow("")
    val finalText: StateFlow<String> = _finalText

    private val _errorState = MutableStateFlow<String?>(null)
    val errorState: StateFlow<String?> = _errorState

    private val _rmsDb = MutableStateFlow(0f)
    val rmsDb: StateFlow<Float> = _rmsDb // Amplitude decibels for visual animations

    private var activeLanguageCode = "es-ES"

    fun startListening(languageCode: String) {
        startListening(this.context, languageCode)
    }

    fun startListening(activeContext: Context, languageCode: String) {
        this.context = activeContext
        val selectedLanguage = when(languageCode) {
            "en" -> "en-US"
            "es" -> "es-ES"
            else -> "es-ES"
        }
        activeLanguageCode = selectedLanguage
        
        _errorState.value = null
        _rmsDb.value = 0f
        
        // Always run SpeechRecognizer setup and initialization on the Main Thread
        mainHandler.post {
            try {
                // Pre-clean old listener
                try {
                    speechRecognizer?.destroy()
                } catch (e: Exception) {}
                speechRecognizer = null

                // Attempt to create on device recognizer for 100% local, stable offline transcription
                speechRecognizer = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    try {
                        if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                        } else {
                            SpeechRecognizer.createSpeechRecognizer(context)
                        }
                    } catch (e: Throwable) {
                        Log.w("MASTERScribe", "OnDeviceSpeechRecognizer failed: ${e.localizedMessage}. Falling back to standard.")
                        try {
                            SpeechRecognizer.createSpeechRecognizer(context)
                        } catch (ex: Throwable) {
                            null
                        }
                    }
                } else {
                    try {
                        SpeechRecognizer.createSpeechRecognizer(context)
                    } catch (e: Throwable) {
                        null
                    }
                }

                if (speechRecognizer == null) {
                    _errorState.value = "Servicio de reconocimiento de voz no disponible en este dispositivo."
                    _isRecording.value = false
                    return@post
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, selectedLanguage)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, selectedLanguage)
                    putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, selectedLanguage)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, true)
                    }
                }

                speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        _isRecording.value = true
                        _errorState.value = null
                    }

                    override fun onBeginningOfSpeech() {}

                    override fun onRmsChanged(rmsdB: Float) {
                        _rmsDb.value = rmsdB
                    }

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {}

                    override fun onError(error: Int) {
                        val message = when (error) {
                            SpeechRecognizer.ERROR_AUDIO -> "Error de grabación de audio"
                            SpeechRecognizer.ERROR_CLIENT -> "Error del cliente de voz"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permiso de micrófono faltante"
                            SpeechRecognizer.ERROR_NETWORK -> "Error de red"
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Tiempo de espera de red agotado"
                            SpeechRecognizer.ERROR_NO_MATCH -> "No se detectó voz"
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "El reconocedor de voz está ocupado"
                            SpeechRecognizer.ERROR_SERVER -> "Error del servidor de voz"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Fin de detección"
                            else -> "Reconocedor inactivo"
                        }
                        
                        Log.d("MASTERScribe", "SpeechRecognizer error: $error ($message)")
                        
                        // Restart for seamless uninterrupted transcription
                        if (_isRecording.value) {
                            if (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_NO_MATCH) {
                                restartListening(500)
                            } else if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                                restartListening(1000)
                            } else {
                                _errorState.value = message
                                _isRecording.value = false
                            }
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            val resultText = matches[0]
                            val currentText = _finalText.value
                            val separator = if (currentText.isEmpty()) "" else " "
                            _finalText.value = currentText + separator + resultText
                        }
                        _partialText.value = ""
                        
                        if (_isRecording.value) {
                            restartListening(200)
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            _partialText.value = matches[0]
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                try {
                    speechRecognizer?.startListening(intent)
                    _isRecording.value = true
                } catch (e: Throwable) {
                    Log.e("MASTERScribe", "Failed to start listening: ${e.localizedMessage}")
                    _errorState.value = "Error al iniciar audición: ${e.localizedMessage}"
                    _isRecording.value = false
                }
            } catch (e: Throwable) {
                _errorState.value = "Error al iniciar transcripción local: ${e.localizedMessage}"
                _isRecording.value = false
            }
        }
    }

    private fun restartListening(delayMs: Long) {
        restartRunnable?.let { mainHandler.removeCallbacks(it) }
        
        val runnable = Runnable {
            if (!_isRecording.value) return@Runnable
            try {
                speechRecognizer?.destroy()
            } catch (e: Throwable) {
                Log.e("MASTERScribe", "Error re-inicializando reconocedor: ${e.localizedMessage}")
            }
            speechRecognizer = null
            val currentLang = if (activeLanguageCode.contains("es")) "es" else "en"
            startListening(context, currentLang)
        }
        restartRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    fun stopListening() {
        _isRecording.value = false
        _partialText.value = ""
        restartRunnable?.let { mainHandler.removeCallbacks(it) }
        restartRunnable = null
        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
                speechRecognizer?.destroy()
            } catch (e: Throwable) {
                Log.e("MASTERScribe", "Error al destruir reconocedor de voz: ${e.localizedMessage}")
            }
            speechRecognizer = null
        }
    }

    fun clearText() {
        _finalText.value = ""
        _partialText.value = ""
        _errorState.value = null
    }

    fun appendText(text: String) {
        _finalText.value = _finalText.value + (if (_finalText.value.isEmpty()) "" else "\n") + text
    }

    fun setRecording(recording: Boolean) {
        _isRecording.value = recording
    }

    fun setPartialText(text: String) {
        _partialText.value = text
    }

    fun setFinalText(text: String) {
        _finalText.value = text
    }

    fun setErrorState(error: String?) {
        _errorState.value = error
    }

    fun setRmsDb(db: Float) {
        _rmsDb.value = db
    }
}
