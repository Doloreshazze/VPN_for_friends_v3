package com.playeverywhere999.vpn_for_friends_v3 // или com.playeverywhere999.vpn_for_friends_v3.ui

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.wireguard.android.backend.Tunnel // Используем Tunnel.State
// и вашу тему
// import com.playeverywhere999.vpn_for_friends_v3.ui.theme.VPN_for_friends_v3Theme


@Composable
fun MainScreen(
    viewModel: VpnViewModel,
    modifier: Modifier = Modifier,
) {
    val currentVpnState by viewModel.vpnState.observeAsState(Tunnel.State.DOWN)
    val lastErrorMessage by viewModel.lastErrorMessage.observeAsState()
    val configStatusMessage by viewModel.configStatusMessage.observeAsState()
    // val preparedConfig by viewModel.preparedConfig.observeAsState() // Раскомментируйте, если нужен для логики кнопки

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "VPN Status: ${currentVpnState.name}",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // Отображение сообщений о процессе или статусе VPN
            val displayConfigMessage = configStatusMessage?.takeIf {
                // Не показываем "Processing..." если уже есть ошибка, чтобы ошибка была виднее
                !(it.equals("Processing...", ignoreCase = true) && !lastErrorMessage.isNullOrEmpty())
            }

            displayConfigMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (message.startsWith("Error", ignoreCase = true)) MaterialTheme.colorScheme.error else LocalContentColor.current,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            lastErrorMessage?.let { error ->
                Text(
                    text = "Error: $error",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Button(onClick = { viewModel.clearLastError() }) { // Кнопка для очистки ошибки
                    Text("Clear Message")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Основная кнопка, управляемая VpnViewModel
            Button(
                onClick = {
                    Log.d("MainScreen", "Connect/Disconnect button clicked. Current state: ${currentVpnState.name}")
                    viewModel.onConnectDisconnectClicked()
                },
                modifier = Modifier.fillMaxWidth(0.8f),
                // Кнопка активна, если состояние не TOGGLE, ИЛИ если состояние TOGGLE, но есть ошибка (позволяет Retry)
                enabled = currentVpnState != Tunnel.State.TOGGLE || !lastErrorMessage.isNullOrEmpty()
            ) {
                when (currentVpnState) {
                    Tunnel.State.DOWN -> {
                        if (configStatusMessage?.contains("Generating", ignoreCase = true) == true ||
                            configStatusMessage?.contains("Contacting server", ignoreCase = true) == true) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Getting Config...")
                                Spacer(Modifier.width(8.dp))
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            }
                        } else if (lastErrorMessage != null) {
                            Text("Retry") // Если была ошибка, кнопка становится "Retry"
                        }
                        else {
                            Text("Connect")
                        }
                    }
                    Tunnel.State.UP -> Text("Disconnect")
                    Tunnel.State.TOGGLE -> {
                        // Если TOGGLE и есть ошибка, показываем "Retry", иначе "Processing"
                        if (!lastErrorMessage.isNullOrEmpty()) {
                            Text("Retry")
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Processing...")
                                Spacer(Modifier.width(8.dp))
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            }
                        }
                    }
                }
            }
        }
    }
}

// Превью потребуют создания Mock/Fake VpnViewModel или передачи всех состояний как параметров.
// Для простоты, вот базовые превью.
// В реальном проекте лучше использовать библиотеку для мокирования или создать Fake реализацию VpnViewModel.

@Preview(showBackground = true, name = "MainScreen DOWN")
@Composable
fun MainScreenPreviewDown() {
    // VPN_for_friends_v3Theme { // Оберните в вашу тему, если она не применяется глобально
    // Для превью нужен FakeVpnViewModel или передача параметров
    // Этот пример не будет полностью рабочим без него, но показывает структуру.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("VPN Status: DOWN", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))
        Button(onClick = {}) { Text("Connect") }
    }
    // }
}

@Preview(showBackground = true, name = "MainScreen UP")
@Composable
fun MainScreenPreviewUp() {
    // VPN_for_friends_v3Theme {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("VPN Status: UP", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))
        Button(onClick = {}) { Text("Disconnect") }
    }
    // }
}

@Preview(showBackground = true, name = "MainScreen TOGGLE (Processing)")
@Composable
fun MainScreenPreviewToggle() {
    // VPN_for_friends_v3Theme {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("VPN Status: TOGGLE", style = MaterialTheme.typography.headlineMedium)
        Text("Processing...", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(24.dp))
        Button(onClick = {}, enabled = false) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Processing...")
                Spacer(Modifier.width(8.dp))
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
    }
    // }
}

@Preview(showBackground = true, name = "MainScreen DOWN with Error")
@Composable
fun MainScreenPreviewDownWithError() {
    // VPN_for_friends_v3Theme {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("VPN Status: DOWN", style = MaterialTheme.typography.headlineMedium)
        Text("Error: Sample Error Message", color = Color.Red, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        Button(onClick = {}) { Text("Clear Message")}
        Spacer(Modifier.height(24.dp))
        Button(onClick = {}) { Text("Retry") }
    }
    // }
}

