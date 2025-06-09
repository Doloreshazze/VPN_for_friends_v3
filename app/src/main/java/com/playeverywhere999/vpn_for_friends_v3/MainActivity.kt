package com.playeverywhere999.vpn_for_friends_v3

import android.Manifest // <-- проверка связи
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager // <-- Добавлен импорт
import android.net.VpnService
import android.os.Build // <-- Добавлен импорт
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat // <-- Добавлен импорт
import androidx.lifecycle.observe
// import androidx.lifecycle.observe // Этот импорт не используется напрямую, можно удалить если нет других использований
import com.playeverywhere999.vpn_for_friends_v3.ui.theme.VPN_for_friends_v3Theme
import com.wireguard.android.backend.Tunnel

enum class VpnDisplayState {
    IDLE, CONNECTING, CONNECTED, DISCONNECTING, ERROR
}

class MainActivity : ComponentActivity() {

    private val vpnViewModel: VpnViewModel by viewModels()
    private var previousStableBackendState: Tunnel.State = Tunnel.State.DOWN

    private val requestVpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Log.d("MainActivity", "VPN permission granted.")
            // После получения разрешения VPN, проверяем разрешение на уведомления (если нужно)
            // и затем запускаем сервис
            checkAndRequestPostNotificationPermissionThenConnect()
        } else {
            Log.w("MainActivity", "VPN permission denied.")
            Toast.makeText(this, "VPN permission was denied.", Toast.LENGTH_LONG).show()
            vpnViewModel.setExternalVpnState(Tunnel.State.DOWN, "VPN permission denied by user.")
        }
    }

    // Лаунчер для запроса разрешения POST_NOTIFICATIONS
    private val requestPostNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Log.d("MainActivity", "POST_NOTIFICATIONS permission granted.")
            // Разрешение получено, теперь можно запускать сервис для подключения
            startVpnServiceConnectAction()
        } else {
            Log.w("MainActivity", "POST_NOTIFICATIONS permission denied.")
            Toast.makeText(this, "Notification permission denied. VPN notifications might not be shown.", Toast.LENGTH_LONG).show()
            // Продолжаем подключение VPN, но уведомления могут не отображаться на Android 13+
            // или сервис может столкнуться с проблемами при вызове startForeground
            // В идеале, нужно предупредить пользователя о важности этого разрешения для стабильной работы.
            // Для VPN сервис должен вызвать startForeground, иначе система его убьет.
            // Если startForeground не сможет показать уведомление, это может привести к ForegroundServiceStartNotAllowedException
            // Поэтому, хотя мы и продолжаем, это потенциально проблемное состояние.
            // Рассмотрим вариант не стартовать VPN или показать более настойчивое предупреждение.
            // Пока что просто продолжаем, но это место для улучшения UX.
            startVpnServiceConnectAction() // Пытаемся запустить, но с возможными проблемами
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Подписки на LiveData из MyWgVpnService остаются без изменений
        MyWgVpnService.tunnelStatus.observe(this) { state ->
            Log.d("MainActivity", "Service tunnelStatus changed: $state")
            val currentError = MyWgVpnService.vpnError.value
            vpnViewModel.setExternalVpnState(state, currentError)
            if (currentError != null) {
                MyWgVpnService.vpnError.postValue(null)
            }
        }

        MyWgVpnService.vpnError.observe(this) { errorMessage ->
            if (errorMessage != null) {
                Log.e("MainActivity", "Service vpnError reported: $errorMessage")
                val currentState = MyWgVpnService.tunnelStatus.value ?: vpnViewModel.vpnState.value ?: Tunnel.State.DOWN
                vpnViewModel.setExternalVpnState(currentState, errorMessage)
                MyWgVpnService.vpnError.postValue(null)
            }
        }

        MyWgVpnService.serviceIsRunning.observe(this) { isRunning ->
            Log.d("MainActivity", "MyWgVpnService.serviceIsRunning: $isRunning")
            if (!isRunning) {
                val vmState = vpnViewModel.vpnState.value
                if (vmState == Tunnel.State.UP || vmState == Tunnel.State.TOGGLE) {
                    Log.w("MainActivity", "Service is not running, but ViewModel state is $vmState. Resetting to DOWN.")
                    vpnViewModel.setExternalVpnState(Tunnel.State.DOWN, "VPN service stopped unexpectedly.")
                }
            }
        }

        setContent {
            VPN_for_friends_v3Theme {
                val currentVpnStateFromViewModel = vpnViewModel.vpnState.observeAsState().value ?: Tunnel.State.DOWN
                val lastErrorMessageFromViewModel by vpnViewModel.lastErrorMessage.observeAsState()

                if (currentVpnStateFromViewModel == Tunnel.State.UP || currentVpnStateFromViewModel == Tunnel.State.DOWN) {
                    if (previousStableBackendState != currentVpnStateFromViewModel) {
                        Log.d("MainActivity", "Updating previousStableBackendState from $previousStableBackendState to $currentVpnStateFromViewModel")
                        previousStableBackendState = currentVpnStateFromViewModel
                    }
                }
                val stateForDisplayLogic = if (currentVpnStateFromViewModel == Tunnel.State.TOGGLE) {
                    previousStableBackendState
                } else {
                    currentVpnStateFromViewModel
                }

                val currentDisplayState = einfacheZuDisplayState(
                    currentVpnStateFromViewModel,
                    lastErrorMessageFromViewModel,
                    stateForDisplayLogic
                )

                VpnControlScreen(
                    displayState = currentDisplayState,
                    errorMessage = lastErrorMessageFromViewModel,
                    onConnectClick = {
                        initiateVpnConnectionOrToggle()
                    },
                    onPrepareConfigClick = {
                        Log.d("MainActivity", "Prepare config button clicked.")
                        vpnViewModel.createAndPrepareConfig()
                        Toast.makeText(this, "Configuration prepared/reloaded.", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }

    private fun initiateVpnConnectionOrToggle() {
        val currentVpnState = vpnViewModel.vpnState.value ?: Tunnel.State.DOWN
        val effectiveStateForDecision = if (currentVpnState == Tunnel.State.TOGGLE) {
            previousStableBackendState
        } else {
            currentVpnState
        }
        val currentErrorMessage = vpnViewModel.lastErrorMessage.value
        val shouldConnect = effectiveStateForDecision == Tunnel.State.DOWN || currentErrorMessage != null

        if (shouldConnect) {
            Log.d("MainActivity", "Intent to CONNECT. Current VM state: $currentVpnState, Prev stable: $previousStableBackendState")
            val vpnIntentPermission = VpnService.prepare(this)
            if (vpnIntentPermission != null) {
                Log.d("MainActivity", "Requesting VPN permission.")
                requestVpnPermissionLauncher.launch(vpnIntentPermission)
            } else {
                Log.d("MainActivity", "VPN permission already granted.")
                // Разрешение VPN есть, теперь проверяем разрешение на уведомления (если нужно)
                // и затем запускаем сервис
                checkAndRequestPostNotificationPermissionThenConnect()
            }
        } else {
            Log.d("MainActivity", "Intent to DISCONNECT. Current VM state: $currentVpnState, Prev stable: $previousStableBackendState")
            Intent(this, MyWgVpnService::class.java).also {
                it.action = MyWgVpnService.ACTION_DISCONNECT
                startService(it) // Для disconnect startForegroundService не нужен, просто startService
            }
            vpnViewModel.setIntermediateVpnState(Tunnel.State.TOGGLE)
        }
    }

    // Новый метод для проверки и запроса разрешения на уведомления ПЕРЕД запуском сервиса для подключения
    private fun checkAndRequestPostNotificationPermissionThenConnect() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13 (API 33) и выше
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    // Разрешение уже есть
                    Log.d("MainActivity", "POST_NOTIFICATIONS permission already granted. Starting service.")
                    startVpnServiceConnectAction()
                }
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    // Пользователь ранее отказал. Показываем объяснение (опционально, но рекомендуется).
                    // Здесь можно показать диалог с объяснением, зачем нужно разрешение.
                    // После диалога снова запросить.
                    // Для простоты пока просто запросим снова.
                    Log.i("MainActivity", "Showing rationale for POST_NOTIFICATIONS and requesting again.")
                    // TODO: Показать диалог с объяснением
                    requestPostNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                else -> {
                    // Запрашиваем разрешение в первый раз или если пользователь выбрал "Больше не спрашивать"
                    // (в последнем случае лаунчер просто не сработает)
                    Log.d("MainActivity", "Requesting POST_NOTIFICATIONS permission.")
                    requestPostNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } else {
            // Для версий ниже Android 13 разрешение не требуется
            Log.d("MainActivity", "OS version < TIRAMISU, no need for POST_NOTIFICATIONS permission. Starting service.")
            startVpnServiceConnectAction()
        }
    }

    // Вынесенный метод для запуска сервиса с действием CONNECT
    private fun startVpnServiceConnectAction() {
        Log.d("MainActivity", "Starting service to connect.")
        Intent(this, MyWgVpnService::class.java).also {
            it.action = MyWgVpnService.ACTION_CONNECT
            // Используем startForegroundService для Android O и выше, если сервис планирует вызывать startForeground
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(it)
            } else {
                startService(it)
            }
        }
        vpnViewModel.setIntermediateVpnState(Tunnel.State.TOGGLE)
    }


    private fun einfacheZuDisplayState(
        currentViewModelState: Tunnel.State,
        errorMessage: String?,
        lastKnownStableState: Tunnel.State
    ): VpnDisplayState {
        // Логика без изменений
        Log.d("DisplayLogic", "currentVMState: $currentViewModelState, error: $errorMessage, lastKnownStable: $lastKnownStableState")
        if (!errorMessage.isNullOrEmpty()) {
            return VpnDisplayState.ERROR
        }
        return when (currentViewModelState) {
            Tunnel.State.DOWN -> VpnDisplayState.IDLE
            Tunnel.State.TOGGLE -> {
                if (lastKnownStableState == Tunnel.State.DOWN) {
                    VpnDisplayState.CONNECTING
                } else {
                    VpnDisplayState.DISCONNECTING
                }
            }
            Tunnel.State.UP -> VpnDisplayState.CONNECTED
        }
    }
}

// VpnControlScreen и превью остаются без изменений
@Composable
fun VpnControlScreen(
    displayState: VpnDisplayState,
    errorMessage: String?,
    onConnectClick: () -> Unit,
    onPrepareConfigClick: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "VPN Status: ${displayState.name}",
                style = MaterialTheme.typography.headlineSmall
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (displayState == VpnDisplayState.ERROR && errorMessage != null) {
                Text(
                    text = "Error: $errorMessage",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            Button(onClick = onPrepareConfigClick) {
                Text("Load/Reload Configuration")
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onConnectClick,
                enabled = displayState != VpnDisplayState.CONNECTING && displayState != VpnDisplayState.DISCONNECTING
            ) {
                val buttonText = when (displayState) {
                    VpnDisplayState.IDLE -> "Connect"
                    VpnDisplayState.CONNECTING -> "Connecting..."
                    VpnDisplayState.CONNECTED -> "Disconnect"
                    VpnDisplayState.DISCONNECTING -> "Disconnecting..."
                    VpnDisplayState.ERROR -> "Retry Connect"
                }
                Text(buttonText)
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun DefaultPreviewMainActivity() {
    VPN_for_friends_v3Theme {
        VpnControlScreen(
            displayState = VpnDisplayState.IDLE,
            errorMessage = null,
            onConnectClick = {},
            onPrepareConfigClick = {}
        )
    }
}