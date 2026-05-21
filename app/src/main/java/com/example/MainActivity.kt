package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.Transcription
import com.example.ui.MasterScribeViewModel
import com.example.ui.theme.MyApplicationTheme
import com.example.util.FileUtils
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                MainAppScreen()
            }
        }
    }
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun MainAppScreen() {
    val context = LocalContext.current
    val viewModel: MasterScribeViewModel = viewModel()
    
    val activeTab by viewModel.activeTab.collectAsStateWithLifecycle()
    val transcriptions by viewModel.transcriptions.collectAsStateWithLifecycle()
    val selectedTranscription by viewModel.selectedTranscription.collectAsStateWithLifecycle()
    
    // Check speech package online/on-device
    var isSpeechAvailable by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        isSpeechAvailable = try {
            SpeechRecognizer.isRecognitionAvailable(context)
        } catch (e: Throwable) {
            false
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().testTag("main_scaffold"),
        bottomBar = {
            AuraBottomNavigation(
                activeTab = activeTab,
                onTabSelected = { viewModel.selectTab(it) }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Animated screen content transition
            AnimatedContent(
                targetState = activeTab,
                transitionSpec = {
                    slideInHorizontally(
                        initialOffsetX = { if (targetState > initialState) it else -it },
                        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow)
                    ) + fadeIn(animationSpec = tween(200)) togetherWith
                    slideOutHorizontally(
                        targetOffsetX = { if (targetState > initialState) -it else it },
                        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow)
                    ) + fadeOut(animationSpec = tween(150))
                },
                label = "tab_transitions"
            ) { targetTab ->
                when (targetTab) {
                    0 -> LiveScribelaScreen(viewModel, isSpeechAvailable)
                    1 -> FileScribelaScreen(viewModel)
                    2 -> HistoryScribelaScreen(viewModel, transcriptions)
                    3 -> SettingsScribelaScreen(viewModel, isSpeechAvailable)
                    4 -> CanvasCreatorScribelaScreen(viewModel)
                }
            }

            // Expanded floating Note Detail Overlay view
            selectedTranscription?.let { item ->
                NoteDetailOverlay(
                    transcription = item,
                    viewModel = viewModel,
                    onDismiss = { viewModel.selectTranscription(null) }
                )
            }
        }
    }
}

// Custom Premium Vector Wave visualizer
@Composable
fun VoiceWaveVisualizer(rmsDb: Float, isRecording: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse_equalizer")
    val sweepPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2 * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sweep_phase"
    )

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(110.dp)
            .padding(vertical = 8.dp)
            .testTag("voice_visualizer")
    ) {
        val barCount = 20
        val spacing = 12f
        val itemWidth = (size.width - (spacing * (barCount - 1))) / barCount
        
        val baseRms = if (isRecording) {
            // Amplify decibels for modern aesthetic
            (rmsDb.coerceAtLeast(-2f) + 4f) * 4.5f
        } else {
            2.5f
        }
        
        for (i in 0 until barCount) {
            // symmetric gaussian curves with sine sweep to oscillate
            val distFromCenter = Math.abs(i - (barCount / 2f)) / (barCount / 2f)
            val bellFactor = (1f - distFromCenter * distFromCenter).coerceAtLeast(0.1f)
            
            // Dynamic oscillation offset using the animated phase
            val oscillator = if (isRecording) {
                Math.sin(sweepPhase.toDouble() + (i * 0.45)).toFloat() * 0.4f + 0.6f
            } else {
                0.2f
            }
            
            val barHeight = (baseRms * bellFactor * oscillator * 1.5f).coerceIn(12f, size.height)
            
            val startX = i * (itemWidth + spacing)
            val startY = (size.height / 2f) - (barHeight / 2f)
            
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF6366F1), // Electric Indigo
                        Color(0xFF06B6D4)  // Neon Cyan
                    )
                ),
                topLeft = Offset(startX, startY),
                size = Size(itemWidth, barHeight),
                cornerRadius = CornerRadius(16f, 16f)
            )
        }
    }
}

