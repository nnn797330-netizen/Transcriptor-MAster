package com.example.ui

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.media.MediaPlayer
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.api.Content
import com.example.api.GeminiClient
import com.example.api.GenerateContentRequest
import com.example.api.GenerationConfig
import com.example.api.Part
import com.example.api.InlineData
import com.example.data.AppDatabase
import com.example.data.Transcription
import com.example.data.TranscriptionRepository
import com.example.speech.SpeechToTextManager
import com.example.util.FileUtils
import com.example.util.PdfGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import org.json.JSONObject
import org.json.JSONArray
import android.graphics.pdf.PdfDocument
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.StaticLayout
import android.graphics.Color
import android.os.Environment
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class EditableSlide(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String,
    val subtitle: String = "",
    val content: String,
    val bullets: List<String> = emptyList(),
    val accentColor: String = "Indigo" // "Indigo", "Teal", "Pink", "Emerald", "Amber"
)

data class EditableDocument(
    val title: String,
    val description: String,
    val slides: List<EditableSlide>
)

class MasterScribeViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getDatabase(application)
    private val repository = TranscriptionRepository(db.transcriptionDao())
    
    val transcriptions: StateFlow<List<Transcription>> = repository.allTranscriptions
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val speechManager = SpeechToTextManager(application)
    
    // UI selections & states
    private val _selectedTranscription = MutableStateFlow<Transcription?>(null)
    val selectedTranscription: StateFlow<Transcription?> = _selectedTranscription.asStateFlow()

    private val _currentLanguage = MutableStateFlow("es") // "es" or "en"
    val currentLanguage: StateFlow<String> = _currentLanguage.asStateFlow()

    private val _activeTab = MutableStateFlow(0) // 0: Live, 1: Files, 2: History, 3: Settings
    val activeTab: StateFlow<Int> = _activeTab.asStateFlow()

    // File Transcription variables
    private val _selectedFileUri = MutableStateFlow<Uri?>(null)
    val selectedFileUri: StateFlow<Uri?> = _selectedFileUri.asStateFlow()

    private val _selectedFileName = MutableStateFlow<String?>(null)
    val selectedFileName: StateFlow<String?> = _selectedFileName.asStateFlow()

    private val _selectedFileSize = MutableStateFlow<String?>(null)
    val selectedFileSize: StateFlow<String?> = _selectedFileSize.asStateFlow()

    private val _selectedFileDuration = MutableStateFlow<Long>(0L)
    val selectedFileDuration: StateFlow<Long> = _selectedFileDuration.asStateFlow()

    private val _isFilePlaying = MutableStateFlow(false)
    val isFilePlaying: StateFlow<Boolean> = _isFilePlaying.asStateFlow()

    private val _filePlaybackProgress = MutableStateFlow(0f)
    val filePlaybackProgress: StateFlow<Float> = _filePlaybackProgress.asStateFlow()

    // Gemini Processing states
    private val _isGeminiProcessing = MutableStateFlow(false)
    val isGeminiProcessing: StateFlow<Boolean> = _isGeminiProcessing.asStateFlow()

    private val _geminiResultMsg = MutableStateFlow<String?>(null)
    val geminiResultMsg: StateFlow<String?> = _geminiResultMsg.asStateFlow()

    // PDF generation results
    private val _pdfFileResult = MutableStateFlow<File?>(null)
    val pdfFileResult: StateFlow<File?> = _pdfFileResult.asStateFlow()

    // SharedPreferences for API key and Model selection
    private val sharedPrefs: SharedPreferences = application.getSharedPreferences("aurascribe_prefs", Context.MODE_PRIVATE)
    private val _customApiKey = MutableStateFlow(sharedPrefs.getString("gemini_api_key", "") ?: "")
    val customApiKey: StateFlow<String> = _customApiKey.asStateFlow()

    private val _selectedModel = MutableStateFlow(sharedPrefs.getString("gemini_model", "gemini-3.5-flash") ?: "gemini-3.5-flash")
    val selectedModel: StateFlow<String> = _selectedModel.asStateFlow()

    private val _transcriptionEngine = MutableStateFlow(sharedPrefs.getString("transcription_engine", "gemini") ?: "gemini")
    val transcriptionEngine: StateFlow<String> = _transcriptionEngine.asStateFlow()

    private var mediaRecorder: android.media.MediaRecorder? = null
    private var audioFile: File? = null
    private var recordingJob: kotlinx.coroutines.Job? = null
    private val _recordingDuration = MutableStateFlow(0L)
    val recordingDuration: StateFlow<Long> = _recordingDuration.asStateFlow()

    // 0: Resumir cosas importantes, 1: Estructurar notas/Dejar como está, 2: Crear borrador para PDF
    private val _selectedOption = MutableStateFlow("important_summary")
    val selectedOption: StateFlow<String> = _selectedOption.asStateFlow()

    // Real-time AI Document Canvas (Manus IA style) States
    private val _generatedDocument = MutableStateFlow<EditableDocument?>(null)
    val generatedDocument: StateFlow<EditableDocument?> = _generatedDocument.asStateFlow()

    private val _isGeneratingDocument = MutableStateFlow(false)
    val isGeneratingDocument: StateFlow<Boolean> = _isGeneratingDocument.asStateFlow()

    private val _documentError = MutableStateFlow<String?>(null)
    val documentError: StateFlow<String?> = _documentError.asStateFlow()

    private val _documentPdfResult = MutableStateFlow<File?>(null)
    val documentPdfResult: StateFlow<File?> = _documentPdfResult.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null
    private var playbackTrackerThread: Thread? = null

    // Tab control
    fun selectTab(idx: Int) {
        _activeTab.value = idx
    }

    // Language switcher
    fun setLanguage(lang: String) {
        _currentLanguage.value = lang
    }

    // Set custom API Key
    fun saveCustomApiKey(key: String) {
        sharedPrefs.edit().putString("gemini_api_key", key).apply()
        _customApiKey.value = key
    }

    // Set Gemini model
    fun saveSelectedModel(model: String) {
        sharedPrefs.edit().putString("gemini_model", model).apply()
        _selectedModel.value = model
    }

    // Set Transcription Engine
    fun saveTranscriptionEngine(engine: String) {
        sharedPrefs.edit().putString("transcription_engine", engine).apply()
        _transcriptionEngine.value = engine
    }

    // Set active extraction option
    fun setSelectedOption(option: String) {
        _selectedOption.value = option
    }

    // Real-time editable operations for AI Document slides
    fun updateSlideTitle(slideId: String, newTitle: String) {
        val currentDoc = _generatedDocument.value ?: return
        val updatedSlides = currentDoc.slides.map {
            if (it.id == slideId) it.copy(title = newTitle) else it
        }
        _generatedDocument.value = currentDoc.copy(slides = updatedSlides)
    }

    fun updateSlideSubtitle(slideId: String, newSubtitle: String) {
        val currentDoc = _generatedDocument.value ?: return
        val updatedSlides = currentDoc.slides.map {
            if (it.id == slideId) it.copy(subtitle = newSubtitle) else it
        }
        _generatedDocument.value = currentDoc.copy(slides = updatedSlides)
    }

    fun updateSlideContent(slideId: String, newContent: String) {
        val currentDoc = _generatedDocument.value ?: return
        val updatedSlides = currentDoc.slides.map {
            if (it.id == slideId) it.copy(content = newContent) else it
        }
        _generatedDocument.value = currentDoc.copy(slides = updatedSlides)
    }

    fun updateSlideBullets(slideId: String, bulletsList: List<String>) {
        val currentDoc = _generatedDocument.value ?: return
        val updatedSlides = currentDoc.slides.map {
            if (it.id == slideId) it.copy(bullets = bulletsList) else it
        }
        _generatedDocument.value = currentDoc.copy(slides = updatedSlides)
    }

    fun updateDocumentHeadings(newTitle: String, newDescription: String) {
        val currentDoc = _generatedDocument.value ?: return
        _generatedDocument.value = currentDoc.copy(title = newTitle, description = newDescription)
    }

    // Select a transcription
    fun selectTranscription(item: Transcription?) {
        _selectedTranscription.value = item
        _pdfFileResult.value = null
        _geminiResultMsg.value = null
    }

    // Start live speech recognizer
    fun startLiveTranscription(context: Context? = null) {
        speechManager.clearText()
        if (_transcriptionEngine.value == "local") {
            if (context != null) {
                speechManager.startListening(context, _currentLanguage.value)
            } else {
                speechManager.startListening(_currentLanguage.value)
            }
        } else {
            startMicRecording()
        }
    }

    fun startMicRecording() {
        val app = getApplication<Application>()
        try {
            audioFile = File(app.cacheDir, "temp_voice_recording.m4a")
            if (audioFile?.exists() == true) {
                audioFile?.delete()
            }

            mediaRecorder = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                android.media.MediaRecorder(app)
            } else {
                @Suppress("DEPRECATION")
                android.media.MediaRecorder()
            }.apply {
                setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(96000)
                setOutputFile(audioFile!!.absolutePath)
                prepare()
                start()
            }

            speechManager.setRecording(true)
            speechManager.setErrorState(null)
            _recordingDuration.value = 0L

            // Start visualizer ticker
            recordingJob?.cancel()
            recordingJob = viewModelScope.launch(Dispatchers.Main) {
                val startTime = System.currentTimeMillis()
                while (mediaRecorder != null) {
                    val durationMs = System.currentTimeMillis() - startTime
                    _recordingDuration.value = durationMs
                    
                    // Format duration to MM:SS
                    val secs = (durationMs / 1000) % 60
                    val mins = (durationMs / (1000 * 60)) % 60
                    val timeStr = String.format(Locale.getDefault(), "%02d:%02d", mins, secs)
                    speechManager.setPartialText("Grabando nota de voz ($timeStr)... Presiona detener para transcribir.")

                    // Read maxAmplitude and convert to rmsDb (scale from 0 to some decibel range, e.g. 0-15)
                    try {
                        val maxAmp = mediaRecorder?.maxAmplitude ?: 0
                        // Convert amplitude to a simulated RMS dB range (1 to 15)
                        val rdb = if (maxAmp > 0) {
                            (20 * Math.log10(maxAmp / 32767.0) + 40).toFloat().coerceIn(1f, 15f)
                        } else {
                            1f
                        }
                        speechManager.setRmsDb(rdb)
                    } catch (e: Exception) {
                        speechManager.setRmsDb(1f)
                    }
                    
                    kotlinx.coroutines.delay(150)
                }
            }
        } catch (e: Exception) {
            Log.e("MASTERScribe", "Error starting microphone recording: ${e.message}", e)
            speechManager.setErrorState("Fallo al iniciar micrófono: ${e.localizedMessage}")
            speechManager.setRecording(false)
            stopMicRecording()
        }
    }

    fun stopMicRecording() {
        recordingJob?.cancel()
        recordingJob = null
        try {
            mediaRecorder?.let {
                it.stop()
                it.release()
            }
        } catch (e: Exception) {
            Log.e("MASTERScribe", "Error stopping MediaRecorder: ${e.message}")
        }
        mediaRecorder = null
    }

    fun stopMicRecordingAndTranscribe(onSuccess: (() -> Unit)? = null) {
        stopMicRecording()
        speechManager.setRecording(false)
        speechManager.setRmsDb(0f)
        
        val file = audioFile
        if (file == null || !file.exists() || file.length() <= 0) {
            speechManager.setErrorState("No se grabó ningún audio o el archivo está de tamaño incorrecto.")
            return
        }

        val apiKey = _customApiKey.value.trim().ifEmpty {
            com.example.BuildConfig.GEMINI_API_KEY.trim()
        }

        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            speechManager.setErrorState("Error: Ingresa una API Key válida de Gemini en Ajustes de la aplicación.")
            return
        }

        _isGeminiProcessing.value = true
        speechManager.setPartialText("Procesando nota de voz con Inteligencia Artificial...")

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val bytes = file.readBytes()
                val base64Data = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                
                val langPrompt = if (_currentLanguage.value == "es") "español" else "inglés"
                val systemPrompt = "Eres un transcriptor de alta precisión. Transcribe el audio adjunto de forma limpia, literal e íntegra en idioma $langPrompt. Devuelve ÚNICAMENTE la transcripción exacta sin preámbulos, ni introducciones, ni comentarios."

                val request = GenerateContentRequest(
                    contents = listOf(
                        Content(
                            parts = listOf(
                                Part(text = "Transcribe el audio adjunto literamente en $langPrompt."),
                                Part(inlineData = InlineData(mimeType = "audio/mp4", data = base64Data))
                            )
                        )
                    ),
                    generationConfig = GenerationConfig(temperature = 0.2f),
                    systemInstruction = Content(parts = listOf(Part(text = systemPrompt)))
                )

                val activeModel = _selectedModel.value.ifEmpty { "gemini-3.5-flash" }
                val response = GeminiClient.apiService.generateContent(activeModel, apiKey, request)
                val responseText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                
                withContext(Dispatchers.Main) {
                    if (!responseText.isNullOrBlank()) {
                        speechManager.setFinalText(responseText.trim())
                        speechManager.setPartialText("")
                        speechManager.setErrorState(null)
                        onSuccess?.invoke()
                    } else {
                        speechManager.setPartialText("")
                        speechManager.setErrorState("Fallo: Gemini no devolvió ningún texto. Prueba otra vez.")
                    }
                }
            } catch (e: Exception) {
                Log.e("MASTERScribe", "Failed Gemini live recording transcription", e)
                withContext(Dispatchers.Main) {
                    speechManager.setPartialText("")
                    speechManager.setErrorState("Error transcribiendo: ${e.localizedMessage}")
                }
            } finally {
                withContext(Dispatchers.Main) {
                    _isGeminiProcessing.value = false
                }
                try {
                    file.delete()
                } catch (ex: Exception) {}
            }
        }
    }

    // Stop live speech and save to database
    fun stopAndSaveLiveTranscription(customTitle: String? = null) {
        if (_transcriptionEngine.value == "local") {
            speechManager.stopListening()
        } else {
            stopMicRecording()
        }
        val text = speechManager.finalText.value.trim()
        if (text.isNotEmpty()) {
            viewModelScope.launch(Dispatchers.IO) {
                val title = customTitle?.trim()?.ifEmpty { null } ?: "Reunión ${System.currentTimeMillis() % 10000}"
                val tr = Transcription(
                    title = title,
                    text = text,
                    language = _currentLanguage.value,
                    isFromFile = false
                )
                val idLong = repository.insert(tr)
                val savedItem = tr.copy(id = idLong.toInt())
                withContext(Dispatchers.Main) {
                    selectTranscription(savedItem)
                    selectTab(2) // Jump to History
                }
            }
        }
    }

    // File selected handler
    fun onFilePicked(uri: Uri) {
        _selectedFileUri.value = uri
        val comp = FileUtils.getFileNameAndSize(getApplication(), uri)
        _selectedFileName.value = comp.first
        _selectedFileSize.value = comp.second
        _selectedFileDuration.value = FileUtils.getMediaDurationMs(getApplication(), uri)
        
        stopFilePlayback()
    }

    // Start transcribing local file
    fun startFileTranscription(context: Context? = null) {
        val uri = _selectedFileUri.value ?: return
        val currentEngine = _transcriptionEngine.value
        
        if (currentEngine == "local") {
            speechManager.clearText()
            if (context != null) {
                speechManager.startListening(context, _currentLanguage.value)
            } else {
                speechManager.startListening(_currentLanguage.value)
            }
            try {
                mediaPlayer?.release()
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(getApplication(), uri)
                    prepare()
                    setOnCompletionListener {
                        stopAndSaveFileTranscription()
                    }
                    start()
                }
                _isFilePlaying.value = true
                trackPlaybackProgress()
            } catch (e: Exception) {
                Log.e("MASTERScribe", "Error playing media file: ${e.message}")
                speechManager.appendText("[Error al reproducir audio local: ${e.localizedMessage}]")
            }
        } else {
            // Direct Gemini Digital Transcription! Perfect, high accuracy, silent and secure.
            speechManager.clearText()
            _isFilePlaying.value = true
            _filePlaybackProgress.value = 0.1f

            val apiKey = _customApiKey.value.trim().ifEmpty {
                com.example.BuildConfig.GEMINI_API_KEY.trim()
            }

            if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
                _isFilePlaying.value = false
                _filePlaybackProgress.value = 0f
                speechManager.setErrorState("Error: Registra una API Key de Gemini en Ajustes de la aplicación.")
                return
            }

            speechManager.setPartialText("Leyendo archivo y convirtiendo datos digitales...")
            
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    withContext(Dispatchers.Main) {
                        _filePlaybackProgress.value = 0.3f
                        speechManager.setPartialText("Enviando pista de sonido al motor Gemini Cloud...")
                    }
                    
                    val bytes = FileUtils.getUriBytes(getApplication(), uri)
                    if (bytes == null || bytes.isEmpty()) {
                        withContext(Dispatchers.Main) {
                            _isFilePlaying.value = false
                            _filePlaybackProgress.value = 0f
                            speechManager.setErrorState("Fallo al leer archivos: No se pudo cargar la pista multimedia.")
                        }
                        return@launch
                    }

                    // Check file size (e.g. max 25MB for safety in inline base64)
                    val sizeMB = bytes.size / (1024.0 * 1024.0)
                    if (sizeMB > 25.0) {
                        withContext(Dispatchers.Main) {
                            _isFilePlaying.value = false
                            _filePlaybackProgress.value = 0f
                            speechManager.setErrorState("Error: El archivo es demasiado grande (${String.format(Locale.getDefault(), "%.1f", sizeMB)}MB). El límite para transcripción directa de archivo es de 25MB.")
                        }
                        return@launch
                    }

                    val base64Data = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                    val mimeType = FileUtils.getMimeType(getApplication(), uri)

                    withContext(Dispatchers.Main) {
                        _filePlaybackProgress.value = 0.6f
                        speechManager.setPartialText("Transcribiendo archivo digital... Transcriptor Gemini está pensando.")
                    }

                    val langPrompt = if (_currentLanguage.value == "es") "español" else "inglés"
                    val systemPrompt = "Eres un transcriptor judicial ultra-preciso. Debes transcribir enteramente la pista de audio multimedia enviada al idioma $langPrompt de forma textual, literal e impecable. Devuelve SÓLO el texto completo de la transcripción, sin saludos, explicaciones, ni textos introductorios."

                    val request = GenerateContentRequest(
                        contents = listOf(
                            Content(
                                parts = listOf(
                                    Part(text = "Transcribe el audio adjunto en idioma $langPrompt."),
                                    Part(inlineData = InlineData(mimeType = mimeType, data = base64Data))
                                )
                            )
                        ),
                        generationConfig = GenerationConfig(temperature = 0.2f),
                        systemInstruction = Content(parts = listOf(Part(text = systemPrompt)))
                    )

                    val activeModel = _selectedModel.value.ifEmpty { "gemini-3.5-flash" }
                    val response = GeminiClient.apiService.generateContent(activeModel, apiKey, request)
                    val responseText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text

                    withContext(Dispatchers.Main) {
                        _filePlaybackProgress.value = 1.0f
                        _isFilePlaying.value = false
                        if (!responseText.isNullOrBlank()) {
                            speechManager.setFinalText(responseText.trim())
                            speechManager.setPartialText("")
                            speechManager.setErrorState(null)
                            
                            // Automatically call save
                            stopAndSaveFileTranscription()
                        } else {
                            speechManager.setPartialText("")
                            speechManager.setErrorState("Fallo: La transcripción digital devolvió contenido vacío.")
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MASTERScribe", "Error transcribing audio file directly in cloud", e)
                    withContext(Dispatchers.Main) {
                        _isFilePlaying.value = false
                        _filePlaybackProgress.value = 0f
                        speechManager.setPartialText("")
                        speechManager.setErrorState("Error de transcripción: ${e.localizedMessage}")
                    }
                }
            }
        }
    }

    private fun trackPlaybackProgress() {
        try {
            playbackTrackerThread?.interrupt()
        } catch (e: Exception) {}
        playbackTrackerThread = Thread {
            try {
                while (_isFilePlaying.value) {
                    val player = mediaPlayer ?: break
                    try {
                        if (player.isPlaying) {
                            val duration = player.duration
                            if (duration > 0) {
                                val progress = player.currentPosition.toFloat() / duration.toFloat()
                                _filePlaybackProgress.value = progress
                            }
                        }
                    } catch (e: Exception) {
                        // ignore MediaPlayer lifecycle exceptions safely
                    }
                    Thread.sleep(300)
                }
            } catch (e: InterruptedException) {
                // Thread interrupted, fine
            } catch (e: Exception) {
                // Prevent any other crash on background thread
            }
        }.apply { start() }
    }

    fun stopFilePlayback() {
        _isFilePlaying.value = false
        _filePlaybackProgress.value = 0f
        playbackTrackerThread?.interrupt()
        playbackTrackerThread = null
        try {
            mediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        } catch (e: Exception) {}
        mediaPlayer = null
    }

    fun stopAndSaveFileTranscription(customTitle: String? = null) {
        stopFilePlayback()
        speechManager.stopListening()
        val text = speechManager.finalText.value.trim()
        val fileName = _selectedFileName.value ?: "Archivo de Voz"
        
        if (text.isNotEmpty()) {
            viewModelScope.launch(Dispatchers.IO) {
                val title = customTitle?.trim()?.ifEmpty { null } ?: "Transcripción: $fileName"
                val tr = Transcription(
                    title = title,
                    text = text,
                    language = _currentLanguage.value,
                    durationSeconds = _selectedFileDuration.value / 1000L,
                    isFromFile = true
                )
                val idLong = repository.insert(tr)
                val savedItem = tr.copy(id = idLong.toInt())
                withContext(Dispatchers.Main) {
                    selectTranscription(savedItem)
                    selectTab(2) // Jump to History
                }
            }
        }
    }

    // Save/delete transcripts directly
    fun deleteTranscription(item: Transcription) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.delete(item)
            if (_selectedTranscription.value?.id == item.id) {
                withContext(Dispatchers.Main) {
                    selectTranscription(null)
                }
            }
        }
    }

    // PDF generation
    fun generateTranscriptionPdf(transcription: Transcription) {
        viewModelScope.launch(Dispatchers.IO) {
            val file = PdfGenerator.generatePdf(getApplication(), transcription)
            withContext(Dispatchers.Main) {
                _pdfFileResult.value = file
            }
        }
    }

    // Gemini API Action: Summarize or Extract Action Items with customized modes
    fun runGeminiExtraction(transcription: Transcription, actionType: String) {
        val apiKey = _customApiKey.value.trim().ifEmpty {
            com.example.BuildConfig.GEMINI_API_KEY.trim()
        }

        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            _geminiResultMsg.value = "Error: Por favor, introduce una API Key válida de Gemini en Ajustes de la aplicación."
            return
        }

        _isGeminiProcessing.value = true
        _geminiResultMsg.value = null

        viewModelScope.launch(Dispatchers.IO) {
            val systemPrompt = when(actionType) {
                "summary_important" -> "Eres un transcribidor y redactor ultra inteligente. SÓLO resume los puntos críticos absolutos, decisiones clave e información de mayor importancia de la siguiente transcripción, omitiendo el relleno o comentarios menores. Formato impecable en puntos clave y español."
                "structured_notes" -> "Eres un redactor profesional del sistema. Estructura el siguiente texto de transcripción completo de forma profesional, agregando subtítulos descriptivos, párrafos limpios y rectificaciones de sintaxis, pero SIN omitir detalles del contenido original (dejar completo y legible). Todo en español."
                "pdf_structured_content" -> "Eres un asistente de redacción ejecutivo. Diseña un borrador formal completo y detallado optimizado para compilar un reporte en formato PDF. Incluye: Introducción o contexto, Temas de discusión principales detallados paso a paso, Puntos clave y Recomendaciones estratégicas finales basadas en el audio transcrito. Redacción corporativa y pulida en español."
                "summary" -> "Eres un asistente de transcripción y síntesis. Proporciona un resumen ejecutivo limpio de la siguiente transcripción en español."
                "actions" -> "Eres un redactor y organizador corporativo. Revisa la siguiente transcripción y extrae una lista numerada limpia de puntos de acción, tareas pendientes directas y personas asignadas si es aplicable. En español."
                else -> "Analiza el siguiente texto y proporciona conclusiones profesionales estructuradas en español."
            }

            val request = GenerateContentRequest(
                contents = listOf(Content(parts = listOf(Part(text = transcription.text)))),
                generationConfig = GenerationConfig(temperature = 0.4f),
                systemInstruction = Content(parts = listOf(Part(text = systemPrompt)))
            )

            try {
                val activeModel = _selectedModel.value.ifEmpty { "gemini-3.5-flash" }
                val response = GeminiClient.apiService.generateContent(activeModel, apiKey, request)
                val responseText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                
                if (responseText != null) {
                    val updatedTranscription = when(actionType) {
                        "summary", "summary_important" -> transcription.copy(summary = responseText)
                        "actions", "structured_notes", "pdf_structured_content" -> transcription.copy(actionItems = responseText)
                        else -> transcription
                    }
                    repository.update(updatedTranscription)
                    withContext(Dispatchers.Main) {
                        _selectedTranscription.value = updatedTranscription
                        _geminiResultMsg.value = "¡Análisis AI completado con éxito con el modelo $activeModel!"
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        _geminiResultMsg.value = "Error: El modelo de Gemini no devolvió respuesta. Verifica el texto."
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _geminiResultMsg.value = "Fallo de API: ${e.localizedMessage ?: "Verifica tu conexión y clave de API"}"
                }
                Log.e("MASTERScribe", "Gemini call error: ${e.localizedMessage}")
            } finally {
                withContext(Dispatchers.Main) {
                    _isGeminiProcessing.value = false
                }
            }
        }
    }

    // AI Canvas Generator (Manus IA model)
    fun generateRealtimeDocument(prompt: String, sourceTranscript: Transcription?) {
        val apiKey = _customApiKey.value.trim().ifEmpty {
            com.example.BuildConfig.GEMINI_API_KEY.trim()
        }

        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            _documentError.value = "Error: Por favor, introduce una API Key de Gemini en Ajustes de la aplicación."
            return
        }

        _isGeneratingDocument.value = true
        _documentError.value = null
        _documentPdfResult.value = null

        viewModelScope.launch(Dispatchers.IO) {
            val systemPrompt = """
                Eres un Diseñador de Documentos Profesionales (estilo Manus IA). Tu meta es generar un documento de presentación refinado y estructurado basado en los parámetros o transcripciones suministradas.
                Debes responder UNICAMENTE con una estructura JSON válida que coincida con este esquema EXACTAMENTE. Sin comentarios, sin textos adicionales fuera del bloque de JSON.

                Estructura JSON:
                {
                  "title": "Título del Documento",
                  "description": "Una descripción introductoria elegante",
                  "slides": [
                    {
                      "title": "Título de la Sección o Diapositiva",
                      "subtitle": "Subtítulo corto de apoyo",
                      "content": "Contenido de párrafo explicativo y bien redactado.",
                      "bullets": ["Información o dato resaltado 1", "Información o dato resaltado 2"],
                      "accentColor": "Indigo"
                    }
                  ]
                }

                Instrucciones importantes:
                - Genera exactamente entre 3 y 6 secciones/diapositivas completas que cubran de inicio a fin todo el tema de manera fluida.
                - Elige de manera estéticas los colores de acento ("accentColor") de cada sección entre: "Indigo", "Teal", "Pink", "Emerald", "Amber".
                - Redacta de forma súper profesional, elocuente y enteramente en español.
            """.trimIndent()

            val textToProcess = if (sourceTranscript != null) {
                "TEMA SOLICITADO: $prompt\n\nCONTENIDO DE TRANSCRIPCIÓN BASE ORIGEN:\n${sourceTranscript.text}"
            } else {
                "TEMA SOLICITADO: $prompt"
            }

            val request = GenerateContentRequest(
                contents = listOf(Content(parts = listOf(Part(text = textToProcess)))),
                generationConfig = GenerationConfig(temperature = 0.6f),
                systemInstruction = Content(parts = listOf(Part(text = systemPrompt)))
            )

            try {
                val activeModel = _selectedModel.value.ifEmpty { "gemini-3.5-flash" }
                val response = GeminiClient.apiService.generateContent(activeModel, apiKey, request)
                val responseText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                
                if (responseText != null) {
                    var cleaned = responseText.trim()
                    if (cleaned.startsWith("```json")) {
                        cleaned = cleaned.substring(7)
                    } else if (cleaned.startsWith("```")) {
                        cleaned = cleaned.substring(3)
                    }
                    if (cleaned.endsWith("```")) {
                        cleaned = cleaned.substring(0, cleaned.length - 3)
                    }
                    cleaned = cleaned.trim()

                    val jsonDoc = JSONObject(cleaned)
                    val title = jsonDoc.optString("title", "Documento AI")
                    val description = jsonDoc.optString("description", "")
                    val slidesArray = jsonDoc.optJSONArray("slides")
                    val slidesList = ArrayList<EditableSlide>()

                    if (slidesArray != null) {
                        for (i in 0 until slidesArray.length()) {
                            val item = slidesArray.getJSONObject(i)
                            val bulsArray = item.optJSONArray("bullets")
                            val bulletsList = ArrayList<String>()
                            if (bulsArray != null) {
                                for (j in 0 until bulsArray.length()) {
                                    bulletsList.add(bulsArray.getString(j))
                                }
                            }
                            slidesList.add(
                                EditableSlide(
                                    title = item.optString("title", "Sección"),
                                    subtitle = item.optString("subtitle", ""),
                                    content = item.optString("content", ""),
                                    bullets = bulletsList,
                                    accentColor = item.optString("accentColor", "Indigo")
                                )
                            )
                        }
                    }

                    val doc = EditableDocument(
                        title = title,
                        description = description,
                        slides = slidesList
                    )

                    withContext(Dispatchers.Main) {
                        _generatedDocument.value = doc
                        _documentError.value = "¡Canvas inteligente cargado con éxito! Ahora puedes editarlo."
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        _documentError.value = "Error: El modelo no devolvió ningún contenido."
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _documentError.value = "Fallo de generación: ${e.localizedMessage}"
                }
                Log.e("ManusBuilder", "Error generating document: ${e.localizedMessage}")
            } finally {
                withContext(Dispatchers.Main) {
                    _isGeneratingDocument.value = false
                }
            }
        }
    }

    // PDF compiler for Slides/Pages Document
    fun generateManusDocumentPdf(document: EditableDocument) {
        _isGeneratingDocument.value = true
        _documentError.value = null
        _documentPdfResult.value = null

        viewModelScope.launch(Dispatchers.IO) {
            val pdfDocument = PdfDocument()
            val pageWidth = 595
            val pageHeight = 842
            val paint = Paint()
            val titlePaint = TextPaint().apply {
                color = Color.rgb(31, 41, 55)
                textSize = 20f
                isFakeBoldText = true
                isAntiAlias = true
            }
            val subtitlePaint = TextPaint().apply {
                color = Color.rgb(100, 116, 139)
                textSize = 10f
                isAntiAlias = true
            }
            val headingPaint = TextPaint().apply {
                textSize = 15f
                isFakeBoldText = true
                isAntiAlias = true
            }
            val textPaint = TextPaint().apply {
                color = Color.rgb(51, 65, 85)
                textSize = 11f
                isAntiAlias = true
            }
            val bulletPaint = TextPaint().apply {
                color = Color.rgb(30, 41, 59)
                textSize = 10.5f
                isAntiAlias = true
            }

            // Draw Cover Page (Slide 0)
            var pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
            var page = pdfDocument.startPage(pageInfo)
            var canvas = page.canvas
            
            // Draw premium page frame (outer rounded shell)
            val outerFrame = RectF(25f, 25f, pageWidth - 25f, pageHeight - 25f)
            paint.color = Color.rgb(255, 255, 255)
            paint.style = Paint.Style.FILL
            canvas.drawRoundRect(outerFrame, 16f, 16f, paint)

            paint.color = Color.rgb(226, 232, 240)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.2f
            canvas.drawRoundRect(outerFrame, 16f, 16f, paint)
            
            // Left elegant brand decoration bar (rounded corner)
            val leftBar = RectF(35f, 40f, 43f, pageHeight - 40f)
            paint.color = Color.rgb(99, 102, 241) // Royal Indigo Color
            paint.style = Paint.Style.FILL
            canvas.drawRoundRect(leftBar, 4f, 4f, paint)

            var currentY = 150f
            titlePaint.textSize = 24f
            
            // Wrap title to fit inside design boundary
            val coverTitleLayout = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                android.text.StaticLayout.Builder.obtain(document.title, 0, document.title.length, titlePaint, 430).build()
            } else {
                @Suppress("DEPRECATION")
                android.text.StaticLayout(document.title, titlePaint, 430, android.text.Layout.Alignment.ALIGN_NORMAL, 1.1f, 0.0f, false)
            }
            canvas.save()
            canvas.translate(60f, currentY)
            coverTitleLayout.draw(canvas)
            canvas.restore()
            currentY += coverTitleLayout.height + 15f
            
            subtitlePaint.textSize = 10f
            subtitlePaint.color = Color.rgb(100, 116, 139)
            canvas.drawText("INFORME EJECUTIVO - DOCUMENTO DIGITAL DE ALTA PRECISIÓN", 60f, currentY, subtitlePaint)
            currentY += 30f
            
            // Draw Description
            val descLayout = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                android.text.StaticLayout.Builder.obtain(document.description, 0, document.description.length, textPaint, 440).build()
            } else {
                @Suppress("DEPRECATION")
                android.text.StaticLayout(document.description, textPaint, 440, android.text.Layout.Alignment.ALIGN_NORMAL, 1.2f, 0.0f, false)
            }
            canvas.save()
            canvas.translate(60f, currentY)
            descLayout.draw(canvas)
            canvas.restore()
            currentY += descLayout.height + 50f
            
            // Date metadata without app names
            subtitlePaint.textSize = 9.5f
            val genDateStr = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())
            canvas.drawText("GENERADO: $genDateStr", 60f, currentY, subtitlePaint)
            currentY += 15f
            canvas.drawText("ORGANIZACIÓN Y PLANEACIÓN INTELIGENTE (IA)", 60f, currentY, subtitlePaint)
            
            pdfDocument.finishPage(page)
            
            // Add a Page for Each Slide
            var pageNum = 2
            for (slide in document.slides) {
                pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum++).create()
                page = pdfDocument.startPage(pageInfo)
                canvas = page.canvas
                
                // Draw rounded frame
                val slideFrame = RectF(25f, 25f, pageWidth - 25f, pageHeight - 25f)
                paint.color = Color.rgb(255, 255, 255)
                paint.style = Paint.Style.FILL
                canvas.drawRoundRect(slideFrame, 16f, 16f, paint)

                paint.color = Color.rgb(226, 232, 240)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1f
                canvas.drawRoundRect(slideFrame, 16f, 16f, paint)
                
                // Slide accent color parsing
                val accColor = when (slide.accentColor) {
                    "Teal" -> Color.rgb(6, 182, 212)
                    "Pink" -> Color.rgb(236, 72, 153)
                    "Emerald" -> Color.rgb(16, 185, 129)
                    "Amber" -> Color.rgb(245, 158, 11)
                    else -> Color.rgb(99, 102, 241) // Indigo
                }
                
                // Accent tag badge bar next to content
                val accentBar = RectF(35f, 40f, 40f, pageHeight - 40f)
                paint.color = accColor
                paint.style = Paint.Style.FILL
                canvas.drawRoundRect(accentBar, 3f, 3f, paint)
                
                currentY = 55f
                
                // Header badge label (Category Pill)
                paint.color = when (slide.accentColor) {
                    "Teal" -> Color.rgb(236, 254, 255)
                    "Pink" -> Color.rgb(253, 242, 248)
                    "Emerald" -> Color.rgb(236, 253, 245)
                    "Amber" -> Color.rgb(255, 251, 235)
                    else -> Color.rgb(238, 242, 255)
                }
                paint.style = Paint.Style.FILL
                val categoryPillRect = RectF(52f, currentY, 180f, currentY + 16f)
                canvas.drawRoundRect(categoryPillRect, 8f, 8f, paint)

                paint.color = accColor
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 0.6f
                canvas.drawRoundRect(categoryPillRect, 8f, 8f, paint)

                val badgeTextPaint = TextPaint().apply {
                    color = accColor
                    textSize = 7.5f
                    isFakeBoldText = true
                    isAntiAlias = true
                }
                val badgeText = "SECCIÓN: ${slide.accentColor.uppercase(Locale.getDefault())}"
                canvas.drawText(badgeText, 62f, currentY + 11f, badgeTextPaint)
                currentY += 32f
                
                // Slide Title
                headingPaint.color = Color.rgb(15, 23, 42) // clean slate
                headingPaint.textSize = 15f
                headingPaint.isFakeBoldText = true
                canvas.drawText(slide.title, 52f, currentY, headingPaint)
                currentY += 15f
                
                if (slide.subtitle.isNotEmpty()) {
                    subtitlePaint.textSize = 10f
                    subtitlePaint.color = Color.rgb(100, 116, 139)
                    canvas.drawText(slide.subtitle, 52f, currentY, subtitlePaint)
                    currentY += 15f
                }
                
                paint.color = Color.rgb(226, 232, 240)
                paint.strokeWidth = 1f
                canvas.drawLine(52f, currentY, 545f, currentY, paint)
                currentY += 22f
                
                // Slide Content Wrap
                val contentLayout = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    android.text.StaticLayout.Builder.obtain(slide.content, 0, slide.content.length, textPaint, 480).build()
                } else {
                    @Suppress("DEPRECATION")
                    android.text.StaticLayout(slide.content, textPaint, 480, android.text.Layout.Alignment.ALIGN_NORMAL, 1.2f, 0.0f, false)
                }
                canvas.save()
                canvas.translate(52f, currentY)
                contentLayout.draw(canvas)
                canvas.restore()
                currentY += contentLayout.height + 25f
                
                // Bullet Highlight Box (Modern Rounded Secondary Card Container)
                if (slide.bullets.isNotEmpty()) {
                    // Precompute total bullet height
                    var bulletBlocksHeight = 25f
                    val tempLayouts = ArrayList<StaticLayout>()
                    
                    for (bullet in slide.bullets) {
                        if (bullet.trim().isEmpty()) continue
                        val bulText = "• ${bullet.trim()}"
                        val bulletLayout = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                            android.text.StaticLayout.Builder.obtain(bulText, 0, bulText.length, bulletPaint, 460).build()
                        } else {
                            @Suppress("DEPRECATION")
                            android.text.StaticLayout(bulText, bulletPaint, 460, android.text.Layout.Alignment.ALIGN_NORMAL, 1.1f, 0.0f, false)
                        }
                        tempLayouts.add(bulletLayout)
                        bulletBlocksHeight += bulletLayout.height + 8f
                    }
                    
                    // Box with rounded borders inside slides
                    val bulletBoxRect = RectF(52f, currentY, 545f, currentY + bulletBlocksHeight + 10f)
                    paint.color = Color.rgb(248, 250, 252) // elegant gray #f8fafc
                    paint.style = Paint.Style.FILL
                    canvas.drawRoundRect(bulletBoxRect, 10f, 10f, paint)

                    paint.color = Color.rgb(226, 232, 240)
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 0.8f
                    canvas.drawRoundRect(bulletBoxRect, 10f, 10f, paint)
                    
                    val boxTitlePaint = TextPaint().apply {
                        textSize = 9f
                        isFakeBoldText = true
                        color = accColor
                        isAntiAlias = true
                    }
                    canvas.drawText("PUNTOS RELEVANTES:", 64f, currentY + 18f, boxTitlePaint)
                    var bulletCurrentY = currentY + 28f
                    
                    for (layout in tempLayouts) {
                        canvas.save()
                        canvas.translate(64f, bulletCurrentY)
                        layout.draw(canvas)
                        canvas.restore()
                        bulletCurrentY += layout.height + 8f
                    }
                }
                
                // draw slide page footer without branding
                subtitlePaint.textSize = 8f
                subtitlePaint.color = Color.rgb(148, 163, 184)
                canvas.drawText("Página ${pageNum-1} | Documento Informativo Profesional", 52f, pageHeight - 40f, subtitlePaint)
                
                pdfDocument.finishPage(page)
            }
            
            val pureTitle = document.title.replace("[^a-zA-Z0-9]".toRegex(), "_")
            val fileName = "Doc_${pureTitle}_${System.currentTimeMillis()}.pdf"
            val storageDir = getApplication<Application>().getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: getApplication<Application>().filesDir
            val pdfFile = java.io.File(storageDir, fileName)
            
            try {
                val outputStream = FileOutputStream(pdfFile)
                pdfDocument.writeTo(outputStream)
                outputStream.flush()
                outputStream.close()
                pdfDocument.close()
                withContext(Dispatchers.Main) {
                    _documentPdfResult.value = pdfFile
                    _documentError.value = "Documento PDF compilado con éxito."
                }
            } catch (e: Exception) {
                pdfDocument.close()
                Log.e("ManusBuilder", "Failed compiling PDF: ${e.message}")
                withContext(Dispatchers.Main) {
                    _documentError.value = "Error al compilar PDF: ${e.localizedMessage}"
                }
            } finally {
                withContext(Dispatchers.Main) {
                    _isGeneratingDocument.value = false
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopMicRecording()
        stopFilePlayback()
        speechManager.stopListening()
    }
}
