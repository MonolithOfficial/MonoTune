package net.darkroom.monotune

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import net.darkroom.monotune.ui.theme.MonotuneTheme
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MonotuneTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    TunerScreen()
                }
            }
        }
    }
}

// TunerScreen reads only the slow-changing flows (tuning, string selection, tuned set).
// It never reads `state` — that belongs exclusively to NoteDisplay, which recomposes
// at audio rate (~43 Hz) without disturbing the tuning selector or string buttons.
@Composable
fun TunerScreen(tunerViewModel: TunerViewModel = viewModel()) {
    val selectedTuning by tunerViewModel.selectedTuning.collectAsStateWithLifecycle()
    val selectedString by tunerViewModel.selectedString.collectAsStateWithLifecycle()
    val tunedStrings by tunerViewModel.tunedStrings.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
        )
    }
    var isListening by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (granted) {
            tunerViewModel.startListening()
            isListening = true
        }
    }

    DisposableEffect(Unit) {
        onDispose { tunerViewModel.stopListening() }
    }

    // Stable lambdas: captured value (tunerViewModel) never changes for the
    // lifetime of this composition, so remember ensures the same object is
    // passed on every recompose — allowing Compose to skip child recompositions.
    val onTuningSelected: (Tuning) -> Unit = remember { { tunerViewModel.selectTuning(it) } }
    val onStringSelected: (Int) -> Unit = remember { { tunerViewModel.selectString(it) } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "Guitar Tuner",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 16.dp)
        )

        TuningSelector(
            selectedTuning = selectedTuning,
            onTuningSelected = onTuningSelected
        )

        StringSelector(
            tuning = selectedTuning,
            selectedString = selectedString,
            tunedStrings = tunedStrings,
            onStringSelected = onStringSelected
        )

        // Only this composable recomposes at audio rate
        NoteDisplay(
            tunerViewModel = tunerViewModel,
            isListening = isListening
        )

        Button(
            onClick = {
                if (!isListening) {
                    if (hasPermission) {
                        tunerViewModel.startListening()
                        isListening = true
                    } else {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                } else {
                    tunerViewModel.stopListening()
                    isListening = false
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isListening) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary
            )
        ) {
            Text(
                text = if (isListening) "Stop" else "Start Tuner",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

// Isolated fast-recompose zone. Only this subtree redraws at ~43 Hz.
@Composable
fun NoteDisplay(tunerViewModel: TunerViewModel, isListening: Boolean) {
    val state by tunerViewModel.state.collectAsStateWithLifecycle()

    val inTune = state.isDetected && abs(state.cents) <= 10f
    val noteColor by animateColorAsState(
        targetValue = when {
            !state.isDetected -> MaterialTheme.colorScheme.outline
            inTune -> Color(0xFF4CAF50)
            else -> MaterialTheme.colorScheme.primary
        },
        animationSpec = tween(200),
        label = "noteColor"
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(200.dp)
                .clip(CircleShape)
                .background(noteColor.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (state.isDetected) state.noteName else "--",
                    fontSize = 72.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = noteColor,
                    textAlign = TextAlign.Center
                )
                if (state.isDetected) {
                    Text(
                        text = state.octave.toString(),
                        fontSize = 24.sp,
                        color = noteColor.copy(alpha = 0.7f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = if (state.isDetected) "%.1f Hz".format(state.frequency) else "",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        CentsMeter(cents = if (state.isDetected) state.cents else 0f, isDetected = state.isDetected)

        Spacer(modifier = Modifier.height(16.dp))

        if (state.isDetected) {
            Text(
                text = when {
                    inTune -> "IN TUNE"
                    state.cents > 0 -> "SHARP +%.0f¢".format(state.cents)
                    else -> "FLAT %.0f¢".format(state.cents)
                },
                style = MaterialTheme.typography.labelLarge,
                color = if (inTune) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        } else {
            Text(
                text = if (isListening) "Listening..." else "Press Start to begin",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TuningSelector(
    selectedTuning: Tuning,
    onTuningSelected: (Tuning) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val notesLabel = remember(selectedTuning) {
        selectedTuning.strings.joinToString(" ") { it.displayName }
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = selectedTuning.displayName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Tuning") },
            supportingText = { Text(notesLabel, fontStyle = FontStyle.Italic) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            enumValues<Tuning>().forEach { tuning ->
                val itemNotes = remember(tuning) {
                    tuning.strings.joinToString(" ") { it.displayName }
                }
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(tuning.displayName, fontWeight = FontWeight.Medium)
                            Text(
                                itemNotes,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    onClick = {
                        onTuningSelected(tuning)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
            }
        }
    }
}

@Composable
fun StringSelector(
    tuning: Tuning,
    selectedString: Int?,
    tunedStrings: Set<Int>,
    onStringSelected: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        tuning.strings.forEachIndexed { index, stringTarget ->
            val isTuned = index in tunedStrings
            val isSelected = selectedString == index
            val containerColor by animateColorAsState(
                targetValue = when {
                    isTuned -> Color(0xFF4CAF50)
                    isSelected -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
                animationSpec = tween(300),
                label = "stringButton_$index"
            )
            val contentColor = when {
                isTuned || isSelected -> Color.White
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Button(
                onClick = { onStringSelected(index) },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = containerColor,
                    contentColor = contentColor
                )
            ) {
                Text(
                    text = stringTarget.displayName,
                    fontSize = 16.sp,
                    fontWeight = if (isSelected || isTuned) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
fun CentsMeter(cents: Float, isDetected: Boolean) {
    val animatedCents by animateFloatAsState(
        targetValue = if (isDetected) cents.coerceIn(-50f, 50f) else 0f,
        animationSpec = tween(100),
        label = "cents"
    )
    val inTune = isDetected && abs(cents) <= 10f
    val needleColor by animateColorAsState(
        targetValue = if (inTune) Color(0xFF4CAF50) else Color(0xFFFF9800),
        animationSpec = tween(200),
        label = "needleColor"
    )

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
            .padding(horizontal = 16.dp)
    ) {
        drawMeter(animatedCents, needleColor, isDetected)
    }
}

private fun DrawScope.drawMeter(cents: Float, needleColor: Color, isDetected: Boolean) {
    val width = size.width
    val height = size.height
    val centerX = width / 2f
    val centerY = height * 0.85f

    drawLine(
        color = Color.Gray.copy(alpha = 0.3f),
        start = Offset(0f, centerY),
        end = Offset(width, centerY),
        strokeWidth = 4.dp.toPx(),
        cap = StrokeCap.Round
    )

    val zoneWidth = width * 0.1f
    drawLine(
        color = Color(0xFF4CAF50).copy(alpha = 0.4f),
        start = Offset(centerX - zoneWidth, centerY),
        end = Offset(centerX + zoneWidth, centerY),
        strokeWidth = 8.dp.toPx(),
        cap = StrokeCap.Round
    )

    for (i in -5..5) {
        val x = centerX + (i / 5f) * (width / 2f)
        val tickHeight = if (i == 0) height * 0.5f else height * 0.25f
        drawLine(
            color = Color.Gray.copy(alpha = 0.5f),
            start = Offset(x, centerY - tickHeight),
            end = Offset(x, centerY),
            strokeWidth = if (i == 0) 3.dp.toPx() else 1.5.dp.toPx(),
            cap = StrokeCap.Round
        )
    }

    if (isDetected) {
        val needleX = centerX + (cents / 50f) * (width / 2f)
        drawLine(
            color = needleColor,
            start = Offset(needleX, centerY - height * 0.75f),
            end = Offset(needleX, centerY + height * 0.1f),
            strokeWidth = 3.dp.toPx(),
            cap = StrokeCap.Round
        )
        drawCircle(
            color = needleColor,
            radius = 6.dp.toPx(),
            center = Offset(needleX, centerY - height * 0.75f)
        )
    }
}