// -------------------------------------------------------------
// TAB 0: REALTIME Speech Transcription
// -------------------------------------------------------------
@Composable
fun LiveScribelaScreen(viewModel: MasterScribeViewModel, isRecognizerAvailable: Boolean) {
    val context = LocalContext.current
    val isRecording by viewModel.speechManager.isRecording.collectAsStateWithLifecycle()
    val partialText by viewModel.speechManager.partialText.collectAsStateWithLifecycle()
    val finalText by viewModel.speechManager.finalText.collectAsStateWithLifecycle()
    val isRecordingError by viewModel.speechManager.errorState.collectAsStateWithLifecycle()
    val rmsDb by viewModel.speechManager.rmsDb.collectAsStateWithLifecycle()
    val currentLanguage by viewModel.currentLanguage.collectAsStateWithLifecycle()
    
    var meetingTitle by remember { mutableStateOf("") }
    var showDialogToSave by remember { mutableStateOf(false) }

    // Permissions Request contracts
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.startLiveTranscription(context)
        } else {
            Toast.makeText(context, "Permiso de micrófono requerido para transcribir", Toast.LENGTH_LONG).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // App Identity Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(45.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(Color(0xFF6366F1), Color(0xFF06B6D4))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "MASTERScribe Logo Icon",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "Grabadora & Dictado IA",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    ),
                    color = Color.White
                )
                Text(
                    text = "Motor de transcripción 100% Offline",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF94A3B8)
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Language toggle pill
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF131B2E))
                .border(1.dp, Color(0xFF1E294B), RoundedCornerShape(24.dp))
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LanguagePill(
                languageName = "Español",
                langCode = "es",
                active = currentLanguage == "es" && !isRecording,
                onClick = { viewModel.setLanguage("es") }
            )
            LanguagePill(
                languageName = "English",
                langCode = "en",
                active = currentLanguage == "en" && !isRecording,
                onClick = { viewModel.setLanguage("en") }
            )
        }

        if (isRecording) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF991B1B).copy(alpha = 0.15f))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color.Red)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Grabando y transcribiendo en vivo...",
                    color = Color(0xFFFECACA),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Spacer(modifier = Modifier.height(15.dp))

        // Visual equalizer drawing
        VoiceWaveVisualizer(rmsDb = rmsDb, isRecording = isRecording)

        Spacer(modifier = Modifier.height(15.dp))

        // Live text viewer container
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF131B2E))
                .border(1.dp, Color(0xFF1E294B), RoundedCornerShape(20.dp))
                .padding(16.dp)
        ) {
            if (finalText.isEmpty() && partialText.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Hearing,
                        contentDescription = "Ready state icon",
                        tint = Color(0xFF1E294B),
                        modifier = Modifier.size(60.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "El transcribidor local está listo",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF94A3B8)
                    )
                    Text(
                        text = "Presiona el botón de inicio de abajo. Todo se transcribirá de manera totalmente segura.",
                        fontSize = 12.sp,
                        color = Color(0xFF64748B),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    reverseLayout = false
                ) {
                    item {
                        Text(
                            text = finalText,
                            color = Color.White,
                            fontSize = 15.sp,
                            lineHeight = 22.sp
                        )
                    }
                    if (partialText.isNotEmpty()) {
                        item {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "$partialText...",
                                color = Color(0xFF06B6D4),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                lineHeight = 22.sp
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(15.dp))

        // Control Panel
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Clear text action
            IconButton(
                onClick = { viewModel.speechManager.clearText() },
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1E294B))
                    .testTag("clear_button"),
                enabled = finalText.isNotEmpty() || partialText.isNotEmpty()
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Clear transcription log",
                    tint = if (finalText.isNotEmpty() || partialText.isNotEmpty()) Color.White else Color(0xFF64748B)
                )
            }

            // Big Glowing Record Button
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .shadow(
                        elevation = if (isRecording) 16.dp else 4.dp,
                        shape = CircleShape,
                        clip = false,
                        ambientColor = Color(0xFF6366F1),
                        spotColor = Color(0xFF6366F1)
                    )
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            colors = if (isRecording) {
                                listOf(Color(0xFFEF4444), Color(0xFFEC4899))
                            } else {
                                listOf(Color(0xFF6366F1), Color(0xFF06B6D4))
                            }
                        )
                    )
                    .clickable {
                        val engine = viewModel.transcriptionEngine.value
                        if (engine == "local" && !isRecognizerAvailable) {
                            Toast.makeText(context, "El motor SpeechRecognizer no está disponible en este dispositivo. Cambia al Motor Gemini en Ajustes.", Toast.LENGTH_LONG).show()
                            return@clickable
                        }
                        
                        if (isRecording) {
                            if (engine == "local") {
                                showDialogToSave = true
                            } else {
                                viewModel.stopMicRecordingAndTranscribe {
                                    showDialogToSave = true
                                }
                            }
                        } else {
                            val audioPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                            if (audioPermission == PackageManager.PERMISSION_GRANTED) {
                                viewModel.startLiveTranscription(context)
                            } else {
                                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    }
                    .testTag("record_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = if (isRecording) "Stop active recording" else "Start recording speech",
                    tint = Color.White,
                    modifier = Modifier.size(36.dp)
                )
            }

            // Stop/Quick Save button
            IconButton(
                onClick = {
                    if (finalText.isNotEmpty()) {
                        showDialogToSave = true
                    }
                },
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1E294B))
                    .testTag("quick_save_button"),
                enabled = finalText.isNotEmpty()
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Save transcription to history",
                    tint = if (finalText.isNotEmpty()) Color.White else Color(0xFF64748B)
                )
            }
        }

        if (isRecordingError != null) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "${isRecordingError}. Reiniciando escucha...",
                color = Color(0xFFFCA5A5),
                fontSize = 11.sp,
                textAlign = TextAlign.Center
            )
        }
    }

    // Save Transcription Dialogue modal
    if (showDialogToSave) {
        AlertDialog(
            onDismissRequest = { showDialogToSave = false },
            containerColor = Color(0xFF131B2E),
            shape = RoundedCornerShape(24.dp),
            title = {
                Text(
                    text = "Guardar Transcripción",
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontSize = 18.sp
                )
            },
            text = {
                Column {
                    Text(
                        text = "Introduce un título descriptivo para esta reunión o conversación para identificarla fácilmente.",
                        color = Color(0xFF94A3B8),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    OutlinedTextField(
                        value = meetingTitle,
                        onValueChange = { meetingTitle = it },
                        placeholder = { Text("Ej. Reunión de Negocios de Diseño", color = Color(0xFF64748B)) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF6366F1),
                            unfocusedBorderColor = Color(0xFF1E294B),
                            focusedLabelColor = Color(0xFF6366F1),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("save_title_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.stopAndSaveLiveTranscription(meetingTitle)
                        showDialogToSave = false
                        meetingTitle = ""
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Guardar", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.stopAndSaveLiveTranscription()
                    showDialogToSave = false
                }) {
                    Text("Omitir Título", color = Color(0xFF94A3B8))
                }
            }
        )
    }
}

// Language Pill visual helper component
@Composable
fun LanguagePill(
    languageName: String,
    langCode: String,
    active: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (active) Color(0xFF6366F1) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp)
    ) {
        Text(
            text = languageName,
            color = if (active) Color.White else Color(0xFF94A3B8),
            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
            fontSize = 13.sp
        )
    }
}


