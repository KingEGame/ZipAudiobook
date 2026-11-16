package com.kingwar.zipbook

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kingwar.zipbook.ui.LibraryViewModel
import com.kingwar.zipbook.ui.ZipBookApp

class MainActivity : ComponentActivity() {

    /** A ZIP handed to the app via VIEW/SEND, imported once the UI is up. */
    private var pendingArchive: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingArchive = extractArchiveUri(intent)

        setContent {
            ZipBookTheme {
                Surface {
                    val vm: LibraryViewModel = viewModel()
                    val context = LocalContext.current

                    // Android 13+ hides the playback notification entirely unless this
                    // is granted at runtime — the manifest entry alone does nothing.
                    // Without it the user sees no notification/shade controls even
                    // though the service is genuinely playing in the background.
                    val notificationPermission = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission(),
                    ) {}

                    LaunchedEffect(Unit) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            val granted = ContextCompat.checkSelfPermission(
                                context,
                                android.Manifest.permission.POST_NOTIFICATIONS,
                            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                            if (!granted) {
                                notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }

                        // Without this exemption Doze can suspend the app process
                        // (and with it the in-process playback service) minutes after
                        // the screen turns off, which is the "stops in the background"
                        // complaint for a long-running audiobook player.
                        val powerManager = context.getSystemService(PowerManager::class.java)
                        if (powerManager != null && !powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
                            runCatching {
                                startActivity(
                                    Intent(
                                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                        Uri.parse("package:${context.packageName}"),
                                    ),
                                )
                            }
                        }
                    }

                    // SAF picker. "application/zip" alone misses archives that file
                    // providers report with a vendor MIME type or none at all, so the
                    // filter is widened and the parser rejects non-archives instead.
                    val picker = rememberLauncherForActivityResult(
                        ActivityResultContracts.OpenDocument(),
                    ) { uri: Uri? ->
                        if (uri != null) {
                            runCatching {
                                contentResolver.takePersistableUriPermission(
                                    uri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                                )
                            }
                            vm.importArchive(uri)
                        }
                    }

                    LaunchedEffect(Unit) {
                        pendingArchive?.let { uri ->
                            pendingArchive = null
                            vm.importArchive(uri)
                        }
                    }

                    ZipBookApp(
                        viewModel = vm,
                        onPickArchive = {
                            picker.launch(
                                arrayOf(
                                    "application/zip",
                                    "application/x-zip-compressed",
                                    "multipart/x-zip",
                                    "application/octet-stream",
                                ),
                            )
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        extractArchiveUri(intent)?.let { pendingArchive = it }
    }

    private fun extractArchiveUri(intent: Intent?): Uri? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
        else -> null
    }
}

@Composable
private fun ZipBookTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = BrandDarkColors, content = content)
}

/** The warm, fixed dark palette the UI mockup is designed around. */
private val BrandDarkColors = darkColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFFFFCC33),
    onPrimary = androidx.compose.ui.graphics.Color(0xFF1A1200),
    primaryContainer = androidx.compose.ui.graphics.Color(0xFF4A3A00),
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFFFFE08A),
    secondary = androidx.compose.ui.graphics.Color(0xFFFF7A45),
    onSecondary = androidx.compose.ui.graphics.Color(0xFF2A0E00),
    background = androidx.compose.ui.graphics.Color(0xFF0F1012),
    onBackground = androidx.compose.ui.graphics.Color(0xFFF2F3F5),
    surface = androidx.compose.ui.graphics.Color(0xFF17181C),
    onSurface = androidx.compose.ui.graphics.Color(0xFFF2F3F5),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFF24262E),
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF9A9EA8),
    outline = androidx.compose.ui.graphics.Color(0xFF2A2C34),
    outlineVariant = androidx.compose.ui.graphics.Color(0xFF2A2C34),
    error = androidx.compose.ui.graphics.Color(0xFFFF6B6B),
)
