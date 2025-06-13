package com.playeverywhere999.vpn_for_friends_v3

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
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
import androidx.core.content.ContextCompat
import com.playeverywhere999.vpn_for_friends_v3.ui.theme.VPN_for_friends_v3Theme
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config // Импорт для Config
import java.io.IOException // Для обработки исключения от toWgQuickString()

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
            checkAndRequestPostNotificationPermissionThenConnect()
        } else {
            Log.w("MainActivity", "VPN permission denied.")
            Toast.makeText(this, "VPN permission was denied.", Toast.LENGTH_LONG).show()
            vpnViewModel.setExternalVpnState(Tunnel.State.DOWN, "VPN permission denied by user.")
        }
    }

    private val requestPostNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Log.d("MainActivity", "POST_NOTIFICATIONS permission granted.")
            startVpnServiceConnectAction()
        } else {
            Log.w("MainActivity", "POST_NOTIFICATIONS permission denied by user after request.")
            Toast.makeText(this, "VPN не может быть запущен без разрешения на уведомления.", Toast.LENGTH_LONG).show()
            vpnViewModel.setExternalVpnState(Tunnel.State.DOWN, "Notification permission denied, VPN cannot start.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
            Log.d("MainActivity", "Intent to CONNECT. Effective decision state: $effectiveStateForDecision, Error: $currentErrorMessage")
            if (currentErrorMessage != null) {
                vpnViewModel.clearLastError()
            }

            val preparedConfigObject: Config? = vpnViewModel.preparedConfig.value
            if (preparedConfigObject == null) {
                Log.e("MainActivity", "Configuration not prepared. Please load configuration first.")
                Toast.makeText(this, "Configuration not loaded. Please Load/Reload first.", Toast.LENGTH_LONG).show()
                vpnViewModel.setExternalVpnState(Tunnel.State.DOWN, "Configuration not loaded. Load/Reload first.")
                return
            }

            val vpnIntentPermission = VpnService.prepare(this)
            if (vpnIntentPermission != null) {
                Log.d("MainActivity", "Requesting VPN permission.")
                requestVpnPermissionLauncher.launch(vpnIntentPermission)
            } else {
                Log.d("MainActivity", "VPN permission already granted.")
                checkAndRequestPostNotificationPermissionThenConnect()
            }
        } else {
            Log.d("MainActivity", "Intent to DISCONNECT. Effective decision state: $effectiveStateForDecision")
            Intent(this, MyWgVpnService::class.java).also {
                it.action = MyWgVpnService.ACTION_DISCONNECT // Используем ACTION_DISCONNECT из MyWgVpnService
                startService(it)
            }
            vpnViewModel.setIntermediateVpnState(Tunnel.State.TOGGLE)
        }
    }

    private fun checkAndRequestPostNotificationPermissionThenConnect() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    Log.d("MainActivity", "POST_NOTIFICATIONS permission already granted. Starting service.")
                    startVpnServiceConnectAction()
                }
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    Log.i("MainActivity", "Showing rationale for POST_NOTIFICATIONS.")
                    AlertDialog.Builder(this)
                        .setTitle("Требуется разрешение на уведомления")
                        .setMessage("Для стабильной работы VPN и отображения его статуса приложению необходимо разрешение на показ уведомлений. Без него VPN-сервис не сможет быть запущен корректно.")
                        .setPositiveButton("Предоставить") { _, _ ->
                            requestPostNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        .setNegativeButton("Отмена") { dialog, _ ->
                            dialog.dismiss()
                            Toast.makeText(this, "Разрешение на уведомления не предоставлено. VPN не будет запущен.", Toast.LENGTH_LONG).show()
                            vpnViewModel.setExternalVpnState(Tunnel.State.DOWN, "Notification permission rationale declined, VPN not started.")
                        }
                        .setCancelable(false)
                        .show()
                }
                else -> {
                    Log.d("MainActivity", "Requesting POST_NOTIFICATIONS permission.")
                    requestPostNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } else {
            Log.d("MainActivity", "OS version < TIRAMISU, no need for POST_NOTIFICATIONS permission. Starting service.")
            startVpnServiceConnectAction()
        }
    }

    // Вынесенный метод для запуска сервиса с действием CONNECT
    private fun startVpnServiceConnectAction() {
        Log.d("MainActivity", "Preparing to start service to connect.")
        val preparedConfigObject: Config? = vpnViewModel.preparedConfig.value

        if (preparedConfigObject == null) {
            Log.e("MainActivity", "startVpnServiceConnectAction: Configuration not prepared. Cannot start VPN service.")
            Toast.makeText(this, "Configuration is missing. Please Load/Reload first.", Toast.LENGTH_LONG).show()
            vpnViewModel.setExternalVpnState(Tunnel.State.DOWN, "Configuration missing, cannot start VPN.")
            return // Не запускаем сервис, если нет конфигурации
        }

        try {
            // Преобразуем объект Config в строку
            // Убедитесь, что метод toWgQuickString() существует в вашей библиотеке wireguard-android
            // и корректно обрабатывается. Он может выбрасывать IOException.
            val configString = preparedConfigObject.toWgQuickString()

            Log.d("MainActivity", "Starting service with ACTION_CONNECT and config string.")
            val serviceIntent = Intent(this, MyWgVpnService::class.java).apply {
                action = MyWgVpnService.ACTION_CONNECT // Используем ACTION_CONNECT из MyWgVpnService
                putExtra(MyWgVpnService.EXTRA_WG_CONFIG_STRING, configString) // Передаем строку конфигурации
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            vpnViewModel.setIntermediateVpnState(Tunnel.State.TOGGLE) // Устанавливаем TOGGLE при начале подключения
        } catch (e: IOException) {
            Log.e("MainActivity", "Error converting configuration to string: ${e.message}", e)
            Toast.makeText(this, "Error preparing configuration for VPN: ${e.message}", Toast.LENGTH_LONG).show()
            vpnViewModel.setExternalVpnState(Tunnel.State.DOWN, "Error preparing configuration: ${e.message}")
        } catch (e: Exception) { // Ловим другие возможные исключения от toWgQuickString или при работе с Intent
            Log.e("MainActivity", "Unexpected error preparing to start VPN: ${e.message}", e)
            Toast.makeText(this, "Unexpected error: ${e.message}", Toast.LENGTH_LONG).show()
            vpnViewModel.setExternalVpnState(Tunnel.State.DOWN, "Unexpected error: ${e.message}")
        }
    }


    private fun einfacheZuDisplayState(
        currentViewModelState: Tunnel.State,
        errorMessage: String?,
        lastKnownStableState: Tunnel.State
    ): VpnDisplayState {
        Log.d("DisplayLogic", "currentVMState: $currentViewModelState, error: $errorMessage, lastKnownStable: $lastKnownStableState")

        if (!errorMessage.isNullOrEmpty()) {
            return VpnDisplayState.ERROR
        }

        return when (currentViewModelState) {
            Tunnel.State.DOWN -> VpnDisplayState.IDLE
            Tunnel.State.UP -> VpnDisplayState.CONNECTED
            Tunnel.State.TOGGLE -> {
                if (lastKnownStableState == Tunnel.State.DOWN) {
                    VpnDisplayState.CONNECTING
                } else {
                    VpnDisplayState.DISCONNECTING
                }
            }
        }
    }
}

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
                    VpnDisplayState.ERROR -> "Retry Connect" // или "Connect", если хотите всегда начинать с "чистого" подключения
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