// -------------------------------------------------------------
// TAB 1: FILE Speech Transcription
// -------------------------------------------------------------
@Composable
fun FileScribelaScreen(viewModel: MasterScribeViewModel) {
    val context = LocalContext.current
    val selectedFileName by viewModel.selectedFileName.collectAsStateWithLifecycle()
    val selectedFileSize by viewModel.selectedFileSize.collectAsStateWithLifecycle()
    val selectedFileDuration by viewModel.selectedFileDuration.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isFilePlaying.collectAsStateWithLifecycle()
    val playbackProgress by viewModel.filePlaybackProgress.collectAsStateWithLifecycle()
    val isRecordingSpeech by viewModel.speechManager.isRecording.collectAsStateWithLifecycle()
    val accumulatedText by viewModel.speechManager.finalText.collectAsStateWithLifecycle()
    val partialText by viewModel.speechManager.partialText.collectAsStateWithLifecycle()
    val currentLanguage by viewModel.currentLanguage.collectAsStateWithLifecycle()
    
    // Pick file SAF contract
    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.onFilePicked(uri)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // App Identity Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(45.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(Color(0xFF06B6D4), Color(0xFFEC4899))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Audiotrack,
                    contentDescription = "File Type Selector Indicator",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "Lector de Archivos",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    ),
                    color = Color.White
                )
                Text(
                    text = "Transcribe audios y videos locales continuos",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF94A3B8)
                )
            }
        }

        Spacer(modifier = Modifier.height(15.dp))

        // File Selector Dashboard Card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF131B2E))
                .border(2.dp, Color(0xFF1E294B), RoundedCornerShape(20.dp))
                .clickable {
                    // Let picking any audio/video document file
                    audioPickerLauncher.launch(arrayOf("audio/*", "video/*"))
                }
                .padding(20.dp)
                .testTag("file_picker_trigger")
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (selectedFileName == null) {
                    Icon(
                        imageVector = Icons.Default.CloudUpload,
                        contentDescription = "Upload Cloud design",
                        tint = Color(0xFF06B6D4),
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Seleccionar Archivo de Audio o Video",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 15.sp
                    )
                    Text(
                        text = "Soporta MP3, WAV, M4A, MP4 sin límites de tiempo.",
                        fontSize = 11.sp,
                        color = Color(0xFF64748B),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.FilePresent,
                        contentDescription = "Selected file emblem",
                        tint = Color(0xFFEC4899),
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = selectedFileName!!,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                    Row(
                        modifier = Modifier.padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = Color(0xFF1E294B),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.padding(end = 6.dp)
                        ) {
                            Text(
                                text = selectedFileSize ?: "0 B",
                                color = Color(0xFFF8FAFC),
                                fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                        Surface(
                            color = Color(0xFF0F172A),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = FileUtils.formatDuration(selectedFileDuration),
                                color = Color(0xFF06B6D4),
                                fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Presiona para cambiar de archivo",
                        fontSize = 10.sp,
                        color = Color(0xFF64748B)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(15.dp))

        // Language config drawer
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF131B2E))
                .border(1.dp, Color(0xFF1E294B), RoundedCornerShape(24.dp))
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LanguagePill(
                languageName = "Español",
                langCode = "es",
                active = currentLanguage == "es" && !isRecordingSpeech,
                onClick = { viewModel.setLanguage("es") }
            )
            LanguagePill(
                languageName = "English",
                langCode = "en",
                active = currentLanguage == "en" && !isRecordingSpeech,
                onClick = { viewModel.setLanguage("en") }
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (isPlaying || isRecordingSpeech) {
            // Live playback track visualizer
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF10B981).copy(alpha = 0.1f))
                    .padding(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF10B981))
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Transcribiendo... Reproduciendo pista de audio",
                        color = Color(0xFFD1FAE5),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { playbackProgress },
                    color = Color(0xFF10B981),
                    trackColor = Color(0xFF047857),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Document transcription output view
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF131B2E))
                .border(1.dp, Color(0xFF1E294B), RoundedCornerShape(20.dp))
                .padding(16.dp)
        ) {
            if (accumulatedText.isEmpty() && partialText.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Description,
                        contentDescription = "File placeholder emblem",
                        tint = Color(0xFF1E294B),
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Resultados del Lector de Archivos",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF94A3B8)
                    )
                    Text(
                        text = "El lector extraerá todo el contenido de audio a texto para que puedas leerlo, exportarlo y resumirlo.",
                        fontSize = 11.sp,
                        color = Color(0xFF64748B),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 2.dp)
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    item {
                        Text(
                            text = accumulatedText,
                            color = Color.White,
                            fontSize = 14.sp,
                            lineHeight = 20.sp
                        )
                    }
                    if (partialText.isNotEmpty()) {
                        item {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "$partialText...",
                                color = Color(0xFFEC4899),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                lineHeight = 20.sp
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(15.dp))

        // Operation Buttons
        if (selectedFileName != null) {
            Button(
                onClick = {
                    if (isPlaying) {
                        viewModel.stopAndSaveFileTranscription()
                    } else {
                        viewModel.startFileTranscription(context)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("file_transcribe_control_btn"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isPlaying) Color(0xFFEF4444) else Color(0xFF06B6D4)
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isPlaying) "Finalizar y Guardar Nota" else "Iniciar Lectura Local de Sonido",
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontSize = 14.sp
                )
            }
        } else {
            Surface(
                color = Color(0xFF1E294B).copy(alpha = 0.5f),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().height(54.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "Selecciona un archivo para transcribir",
                        color = Color(0xFF64748B),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}


// -------------------------------------------------------------
// TAB 2: HISTORY LIST OF PAST TRANSCRIPTIONS
// -------------------------------------------------------------
@Composable
fun HistoryScribelaScreen(viewModel: MasterScribeViewModel, list: List<Transcription>) {
    var searchQuery by remember { mutableStateOf("") }
    val keyboardController = LocalSoftwareKeyboardController.current

    val filteredList = list.filter {
        it.title.contains(searchQuery, ignoreCase = true) || 
        it.text.contains(searchQuery, ignoreCase = true)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        // App Identity Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(45.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(Color(0xFF6366F1), Color(0xFFEC4899))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = "History visual",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "Historial de Notas",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    ),
                    color = Color.White
                )
                Text(
                    text = "Tus bitácoras guardadas localmente",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF94A3B8)
                )
            }
        }

        Spacer(modifier = Modifier.height(15.dp))

        // Search textfield
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Buscar por título o contenido...", color = Color(0xFF64748B), fontSize = 13.sp) },
            leadingIcon = { Icon(imageVector = Icons.Default.Search, contentDescription = null, tint = Color(0xFF64748B)) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Clear search query", tint = Color.LightGray)
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF6366F1),
                unfocusedBorderColor = Color(0xFF1E294B),
                focusedContainerColor = Color(0xFF131B2E),
                unfocusedContainerColor = Color(0xFF131B2E),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() }),
            modifier = Modifier.fillMaxWidth().testTag("history_search_input")
        )

        Spacer(modifier = Modifier.height(15.dp))

        if (filteredList.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = "Empty list emblem",
                        tint = Color(0xFF1E294B),
                        modifier = Modifier.size(60.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = if (searchQuery.isEmpty()) "No hay bitácoras guardadas" else "Ningún resultado coincide",
                        color = Color(0xFF94A3B8),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    )
                    Text(
                        text = if (searchQuery.isEmpty()) "Graba audio en vivo o procesa un archivo para guardarlo aquí." else "Intenta buscar usando otras palabras clave.",
                        color = Color(0xFF64748B),
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(start = 40.dp, end = 40.dp, top = 2.dp)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filteredList) { item ->
                    TranscriptionCardItem(
                        transcription = item,
                        onClick = { viewModel.selectTranscription(item) },
                        onDelete = { viewModel.deleteTranscription(item) }
                    )
                }
            }
        }
    }
}

