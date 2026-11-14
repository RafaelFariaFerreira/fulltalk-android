package dev.fulltalk.app.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.fulltalk.app.R
import dev.fulltalk.app.bubble.BubbleService
import dev.fulltalk.app.container
import dev.fulltalk.app.model.ModelState
import dev.fulltalk.core.History

private val Violet = Color(0xFF5B3FE4)
private val Background = Color(0xFFF7F6FC)
private val Ink = Color(0xFF1B1A2E)
private val Muted = Color(0xFF6B6A80)
private val Border = Color(0xFFE8E6F3)
private val Danger = Color(0xFFE5484D)

/** Setup, settings and the recent transcriptions. Day to day, the bubble is the app. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Violet,
                    background = Background,
                    surface = Color.White,
                    onBackground = Ink,
                    onSurface = Ink,
                ),
            ) {
                Screen()
            }
        }
    }
}

@Composable
private fun Screen() {
    val context = LocalContext.current
    val app = context.container
    val model by app.model.state.collectAsStateWithLifecycle()
    val autoPaste by app.preferences.autoPaste.collectAsStateWithLifecycle()
    val history by app.preferences.history.collectAsStateWithLifecycle()

    // Both can change while the user is away in the system settings.
    var microphone by remember { mutableStateOf(context.hasMicrophone()) }
    var bubble by remember { mutableStateOf(context.isBubbleEnabled()) }
    LifecycleResumeEffect(Unit) {
        microphone = context.hasMicrophone()
        bubble = context.isBubbleEnabled()
        onPauseOrDispose { }
    }
    val askMicrophone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        microphone = it
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Header()

        Step(
            number = 1,
            title = "Modelo de voz",
            done = model == ModelState.Ready,
            description = when (val state = model) {
                ModelState.Ready -> "Instalado. A transcrição roda neste aparelho, sem internet."
                is ModelState.Downloading ->
                    "Baixando… ${state.done / 1_000_000} de ${state.total / 1_000_000} MB"
                is ModelState.Failed -> "Não deu certo: ${state.reason}"
                ModelState.Missing -> "Um download único de 670 MB. Depois disso nada sai do aparelho."
            },
            danger = model is ModelState.Failed,
        ) {
            when (val state = model) {
                is ModelState.Downloading -> LinearProgressIndicator(
                    progress = { state.done.toFloat() / state.total },
                    modifier = Modifier.fillMaxWidth(),
                )
                ModelState.Missing -> Action("Baixar") { app.model.download() }
                is ModelState.Failed -> Action("Tentar de novo") { app.model.download() }
                ModelState.Ready -> Unit
            }
        }

        Step(
            number = 2,
            title = "Microfone",
            done = microphone,
            description = if (microphone) {
                "Liberado. Ele só é aberto enquanto você dita."
            } else {
                "Para ouvir o que você dita. Ele só é aberto enquanto a bolinha está roxa."
            },
        ) {
            if (!microphone) Action("Permitir") { askMicrophone.launch(Manifest.permission.RECORD_AUDIO) }
        }

        Step(
            number = 3,
            title = "Bolinha flutuante",
            done = bubble,
            description = if (bubble) {
                "Ativa. Toque nela para falar, ou segure enquanto fala. Dois toques abrem esta tela."
            } else {
                "Ative o fulltalk dev em Acessibilidade. É o que deixa a bolinha ficar sobre os outros apps " +
                    "e colar o texto no campo em foco. Nada da tela é lido ou guardado."
            },
        ) {
            if (!bubble) {
                Action("Abrir Acessibilidade") {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }
        }

        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Colar automaticamente", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text(
                        "Cola no campo de texto em foco. Sem campo em foco, só copia.",
                        color = Muted,
                        fontSize = 13.sp,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = autoPaste, onCheckedChange = app.preferences::setAutoPaste)
            }
        }

        Recent(history)

        Text(
            stringResource(R.string.slogan),
            color = Muted,
            fontSize = 12.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Header() {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
        Waveform()
        Spacer(Modifier.width(10.dp))
        Text("fulltalk", fontWeight = FontWeight.SemiBold, fontSize = 24.sp, color = Ink)
        Spacer(Modifier.width(6.dp))
        Text("dev", fontSize = 24.sp, color = Violet)
    }
}

/** The logo mark: five bars of a waveform. */
@Composable
private fun Waveform() {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        for (height in listOf(10, 18, 28, 18, 10)) {
            Box(
                Modifier
                    .size(width = 4.dp, height = height.dp)
                    .clip(CircleShape)
                    .background(Violet),
            )
        }
    }
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun Step(
    number: Int,
    title: String,
    done: Boolean,
    description: String,
    danger: Boolean = false,
    action: @Composable () -> Unit,
) {
    Panel {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(if (done) Violet else Border),
                ) {
                    Text(
                        if (done) "✓" else number.toString(),
                        color = if (done) Color.White else Muted,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            }
            Text(description, color = if (danger) Danger else Muted, fontSize = 13.sp, lineHeight = 18.sp)
            action()
        }
    }
}

@Composable
private fun Action(label: String, onClick: () -> Unit) {
    Button(onClick = onClick, shape = RoundedCornerShape(12.dp)) { Text(label) }
}

@Composable
private fun Recent(history: History) {
    val context = LocalContext.current
    Panel {
        Column {
            Text("Últimas transcrições", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Spacer(Modifier.height(6.dp))
            if (history.entries.isEmpty()) {
                Text(
                    "Suas 5 últimas transcrições aparecem aqui. Toque em uma para copiar.",
                    color = Muted,
                    fontSize = 13.sp,
                )
            }
            for (entry in history.entries) {
                Text(
                    entry.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { context.copy(entry.text) }
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                )
            }
        }
    }
}

private fun Context.copy(text: String) {
    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("fulltalk dev", text))
}

private fun Context.hasMicrophone(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

/** Whether the user turned the floating microphone on in the system's accessibility settings. */
private fun Context.isBubbleEnabled(): Boolean {
    val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
    val mine = ComponentName(this, BubbleService::class.java)
    return enabled.orEmpty().split(':').any { ComponentName.unflattenFromString(it) == mine }
}
