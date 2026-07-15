package nz.co.mixport.customsvision

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nz.co.mixport.customsvision.data.AppLanguage
import nz.co.mixport.customsvision.data.AppPreferencesRepository
import nz.co.mixport.customsvision.scanner.PdaHardwareKeyDispatcher
import nz.co.mixport.customsvision.ui.AppViewModel
import nz.co.mixport.customsvision.ui.AppViewModelFactory
import nz.co.mixport.customsvision.ui.CustomsApp
import nz.co.mixport.customsvision.ui.pick
import nz.co.mixport.customsvision.ui.theme.MixportCustomsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(STARTUP_TAG, "MainActivity.onCreate start at ${SystemClock.elapsedRealtime()} ms")
        enableEdgeToEdge()
        maybePinAppToHomeScreen()

        val app = application as CustomsApplication

        setContent {
            MixportCustomsTheme {
                MainActivityContent(app = app)
            }
        }
        Log.i(STARTUP_TAG, "MainActivity.setContent scheduled at ${SystemClock.elapsedRealtime()} ms")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if ((event?.repeatCount ?: 0) == 0 && PdaHardwareKeyDispatcher.dispatchKeyDown(keyCode)) {
            Log.i(
                STARTUP_TAG,
                "Consumed hardware FDA key down ${PdaHardwareKeyDispatcher.describeKey(keyCode)}",
            )
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (PdaHardwareKeyDispatcher.dispatchKeyUp(keyCode)) {
            Log.i(
                STARTUP_TAG,
                "Consumed hardware FDA key up ${PdaHardwareKeyDispatcher.describeKey(keyCode)}",
            )
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun maybePinAppToHomeScreen() {
        val preferences = getSharedPreferences(HOME_SHORTCUT_PREFS, Context.MODE_PRIVATE)
        if (preferences.getBoolean(KEY_HOME_SHORTCUT_REQUESTED, false)) {
            return
        }

        val launchIntent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        }

        val requested = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> requestPinnedShortcut(launchIntent)
            else -> sendLegacyInstallShortcutBroadcast(launchIntent)
        }
        if (requested) {
            preferences.edit().putBoolean(KEY_HOME_SHORTCUT_REQUESTED, true).apply()
        }
    }

    private fun requestPinnedShortcut(launchIntent: Intent): Boolean {
        val shortcutManager = getSystemService(ShortcutManager::class.java) ?: return false
        if (!shortcutManager.isRequestPinShortcutSupported) {
            return sendLegacyInstallShortcutBroadcast(launchIntent)
        }
        val shortcut = ShortcutInfo.Builder(this, HOME_SHORTCUT_ID)
            .setShortLabel(getString(R.string.app_name))
            .setLongLabel(getString(R.string.app_name))
            .setIcon(Icon.createWithResource(this, R.mipmap.ic_launcher))
            .setIntent(launchIntent)
            .build()
        return runCatching {
            shortcutManager.requestPinShortcut(shortcut, null)
        }.getOrElse { throwable ->
            Log.w(STARTUP_TAG, "Unable to request pinned launcher shortcut", throwable)
            false
        }.let { requested ->
            if (requested) {
                true
            } else {
                Log.i(STARTUP_TAG, "ShortcutManager requestPinShortcut returned false, falling back to legacy broadcast")
                sendLegacyInstallShortcutBroadcast(launchIntent)
            }
        }
    }

    private fun sendLegacyInstallShortcutBroadcast(launchIntent: Intent): Boolean {
        return runCatching {
            sendBroadcast(
                Intent("com.android.launcher.action.INSTALL_SHORTCUT").apply {
                    putExtra(Intent.EXTRA_SHORTCUT_NAME, getString(R.string.app_name))
                    putExtra(Intent.EXTRA_SHORTCUT_INTENT, launchIntent)
                    putExtra("duplicate", false)
                    putExtra(
                        Intent.EXTRA_SHORTCUT_ICON_RESOURCE,
                        Intent.ShortcutIconResource.fromContext(this@MainActivity, R.mipmap.ic_launcher),
                    )
                },
            )
            true
        }.getOrElse { throwable ->
            Log.w(STARTUP_TAG, "Unable to send legacy launcher shortcut broadcast", throwable)
            false
        }
    }
}

private sealed interface StartupUiState {
    data object Loading : StartupUiState

    data class Ready(
        val payload: AppBootstrapPayload,
    ) : StartupUiState

    data class Failed(
        val message: String,
    ) : StartupUiState
}

@Composable
private fun MainActivityContent(app: CustomsApplication) {
    var retryToken by remember { mutableIntStateOf(0) }
    var startupState by remember(app, retryToken) { mutableStateOf<StartupUiState>(StartupUiState.Loading) }
    val startupLanguage = remember(app, retryToken) {
        AppPreferencesRepository(app).getLanguage()
    }

    LaunchedEffect(app, retryToken) {
        startupState = StartupUiState.Loading
        val startedAt = SystemClock.elapsedRealtime()
        withFrameNanos { }
        startupState = runCatching {
            withContext(Dispatchers.IO) {
                app.loadBootstrapPayload()
            }
        }.fold(
            onSuccess = StartupUiState::Ready,
            onFailure = { throwable ->
                StartupUiState.Failed(
                    throwable.message ?: startupLanguage.pick(
                        "Unable to start Mixport Customs Vision.",
                        "无法启动 Mixport Customs Vision。",
                    ),
                )
            },
        )
        Log.i(
            STARTUP_TAG,
            "Bootstrap completed in ${SystemClock.elapsedRealtime() - startedAt} ms with state=${startupState.javaClass.simpleName}",
        )
    }

    when (val state = startupState) {
        StartupUiState.Loading -> StartupLoadingScreen(language = startupLanguage)
        is StartupUiState.Failed -> StartupFailureScreen(
            language = startupLanguage,
            message = state.message,
            onRetry = { retryToken++ },
        )

        is StartupUiState.Ready -> {
            val viewModel: AppViewModel = viewModel(
                factory = AppViewModelFactory(
                    repository = state.payload.repository,
                    preferencesRepository = state.payload.preferencesRepository,
                    startupSnapshot = state.payload.startupSnapshot,
                ),
            )
            CustomsApp(viewModel = viewModel)
        }
    }
}

private const val STARTUP_TAG = "MixportStartup"
private const val HOME_SHORTCUT_PREFS = "mixport_home_shortcut"
private const val KEY_HOME_SHORTCUT_REQUESTED = "home_shortcut_requested"
private const val HOME_SHORTCUT_ID = "mixport_customs_home"

@Composable
private fun StartupLoadingScreen(language: AppLanguage) {
    StartupShell {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary)
        Text(
            text = language.pick(
                "Preparing Mixport pilot workspace...",
                "正在准备 Mixport 试点作业环境...",
            ),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

@Composable
private fun StartupFailureScreen(
    language: AppLanguage,
    message: String,
    onRetry: () -> Unit,
) {
    StartupShell {
        Text(
            text = "Startup failed",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onPrimary,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.88f),
        )
        Button(
            onClick = onRetry,
            shape = RoundedCornerShape(999.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.14f),
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Text(
                text = language.pick("Retry", "重试"),
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun StartupShell(content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.92f),
                    ),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.Start,
            content = content,
        )
    }
}