// Single card listing item
@Composable
fun TranscriptionCardItem(
    transcription: Transcription,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val sdf = SimpleDateFormat("dd MMM, yyyy - HH:mm", Locale.getDefault())
    val dateStr = sdf.format(Date(transcription.timestamp))

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF131B2E))
            .border(1.dp, Color(0xFF1E294B), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(14.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Language badges
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF6366F1).copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = transcription.language.uppercase(Locale.getDefault()),
                            color = Color(0xFF818CF8),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF06B6D4).copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (transcription.isFromFile) "Archivo" else "En vivo",
                            color = Color(0xFF22D3EE),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Delete quick button
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete item",
                        tint = Color(0xFFEF4444).copy(alpha = 0.7f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Title
            Text(
                text = transcription.title,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Body preview
            Text(
                text = transcription.text,
                color = Color(0xFF94A3B8),
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 16.sp
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = dateStr,
                    fontSize = 11.sp,
                    color = Color(0xFF64748B),
                    fontWeight = FontWeight.Medium
                )

                // Indication of AI content summary
                if (transcription.summary != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "Gemini contents generated",
                            tint = Color(0xFF06B6D4),
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Resumen AI",
                            color = Color(0xFF06B6D4),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}


// -------------------------------------------------------------
// TAB 3: APP SETTINGS
// -------------------------------------------------------------
@Composable
fun SettingsScribelaScreen(viewModel: MasterScribeViewModel, isRecognitionAvailable: Boolean) {
    val context = LocalContext.current
    val customApiKey by viewModel.customApiKey.collectAsStateWithLifecycle()
    var editKeyVal by remember { mutableStateOf(customApiKey) }
    var hideSecret by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        // App Identity Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(45.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(Color(0xFF10B981), Color(0xFF06B6D4))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings icon design",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "Ajustes del Sistema",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    ),
                    color = Color.White
                )
                Text(
                    text = "Configura la inteligencia avanzada y variables",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF94A3B8)
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Card Settings 1: Gemini Setup
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF131B2E)),
            shape = RoundedCornerShape(20.dp),
            border = borderStrokeDefault()
        ) {
            Column(
                modifier = Modifier.padding(18.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = "Gemini logo icon indication",
                        tint = Color(0xFF6366F1),
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "API de Gemini AI Studio",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 15.sp
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Para activar resúmenes ejecutivos automáticos de tus reuniones y la extracción de puntos de acción, conecta una clave API válida de Google AI Studio.",
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
                Spacer(modifier = Modifier.height(14.dp))
                
                OutlinedTextField(
                    value = editKeyVal,
                    onValueChange = { editKeyVal = it },
                    label = { Text("Gemini API Key", color = Color(0xFF94A3B8), fontSize = 12.sp) },
                    placeholder = { Text("Introduce AI Studio Key...", color = Color(0xFF64748B)) },
                    visualTransformation = if (hideSecret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                    trailingIcon = {
                        IconButton(onClick = { hideSecret = !hideSecret }) {
                            Icon(
                                imageVector = if (hideSecret) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (hideSecret) "Mostrar clave" else "Ocultar clave",
                                tint = Color.LightGray
                            )
                        }
                    },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF6366F1),
                        unfocusedBorderColor = Color(0xFF1E294B),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("gemini_api_key_field")
                )

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        viewModel.saveCustomApiKey(editKeyVal)
                        Toast.makeText(context, "API Key guardada de manera segura localmente", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Text("Guardar e Integrar API", color = Color.White, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(20.dp))
                
                Text(
                    text = "Modelo Gemini Activo:",
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontSize = 13.sp
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                val currentModel by viewModel.selectedModel.collectAsStateWithLifecycle()
                
                val models = listOf(
                    "gemini-3.5-flash" to "Gemini 3.5 Flash",
                    "gemini-3.1-pro-preview" to "Gemini 3.1 Pro (Preview)",
                    "gemini-3.1-flash-lite-preview" to "Gemini 3.1 Lite (Preview)"
                )
                
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    models.forEach { (id, label) ->
                        val isSelected = currentModel == id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSelected) Color(0xFF1E1B4B) else Color(0xFF0F172A))
                                .border(1.dp, if (isSelected) Color(0xFF6366F1) else Color(0xFF1E293B), RoundedCornerShape(12.dp))
                                .clickable { viewModel.saveSelectedModel(id) }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { viewModel.saveSelectedModel(id) },
                                colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF6366F1), unselectedColor = Color(0xFF475569))
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(text = label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    text = if (id == "gemini-3.5-flash") "Velocidad balanceada y alto desempeño general (Recomendado)"
                                           else if (id == "gemini-3.1-pro-preview") "Mayor poder de razonamiento, ideas complejas y síntesis"
                                           else "Respuesta ultra veloz con consumo optimizado de recursos",
                                    color = Color(0xFF94A3B8),
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(15.dp))

        // Card Settings: Motor de Transcripción Selection
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF131B2E)),
            shape = RoundedCornerShape(20.dp),
            border = borderStrokeDefault()
        ) {
            Column(
                modifier = Modifier.padding(18.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Hearing,
                        contentDescription = "Hearing Icon",
                        tint = Color(0xFF6366F1),
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Motor de Transcripción Principal",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 15.sp
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Elige el motor para transcribir tus notas de voz y archivos locales de audio/video. Se recomienda usar Gemini Cloud para una precisión absoluta.",
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
                Spacer(modifier = Modifier.height(14.dp))

                val currentEngine by viewModel.transcriptionEngine.collectAsStateWithLifecycle()

                val engines = listOf(
                    "gemini" to "Motor Gemini Inteligente (Recomendado)",
                    "local" to "Motor SpeechRecognizer Local (Offline)"
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    engines.forEach { (id, label) ->
                        val isSelected = currentEngine == id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSelected) Color(0xFF1E1B4B) else Color(0xFF0F172A))
                                .border(1.dp, if (isSelected) Color(0xFF6366F1) else Color(0xFF1E293B), RoundedCornerShape(12.dp))
                                .clickable { viewModel.saveTranscriptionEngine(id) }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { viewModel.saveTranscriptionEngine(id) },
                                colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF6366F1), unselectedColor = Color(0xFF475569))
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(text = label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    text = if (id == "gemini") "Graba audio digital y transcribe en la nube. 100% preciso, sin caídas ni ruidos locales."
                                           else "Usa el transcriptor del sistema offline. Requiere servicios de voz de Google instalados en el dispositivo.",
                                    color = Color(0xFF94A3B8),
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(15.dp))

        // Card Settings 2: Engine Status Checks
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF131B2E)),
            shape = RoundedCornerShape(20.dp),
            border = borderStrokeDefault()
        ) {
            Column(
                modifier = Modifier.padding(18.dp)
            ) {
                Text(
                    text = "Estado del Sistema",
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontSize = 15.sp
                )
                Spacer(modifier = Modifier.height(12.dp))

                StatusRow(
                    labelName = "Motor SpeechRecognizer",
                    statusActive = isRecognitionAvailable,
                    statusTextActive = "Disponible (100% Offline)",
                    statusTextInactive = "No admitido"
                )
                Spacer(modifier = Modifier.height(8.dp))
                StatusRow(
                    labelName = "Idioma Español local",
                    statusActive = isRecognitionAvailable, // standard device fallback check
                    statusTextActive = "Disponible",
                    statusTextInactive = "Sin comprobar"
                )
                Spacer(modifier = Modifier.height(8.dp))
                StatusRow(
                    labelName = "Idioma Inglés local",
                    statusActive = isRecognitionAvailable,
                    statusTextActive = "Disponible",
                    statusTextInactive = "Sin comprobar"
                )
            }
        }
    }
}

