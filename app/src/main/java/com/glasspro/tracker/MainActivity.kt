package com.glasspro.tracker

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glasspro.tracker.ui.screens.MainScreen
import com.glasspro.tracker.ui.theme.GlassProTheme
import com.glasspro.tracker.ui.viewmodel.MarketViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MarketViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as GlassProApplication
        setContent {
            GlassProTheme {
                CrashLogDialogIfPresent(app)
                MainScreen(viewModel = viewModel)
            }
        }
    }
}

/**
 * If the previous launch crashed, shows the persisted stack trace so the
 * failure can be copied and shared without a computer.
 */
@Composable
private fun CrashLogDialogIfPresent(app: GlassProApplication) {
    val context = LocalContext.current
    var crashLog by remember { mutableStateOf(app.readCrashLog()) }

    if (crashLog != null) {
        AlertDialog(
            onDismissRequest = {
                app.clearCrashLog()
                crashLog = null
            },
            title = { Text("Uygulama çöktü", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = "Bir önceki açılışta uygulama kapandı. Aşağıdaki hata raporunu " +
                            "kopyalayıp geliştiriciyle paylaşabilirsin:",
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = crashLog!!,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("GlassPro crash", crashLog))
                    Toast.makeText(context, "Hata raporu kopyalandı", Toast.LENGTH_SHORT).show()
                }) {
                    Text("Kopyala")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    app.clearCrashLog()
                    crashLog = null
                }) {
                    Text("Devam Et")
                }
            }
        )
    }
}
