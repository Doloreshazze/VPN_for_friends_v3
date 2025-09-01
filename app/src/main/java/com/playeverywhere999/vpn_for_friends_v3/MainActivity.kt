package com.playeverywhere999.vpn_for_friends_v3

import com.playeverywhere999.vpn_for_friends_v3.util.EventObserver // Убедитесь, что этот util.EventObserver существует
import android.Manifest
import android.app.Activity
import android.app.AlertDialog
// import android.content.Intent // Не используется напрямую, если все через ViewModel/Service
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.playeverywhere999.vpn_for_friends_v3.ui.theme.VPN_for_friends_v3Theme
import com.wireguard.android.backend.Tunnel


class MainActivity : ComponentActivity() {

    private val vpnViewModel: VpnViewModel by viewModels()

    private val requestVpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                Log.d("MainActivity", "VPN permission granted by user. Checking notification permission next.")
                // VPN разрешение получено, теперь проверяем/запрашиваем разрешение на уведомления.
                checkAndRequestPostNotificationPermissionThenConnect()
            } else {
                Log.w("MainActivity", "VPN permission denied by user.")
                Toast.makeText(this, "VPN permission was denied.", Toast.LENGTH_LONG).show()
                vpnViewModel.setExternalVpnState(Tunnel.State.DOWN, "VPN permission denied by user.")
            }
        }

    private val requestPostNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Log.d("MainActivity", "POST_NOTIFICATIONS permission granted by user. Triggering ViewModel's connect logic.")
            // Разрешение получено, сообщаем ViewModel, чтобы она продолжила/повторила попытку подключения
            vpnViewModel.onConnectDisconnectClicked()
        } else {
            Log.w("MainActivity", "POST_NOTIFICATIONS permission denied by user after request.")
            Toast.makeText(this, "VPN cannot start without notification permission.", Toast.LENGTH_LONG).show()
            vpnViewModel.setExternalVpnState(Tunnel.State.DOWN, "Notification permission denied, VPN cannot start.")
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Наблюдение за состоянием из сервиса и обновление ViewModel
        MyWgVpnService.tunnelStatus.observe(this) { state ->
            Log.d("MainActivity", "Service tunnelStatus changed to: $state")
            val currentError = MyWgVpnService.vpnError.value
            vpnViewModel.setExternalVpnState(state, currentError)
        }

        MyWgVpnService.vpnError.observe(this) { errorMessage ->
            // Реагируем, только если есть новая ошибка, чтобы не перезаписывать состояние без нужды,
            // если ошибка пришла, а состояние уже DOWN.
            if (errorMessage != null) {
                Log.d("MainActivity", "Service vpnError observed: $errorMessage")
                // Получаем самое актуальное состояние, если оно есть, иначе считаем DOWN
                val currentState = MyWgVpnService.tunnelStatus.value ?: vpnViewModel.vpnState.value ?: Tunnel.State.DOWN
                vpnViewModel.setExternalVpnState(currentState, errorMessage)
            }
        }

        MyWgVpnService.serviceIsRunning.observe(this) { isRunning ->
            Log.d("MainActivity", "MyWgVpnService.serviceIsRunning: $isRunning")
            if (!isRunning) {
                val vmState = vpnViewModel.vpnState.value
                // Если ViewModel думает, что VPN работает или подключается, а сервис уже не запущен,
                // то сбрасываем состояние в DOWN.
                if (vmState == Tunnel.State.UP || vmState == Tunnel.State.TOGGLE) {
                    Log.w("MainActivity", "Service is not running, but ViewModel state is $vmState. Resetting to DOWN.")
                    vpnViewModel.setExternalVpnState(Tunnel.State.DOWN, "VPN service stopped unexpectedly.")
                }
            }
        }

        // --- НАБЛЮДЕНИЕ ЗА СОБЫТИЕМ ЗАПРОСА РАЗРЕШЕНИЙ ОТ VIEWMODEL ---
        vpnViewModel.requestPermissionsEvent.observe(this, EventObserver {
            Log.d("MainActivity", "Received requestPermissionsEvent from ViewModel.")
            requestVpnAndNotificationPermissionsMain() // Вызываем метод для запроса разрешений
        })
        // --- КОНЕЦ НАБЛЮДЕНИЯ ---

        setContent {
            VPN_for_friends_v3Theme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainScreen(viewModel = vpnViewModel) // Используем UI из MainScreen.kt
                }
            }
        }
    }

    /**
     * Этот метод инициирует процесс запроса необходимых разрешений (VPN, затем Уведомления).
     * Он вызывается, когда ViewModel сигнализирует о необходимости разрешений.
     */
    private fun requestVpnAndNotificationPermissionsMain() {
        val vpnIntentPermission = VpnService.prepare(this)
        if (vpnIntentPermission != null) {
            Log.d("MainActivity", "Requesting VPN permission (triggered by ViewModel or initial check).")
            requestVpnPermissionLauncher.launch(vpnIntentPermission)
        } else {
            Log.d("MainActivity", "VPN permission already granted. Checking Notifications permission next.")
            checkAndRequestPostNotificationPermissionThenConnect()
        }
    }

    /**
     * Этот метод проверяет и запрашивает разрешение на POST_NOTIFICATIONS (для Android 13+)
     * ПОСЛЕ того, как разрешение на VPN уже было предоставлено.
     * Если все разрешения есть, он "пинает" ViewModel для продолжения.
     */
    private fun checkAndRequestPostNotificationPermissionThenConnect() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    Log.d("MainActivity", "POST_NOTIFICATIONS permission already granted. Triggering ViewModel's connect logic.")
                    vpnViewModel.onConnectDisconnectClicked()
                }
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    Log.i("MainActivity", "Showing rationale for POST_NOTIFICATIONS.")
                    AlertDialog.Builder(this)
                        .setTitle("Notification Permission Needed")
                        .setMessage("To ensure stable VPN operation and display its status, the app needs permission to show notifications. Without it, the VPN service cannot be started correctly.")
                        .setPositiveButton("Grant") { _, _ ->
                            requestPostNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        .setNegativeButton("Cancel") { dialog, _ ->
                            dialog.dismiss()
                            Toast.makeText(this, "Notification permission not granted. VPN will not start.", Toast.LENGTH_LONG).show()
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
            Log.d("MainActivity", "OS version < TIRAMISU, no need for POST_NOTIFICATIONS permission. Triggering ViewModel's connect logic.")
            vpnViewModel.onConnectDisconnectClicked()
        }
    }
}