@Composable
fun StatusRow(labelName: String, statusActive: Boolean, statusTextActive: String, statusTextInactive: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = labelName, color = Color(0xFF94A3B8), fontSize = 13.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (statusActive) Color(0xFF10B981) else Color(0xFFEF4444))
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (statusActive) statusTextActive else statusTextInactive,
                color = if (statusActive) Color(0xFF10B981) else Color(0xFFEF4444),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}


// -------------------------------------------------------------
// TAB 4: REAL-TIME DOCUMENT CANVAS (Manus IA Style)
// -------------------------------------------------------------
@Composable
fun CanvasCreatorScribelaScreen(viewModel: MasterScribeViewModel) {
    val context = LocalContext.current
    val isGenerating by viewModel.isGeneratingDocument.collectAsStateWithLifecycle()
    val docMsg by viewModel.documentError.collectAsStateWithLifecycle()
    val generatedDoc by viewModel.generatedDocument.collectAsStateWithLifecycle()
    val docPdfFile by viewModel.documentPdfResult.collectAsStateWithLifecycle()
    val transcriptions by viewModel.transcriptions.collectAsStateWithLifecycle()

    var textPrompt by remember { mutableStateOf("") }
    var selectedSourceId by remember { mutableStateOf<Int?>(null) }
    var showSourceDropdown by remember { mutableStateOf(false) }
    
    // For direct in-place editing
    var currentEditingSlideId by remember { mutableStateOf<String?>(null) }
    var editSlideTitle by remember { mutableStateOf("") }
    var editSlideSubtitle by remember { mutableStateOf("") }
    var editSlideContent by remember { mutableStateOf("") }
    var editSlideBulletsStr by remember { mutableStateOf("") }
    
    // Fullscreen presentation view (PPT Simulation)
    var showPresentationMode by remember { mutableStateOf(false) }
    var presentationActiveIndex by remember { mutableStateOf(0) }

    // Selected source item text representation
    val selectedSource = transcriptions.find { it.id == selectedSourceId }
    val sourceLabel = selectedSource?.title ?: "Ninguno (Generar desde cero)"

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Platform Header Banner
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(45.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            Brush.linearGradient(
                                colors = listOf(Color(0xFF6366F1), Color(0xFFEC4899))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = "Canvas Icon",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Creador de Documentos IA",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        ),
                        color = Color.White
                    )
                    Text(
                        text = "Genera informes, ensayos y contratos listos para PDF",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF94A3B8)
                    )
                }
            }
        }

        // Setup Panel (only if doc not generated or user wants to redesign)
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF131B2E)),
                shape = RoundedCornerShape(20.dp),
                border = borderStrokeDefault()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Generar Nuevo Documento",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 15.sp
                    )

                    // Topic Input
                    OutlinedTextField(
                        value = textPrompt,
                        onValueChange = { textPrompt = it },
                        label = { Text("Tema o Instrucción del Documento", color = Color(0xFF94A3B8), fontSize = 12.sp) },
                        placeholder = { Text("Ej: Plan estratégico 2026, Estructura del pitch...", color = Color(0xFF64748B), fontSize = 13.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF6366F1),
                            unfocusedBorderColor = Color(0xFF1E294B),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth().testTag("canvas_prompt_input")
                    )

                    // Tareas Rápidas / Sugerencias
                    Column {
                        Text(
                            text = "Presiona una plantilla para rellenar automáticamente:",
                            color = Color(0xFF818CF8),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        
                        val suggestionsList = listOf(
                            "Contrato de Servicios Profesionales Autonómos",
                            "Propuesta Técnica para el Desarrollo de una App",
                            "Ensayo Académico sobre la Física Cuántica",
                            "Plan Estratégico y Financiero para Startup",
                            "Itinerario de Viaje de Negocios de 3 Días",
                            "Orden del Día Completa para Reunión Anual"
                        )
                        
                        androidx.compose.foundation.lazy.LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(suggestionsList.size) { index ->
                                val task = suggestionsList[index]
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(Color(0xFF1E293B))
                                        .border(1.dp, Color(0xFF334155), RoundedCornerShape(16.dp))
                                        .clickable { textPrompt = task }
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = task,
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }

                    // Source Selection Dropdown
                    Column {
                        Text(
                            text = "Importar desde Transcripción Origen (Opcional):",
                            color = Color(0xFF94A3B8),
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF0F172A))
                                .border(1.dp, Color(0xFF1E294B), RoundedCornerShape(12.dp))
                                .clickable { showSourceDropdown = !showSourceDropdown }
                                .padding(14.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = sourceLabel,
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Icon(
                                    imageVector = if (showSourceDropdown) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                                    contentDescription = null,
                                    tint = Color.LightGray,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        if (showSourceDropdown) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                                border = borderStrokeDefault()
                            ) {
                                Column(modifier = Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                                    // Default Option
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                selectedSourceId = null
                                                showSourceDropdown = false
                                            }
                                            .padding(12.dp)
                                    ) {
                                        Text("Ninguno (Generar desde cero)", color = Color(0xFF10B981), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    }
                                    
                                    transcriptions.forEach { transcript ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    selectedSourceId = transcript.id
                                                    showSourceDropdown = false
                                                }
                                                .padding(12.dp)
                                        ) {
                                            Text(transcript.title, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Button(
                        onClick = {
                            if (textPrompt.trim().isEmpty() && selectedSourceId == null) {
                                Toast.makeText(context, "Por favor escribe un tema o selecciona una fuente.", Toast.LENGTH_SHORT).show()
                            } else {
                                viewModel.generateRealtimeDocument(textPrompt, selectedSource)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
                        shape = RoundedCornerShape(12.dp),
                        enabled = !isGenerating,
                        modifier = Modifier.fillMaxWidth().height(48.dp).testTag("canvas_generate_btn")
                    ) {
                        if (isGenerating) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(imageVector = Icons.Default.AutoAwesome, contentDescription = null, tint = Color.White)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Generar Canvas con AI", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        // Action Status Toast message info
        docMsg?.let { msg ->
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = msg,
                        color = Color(0xFF38BDF8),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(12.dp),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // Active Workspace
        generatedDoc?.let { doc ->
            // Document Title & Description (Editable!)
            item {
                var isEditingHeader by remember { mutableStateOf(false) }
                var tempTitle by remember { mutableStateOf(doc.title) }
                var tempDesc by remember { mutableStateOf(doc.description) }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF0F172A))
                        .border(1.dp, Color(0xFF6366F1), RoundedCornerShape(16.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isEditingHeader) {
                        OutlinedTextField(
                            value = tempTitle,
                            onValueChange = { tempTitle = it },
                            label = { Text("Título general", color = Color.Gray, fontSize = 11.sp) },
                            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = tempDesc,
                            onValueChange = { tempDesc = it },
                            label = { Text("Descripción", color = Color.Gray, fontSize = 11.sp) },
                            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    viewModel.updateDocumentHeadings(tempTitle, tempDesc)
                                    isEditingHeader = false
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Guardar", color = Color.White, fontSize = 11.sp)
                            }
                            TextButton(onClick = { isEditingHeader = false }) {
                                Text("Cancelar", color = Color.LightGray, fontSize = 11.sp)
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = doc.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            IconButton(onClick = { isEditingHeader = true }) {
                                Icon(imageVector = Icons.Default.Edit, contentDescription = "Edit Header", tint = Color.LightGray)
                            }
                        }
                        Text(text = doc.description, color = Color(0xFF94A3B8), fontSize = 13.sp)
                    }
                }
            }

            // Exporter Compilation operations (PDF compilation & Presentations deck)
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { viewModel.generateManusDocumentPdf(doc) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).height(45.dp).testTag("compile_canvas_pdf_btn")
                    ) {
                        Icon(imageVector = Icons.Default.PictureAsPdf, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Exportar PDF", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }

                    Button(
                        onClick = {
                            if (doc.slides.isNotEmpty()) {
                                showPresentationMode = true
                                presentationActiveIndex = 0
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD946EF)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).height(45.dp).testTag("play_ppt_btn")
                    ) {
                        Icon(imageVector = Icons.Default.Slideshow, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Presentar", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }

            // Document PDF Share Box layout if ready
            docPdfFile?.let { file ->
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF047857).copy(alpha = 0.3f))
                            .border(1.dp, Color(0xFF10B981), RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("PDF de Alta Precisión Generado", color = Color(0xFFA7F3D0), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Button(
                            onClick = {
                                try {
                                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "application/pdf"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(intent, "Compartir Documento Canvas"))
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Compartir", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            item {
                Text(
                    text = "Secciones / Diapositivas:",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }

            // Rendering of Slides/Sections
            items(doc.slides) { slide ->
                val accColor = when (slide.accentColor) {
                    "Teal" -> Color(0xFF06B6D4)
                    "Pink" -> Color(0xFFEC4899)
                    "Emerald" -> Color(0xFF10B981)
                    "Amber" -> Color(0xFFF59E0B)
                    else -> Color(0xFF6366F1) // Indigo
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            currentEditingSlideId = slide.id
                            editSlideTitle = slide.title
                            editSlideSubtitle = slide.subtitle
                            editSlideContent = slide.content
                            editSlideBulletsStr = slide.bullets.joinToString("\n")
                        },
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF131B2E)),
                    shape = RoundedCornerShape(16.dp),
                    border = borderStrokeDefault()
                ) {
                    Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                        Box(
                            modifier = Modifier
                                .width(8.dp)
                                .fillMaxHeight()
                                .background(accColor)
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = slide.title,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(accColor.copy(alpha = 0.12f))
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(text = slide.accentColor, color = accColor, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            if (slide.subtitle.isNotEmpty()) {
                                Text(text = slide.subtitle, color = Color(0xFF94A3B8), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            }
                            
                            HorizontalDivider(color = Color(0xFF1E294B), thickness = 0.5.dp)
                            
                            Text(text = slide.content, color = Color(0xFFE2E8F0), fontSize = 13.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)

                            if (slide.bullets.isNotEmpty()) {
                                Column(modifier = Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    slide.bullets.take(2).forEach { bullet ->
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(modifier = Modifier.size(4.dp).clip(CircleShape).background(accColor))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(text = bullet, color = Color(0xFF94A3B8), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                    if (slide.bullets.size > 2) {
                                        Text(text = "+ ${slide.bullets.size - 2} más...", color = accColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Modal Sheet Overlay for Real-time Editing (Manus IA Style)
    if (currentEditingSlideId != null) {
        AlertDialog(
            onDismissRequest = { currentEditingSlideId = null },
            containerColor = Color(0xFF0F172A),
            shape = RoundedCornerShape(24.dp),
            title = {
                Text("Editar Contenido Canvas", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 18.sp)
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = editSlideTitle,
                        onValueChange = { editSlideTitle = it },
                        label = { Text("Título de Sección", color = Color.Gray, fontSize = 11.sp) },
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                        modifier = Modifier.fillMaxWidth().testTag("edit_slide_title")
                    )

                    OutlinedTextField(
                        value = editSlideSubtitle,
                        onValueChange = { editSlideSubtitle = it },
                        label = { Text("Subtítulo", color = Color.Gray, fontSize = 11.sp) },
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                        modifier = Modifier.fillMaxWidth().testTag("edit_slide_subtitle")
                    )

                    OutlinedTextField(
                        value = editSlideContent,
                        onValueChange = { editSlideContent = it },
                        label = { Text("Contenido principal", color = Color.Gray, fontSize = 11.sp) },
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 8
                    )

                    OutlinedTextField(
                        value = editSlideBulletsStr,
                        onValueChange = { editSlideBulletsStr = it },
                        label = { Text("Destacados de Sección (Uno por línea)", color = Color.Gray, fontSize = 11.sp) },
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 6
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val sid = currentEditingSlideId!!
                        viewModel.updateSlideTitle(sid, editSlideTitle)
                        viewModel.updateSlideSubtitle(sid, editSlideSubtitle)
                        viewModel.updateSlideContent(sid, editSlideContent)
                        val bulletsList = editSlideBulletsStr.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                        viewModel.updateSlideBullets(sid, bulletsList)
                        currentEditingSlideId = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Aplicar", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { currentEditingSlideId = null }) {
                    Text("Cancelar", color = Color(0xFF64748B))
                }
            }
        )
    }

    // Fullscreen PowerPoint / Slideshow Simulator View
    if (showPresentationMode && generatedDoc != null) {
        val doc = generatedDoc!!
        val slides = doc.slides

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF000000))
                .clickable { /* prevent clicks from background actions */ }
        ) {
            if (slides.isNotEmpty() && presentationActiveIndex in slides.indices) {
                val activeSlide = slides[presentationActiveIndex]
                val accColor = when (activeSlide.accentColor) {
                    "Teal" -> Color(0xFF06B6D4)
                    "Pink" -> Color(0xFFEC4899)
                    "Emerald" -> Color(0xFF10B981)
                    "Amber" -> Color(0xFFF59E0B)
                    else -> Color(0xFF6366F1)
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // Header Presenter Status bar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = doc.title.uppercase(Locale.getDefault()), color = Color(0xFF475569), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        IconButton(onClick = { showPresentationMode = false }) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = "Exit Presentation", tint = Color.LightGray)
                        }
                    }

                    // Content Slide core layout
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(accColor.copy(alpha = 0.15f))
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text(text = activeSlide.subtitle.ifEmpty { "APARTADO ${presentationActiveIndex + 1}" }.uppercase(Locale.getDefault()), color = accColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        Text(text = activeSlide.title, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 34.sp)
                        
                        Box(modifier = Modifier.width(80.dp).height(4.dp).background(accColor))

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(text = activeSlide.content, color = Color(0xFFE2E8F0), fontSize = 16.sp, lineHeight = 24.sp)

                        if (activeSlide.bullets.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                activeSlide.bullets.forEach { bullet ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(accColor))
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(text = bullet, color = Color(0xFF94A3B8), fontSize = 14.sp)
                                    }
                                }
                            }
                        }
                    }

                    // Lower Slider Slide Pagers
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            IconButton(
                                onClick = { if (presentationActiveIndex > 0) presentationActiveIndex-- },
                                enabled = presentationActiveIndex > 0
                            ) {
                                Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Prev Slide", tint = if (presentationActiveIndex > 0) Color.White else Color.DarkGray)
                            }
                            IconButton(
                                onClick = { if (presentationActiveIndex < slides.size - 1) presentationActiveIndex++ },
                                enabled = presentationActiveIndex < slides.size - 1
                            ) {
                                Icon(imageVector = Icons.Default.ArrowForward, contentDescription = "Next Slide", tint = if (presentationActiveIndex < slides.size - 1) Color.White else Color.DarkGray)
                            }
                        }

                        Text(
                            text = "Diapositiva ${presentationActiveIndex + 1} de ${slides.size}",
                            color = Color(0xFF475569),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}


// -------------------------------------------------------------
// EXPANDED NOTE GRAPHICAL ACTION OVERLAY PANEL (MODAL DETAIL)
// -------------------------------------------------------------
@Composable
fun NoteDetailOverlay(
    transcription: Transcription,
    viewModel: MasterScribeViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val isGeminiProcessing by viewModel.isGeminiProcessing.collectAsStateWithLifecycle()
    val geminiResultMsg by viewModel.geminiResultMsg.collectAsStateWithLifecycle()
    val pdfFileResult by viewModel.pdfFileResult.collectAsStateWithLifecycle()
    val clipboardManager = LocalClipboardManager.current
    
    // Auto-scroll logic or scrolling wrapper
    val sdf = SimpleDateFormat("dd MMMM, yyyy - HH:mm:ss", Locale("es", "ES"))
    val formattedDate = sdf.format(Date(transcription.timestamp))

    Surface(
        color = Color(0xFF090D1A),
        modifier = Modifier
            .fillMaxSize()
            .testTag("note_detail_overlay")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
        ) {
            // Header with back shortcut
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                Text(
                    text = "Detalle de Transcripción",
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontSize = 17.sp
                )
                // Sharing quick shortcut
                IconButton(
                    onClick = {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, transcription.title)
                            putExtra(Intent.EXTRA_TEXT, "${transcription.title}\n\n${transcription.text}")
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Compartir texto mediante"))
                    }
                ) {
                    Icon(imageVector = Icons.Default.Share, contentDescription = "Share text", tint = Color(0xFF06B6D4))
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Scrollable contents layout
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(15.dp)
            ) {
                // Info Section
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF131B2E))
                            .border(1.dp, Color(0xFF1E294B), RoundedCornerShape(16.dp))
                            .padding(16.dp)
                    ) {
                        Text(
                            text = transcription.title,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Fecha: $formattedDate",
                            color = Color(0xFF94A3B8),
                            fontSize = 12.sp
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 10.dp),
                            color = Color(0xFF1E294B)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Idioma detectado: ${transcription.language.uppercase(Locale.getDefault())}",
                                color = Color(0xFF6366F1),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            if (transcription.durationSeconds > 0) {
                                Text(
                                    text = "Duración: ${FileUtils.formatDuration(transcription.durationSeconds * 1000L)}",
                                    color = Color(0xFF06B6D4),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                // Transcription Body Section
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF131B2E))
                            .border(1.dp, Color(0xFF1E294B), RoundedCornerShape(16.dp))
                            .padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Texto Transcrito",
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                fontSize = 14.sp
                            )
                            Row {
                                // Copy Button
                                IconButton(onClick = {
                                    clipboardManager.setText(AnnotatedString(transcription.text))
                                    Toast.makeText(context, "Texto copiado al portapapeles", Toast.LENGTH_SHORT).show()
                                }, modifier = Modifier.size(32.dp)) {
                                    Icon(imageVector = Icons.Default.ContentCopy, contentDescription = "Copy text", tint = Color.LightGray, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = transcription.text,
                            color = Color(0xFFF8FAFC),
                            fontSize = 14.sp,
                            lineHeight = 21.sp
                        )
                    }
                }

                // AI PRESETS AND EXTRACTIONS SECTION
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF131B2E))
                            .border(1.dp, Color(0xFF1E294B), RoundedCornerShape(16.dp))
                            .padding(16.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.AutoAwesome, contentDescription = null, tint = Color(0xFF6366F1), modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Acción AI con Presets de Gemini", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Aplica algoritmos estructurados inteligentes a base de todo lo transcrito con el modelo activo.",
                            color = Color(0xFF94A3B8),
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        val activeOption by viewModel.selectedOption.collectAsStateWithLifecycle()
                        
                        val options = listOf(
                            "summary_important" to "Resumir solo cosas importantes",
                            "structured_notes" to "Estructurar notas completas (Dejar todo)",
                            "pdf_structured_content" to "Crear borrador para Reporte PDF"
                        )

                        options.forEach { (id, label) ->
                            val isSelected = activeOption == id
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isSelected) Color(0xFF1E1B4B) else Color(0xFF0F172A))
                                    .border(1.dp, if (isSelected) Color(0xFF6366F1) else Color(0xFF1E293B), RoundedCornerShape(10.dp))
                                    .clickable { viewModel.setSelectedOption(id) }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { viewModel.setSelectedOption(id) },
                                    colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF6366F1), unselectedColor = Color(0xFF475569))
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(text = label, color = Color.White, fontSize = 13.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(15.dp))

                        Button(
                            onClick = { viewModel.runGeminiExtraction(transcription, activeOption) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
                            shape = RoundedCornerShape(12.dp),
                            enabled = !isGeminiProcessing,
                            modifier = Modifier.fillMaxWidth().testTag("run_preset_ai_btn")
                        ) {
                            if (isGeminiProcessing) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(imageVector = Icons.Default.FlashOn, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Procesar Acción con Gemini", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                // AI summary Section
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF131B2E))
                            .border(1.dp, Color(0xFF1E294B), RoundedCornerShape(16.dp))
                            .padding(16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = Color(0xFF6366F1),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Resumen Ejecutivo con AI",
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                fontSize = 14.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        if (transcription.summary == null) {
                            Text(
                                text = "Genera un resumen automático estructurado extrayendo los puntos esenciales de la reunión.",
                                color = Color(0xFF94A3B8),
                                fontSize = 12.sp,
                                modifier = Modifier.padding(bottom = 12.dp)
                            )
                            Button(
                                onClick = { viewModel.runGeminiExtraction(transcription, "summary") },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
                                shape = RoundedCornerShape(12.dp),
                                enabled = !isGeminiProcessing,
                                modifier = Modifier.fillMaxWidth().testTag("generate_summary_btn")
                            ) {
                                if (isGeminiProcessing) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                                } else {
                                    Text("Generar Resumen Inteligente", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        } else {
                            Text(
                                text = transcription.summary,
                                color = Color(0xFF818CF8),
                                fontSize = 13.sp,
                                lineHeight = 19.sp
                            )
                        }
                    }
                }

                // Action items Section
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF131B2E))
                            .border(1.dp, Color(0xFF1E294B), RoundedCornerShape(16.dp))
                            .padding(16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.ListAlt,
                                contentDescription = null,
                                tint = Color(0xFF06B6D4),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Puntos Clave y Acciones AI",
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                fontSize = 14.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        if (transcription.actionItems == null) {
                            Text(
                                text = "Identifica todas las tareas pendientes, decisiones y responsabilidades asignadas de inmediato.",
                                color = Color(0xFF94A3B8),
                                fontSize = 12.sp,
                                modifier = Modifier.padding(bottom = 12.dp)
                            )
                            Button(
                                onClick = { viewModel.runGeminiExtraction(transcription, "actions") },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF06B6D4)),
                                shape = RoundedCornerShape(12.dp),
                                enabled = !isGeminiProcessing,
                                modifier = Modifier.fillMaxWidth().testTag("generate_actions_btn")
                            ) {
                                if (isGeminiProcessing) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                                } else {
                                    Text("Extraer Tareas y Decisiones", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        } else {
                            Text(
                                text = transcription.actionItems,
                                color = Color(0xFF22D3EE),
                                fontSize = 13.sp,
                                lineHeight = 19.sp
                            )
                        }
                    }
                }

                // Export & PDF operations panel
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF131B2E))
                            .border(1.dp, Color(0xFF1E294B), RoundedCornerShape(16.dp))
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "Exportación de Documento",
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Genera un reporte PDF con la transcripción completa, logos del sistema, cabeceras estructuradas y análisis de inteligencia.",
                            color = Color(0xFF94A3B8),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                        
                        Button(
                            onClick = { viewModel.generateTranscriptionPdf(transcription) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().testTag("generate_pdf_btn")
                        ) {
                            Icon(imageVector = Icons.Default.PictureAsPdf, contentDescription = null, tint = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Compilar PDF de Transcripción", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }

                        // Share resulting PDF file
                        pdfFileResult?.let { file ->
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF065F46).copy(alpha = 0.3f))
                                    .padding(8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "PDF compilado con éxito",
                                    color = Color(0xFFA7F3D0),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Button(
                                    onClick = {
                                        try {
                                            val uri: Uri = FileProvider.getUriForFile(
                                                context,
                                                "${context.packageName}.fileprovider",
                                                file
                                            )
                                            val intent = Intent(Intent.ACTION_SEND).apply {
                                                type = "application/pdf"
                                                putExtra(Intent.EXTRA_STREAM, uri)
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            context.startActivity(Intent.createChooser(intent, "Compartir Reporte PDF"))
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "Error al compartir archivo: ${e.message}", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.height(30.dp)
                                ) {
                                    Text("Compartir PDF", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            // Gemini API call toasts messages if any changes occurs
            geminiResultMsg?.let { msg ->
                LaunchedEffect(msg) {
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}


// -------------------------------------------------------------
// NAVIGATION BAR COMPONENT
// -------------------------------------------------------------
@Composable
fun AuraBottomNavigation(
    activeTab: Int,
    onTabSelected: (Int) -> Unit
) {
    NavigationBar(
        containerColor = Color(0xFF000000),
        tonalElevation = 8.dp,
        modifier = Modifier
            .border(1.dp, Color(0xFF1E294B), RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .testTag("bottom_nav")
    ) {
        NavigationBarItem(
            selected = activeTab == 0,
            onClick = { onTabSelected(0) },
            icon = { Icon(imageVector = Icons.Default.Mic, contentDescription = "Live Recording Scribe") },
            label = { Text("En Vivo", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = Color.White,
                selectedTextColor = Color(0xFF6366F1),
                indicatorColor = Color(0xFF6366F1),
                unselectedIconColor = Color(0xFF64748B),
                unselectedTextColor = Color(0xFF64748B)
            ),
            modifier = Modifier.testTag("nav_item_live")
        )
        NavigationBarItem(
            selected = activeTab == 1,
            onClick = { onTabSelected(1) },
            icon = { Icon(imageVector = Icons.Default.Audiotrack, contentDescription = "File Speech Scribe") },
            label = { Text("Archivos", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = Color.White,
                selectedTextColor = Color(0xFF06B6D4),
                indicatorColor = Color(0xFF06B6D4),
                unselectedIconColor = Color(0xFF64748B),
                unselectedTextColor = Color(0xFF64748B)
            ),
            modifier = Modifier.testTag("nav_item_file")
        )
        NavigationBarItem(
            selected = activeTab == 4,
            onClick = { onTabSelected(4) },
            icon = { Icon(imageVector = Icons.Default.AutoAwesome, contentDescription = "AI Canvas Creator Workspace") },
            label = { Text("Canvas AI", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = Color.White,
                selectedTextColor = Color(0xFF6366F1),
                indicatorColor = Color(0xFF6366F1),
                unselectedIconColor = Color(0xFF64748B),
                unselectedTextColor = Color(0xFF64748B)
            ),
            modifier = Modifier.testTag("nav_item_canvas")
        )
        NavigationBarItem(
            selected = activeTab == 2,
            onClick = { onTabSelected(2) },
            icon = { Icon(imageVector = Icons.Default.History, contentDescription = "History Logs Scribe") },
            label = { Text("Historial", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = Color.White,
                selectedTextColor = Color(0xFFEC4899),
                indicatorColor = Color(0xFFEC4899),
                unselectedIconColor = Color(0xFF64748B),
                unselectedTextColor = Color(0xFF64748B)
            ),
            modifier = Modifier.testTag("nav_item_history")
        )
        NavigationBarItem(
            selected = activeTab == 3,
            onClick = { onTabSelected(3) },
            icon = { Icon(imageVector = Icons.Default.Settings, contentDescription = "AuraSettings configuration") },
            label = { Text("Ajustes", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = Color.White,
                selectedTextColor = Color(0xFF10B981),
                indicatorColor = Color(0xFF10B981),
                unselectedIconColor = Color(0xFF64748B),
                unselectedTextColor = Color(0xFF64748B)
            ),
            modifier = Modifier.testTag("nav_item_settings")
        )
    }
}

// Border Stroke styles helper
fun borderStrokeDefault(): androidx.compose.foundation.BorderStroke {
    return androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E294B))
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(text = "Hello $name!", modifier = modifier)
}

