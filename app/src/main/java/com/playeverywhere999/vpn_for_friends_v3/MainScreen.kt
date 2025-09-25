package com.playeverywhere999.vpn_for_friends_v3 // или com.playeverywhere999.vpn_for_friends_v3.ui

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wireguard.android.backend.Tunnel // Используем Tunnel.State
// и вашу тему
// import com.playeverywhere999.vpn_for_friends_v3.ui.theme.VPN_for_friends_v3Theme

// Helper function to determine if a configuration/connection process is active
private fun isConfigInProgress(configStatus: String?): Boolean {
    return configStatus?.contains("Generating", ignoreCase = true) == true ||
           configStatus?.contains("Contacting server", ignoreCase = true) == true ||
           configStatus?.contains("Retrying config fetch", ignoreCase = true) == true ||
           configStatus?.contains("Connection failed, VPN service is retrying...", ignoreCase = true) == true ||
           configStatus?.contains("Preparing tunnel", ignoreCase = true) == true ||
           configStatus?.contains("Configuration ready", ignoreCase = true) == true ||
           configStatus?.contains("Processing...", ignoreCase = true) == true
}

@Composable
private fun ConnectionStatusIndicator(
    vpnState: Tunnel.State, 
    isEnabled: Boolean, // New parameter to control color when disabled
    size: Dp = 20.dp, 
    modifier: Modifier = Modifier
) {
    val indicatorColor = if (!isEnabled) {
        Color.Gray
    } else {
        when (vpnState) {
            Tunnel.State.UP -> Color.Green
            Tunnel.State.DOWN -> Color.Red // Used for Connect (default/down) and Retry states
            else -> Color.Gray // Default to gray if state is unexpected for an enabled indicator
        }
    }

    Box(
        modifier = modifier
            .size(size) 
            .background(indicatorColor, CircleShape)
    )
}

@Composable
private fun StatusMessageDisplay(
    vpnState: Tunnel.State,
    lastError: String?,
    configStatus: String?
) {
    val message: String?
    val color: Color
    val configInProgress = isConfigInProgress(configStatus)

    when {
        !lastError.isNullOrEmpty() -> {
            message = stringResource(id = R.string.status_error)
            color = MaterialTheme.colorScheme.error
        }
        vpnState == Tunnel.State.UP -> {
            message = null 
            color = LocalContentColor.current
        }
        vpnState == Tunnel.State.TOGGLE || (vpnState == Tunnel.State.DOWN && configInProgress && lastError.isNullOrEmpty()) -> {
            message = stringResource(id = R.string.status_connecting) 
            color = LocalContentColor.current
        }
        else -> {
            message = null 
            color = LocalContentColor.current
        }
    }

    message?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodyMedium,
            color = color,
            modifier = Modifier.padding(bottom = 8.dp)
        )
    }
}

@Composable
fun MainScreen(
    viewModel: VpnViewModel,
    modifier: Modifier = Modifier,
) {
    val currentVpnState by viewModel.vpnState.observeAsState(Tunnel.State.DOWN)
    val lastErrorMessage by viewModel.lastErrorMessage.observeAsState()
    val configStatusMessage by viewModel.configStatusMessage.observeAsState()

    val configCurrentlyInProgress = isConfigInProgress(configStatusMessage)

    val isButtonEnabled = !(
        (currentVpnState == Tunnel.State.TOGGLE && lastErrorMessage.isNullOrEmpty()) ||
        (currentVpnState == Tunnel.State.DOWN && lastErrorMessage.isNullOrEmpty() && configCurrentlyInProgress)
    )

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            StatusMessageDisplay(
                vpnState = currentVpnState,
                lastError = lastErrorMessage,
                configStatus = configStatusMessage
            )
            
            val showStatusMessage = lastErrorMessage != null || 
                                   (currentVpnState == Tunnel.State.TOGGLE && lastErrorMessage == null) ||
                                   (currentVpnState == Tunnel.State.DOWN && configCurrentlyInProgress && lastErrorMessage == null)
            if (showStatusMessage) {
                 Spacer(modifier = Modifier.height(16.dp))
            } else {
                 Spacer(modifier = Modifier.height(48.dp)) 
            }

            Button(
                onClick = {
                    Log.d("MainScreen", "Connect/Disconnect button clicked. Current state: ${currentVpnState.name}, Enabled: $isButtonEnabled")
                    if (isButtonEnabled) { 
                        viewModel.onConnectDisconnectClicked()
                    }
                },
                modifier = Modifier.fillMaxWidth(0.8f),
                enabled = isButtonEnabled
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    if (lastErrorMessage != null) {
                        Text(stringResource(id = R.string.button_retry))
                        Spacer(Modifier.width(8.dp))
                        ConnectionStatusIndicator(vpnState = Tunnel.State.DOWN, isEnabled = isButtonEnabled) // isButtonEnabled will be true here
                    } else if (currentVpnState == Tunnel.State.UP) {
                        Text(stringResource(id = R.string.button_disconnect))
                        Spacer(Modifier.width(8.dp))
                        ConnectionStatusIndicator(vpnState = Tunnel.State.UP, isEnabled = isButtonEnabled) // isButtonEnabled will be true here
                    } else {
                        Text(stringResource(id = R.string.button_connect))
                        Spacer(Modifier.width(8.dp))
                        ConnectionStatusIndicator(vpnState = Tunnel.State.DOWN, isEnabled = isButtonEnabled) // Color changes if button is disabled
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, name = "MainScreen DOWN (Idle)")
@Composable
fun MainScreenPreviewDownIdle() {
    MaterialTheme {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
        ) {
            StatusMessageDisplay(vpnState = Tunnel.State.DOWN, lastError = null, configStatus = null)
            Spacer(modifier = Modifier.height(48.dp))
            Button(onClick = {}, enabled = true) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) { 
                    Text(stringResource(R.string.button_connect)) 
                    Spacer(Modifier.width(8.dp))
                    ConnectionStatusIndicator(vpnState = Tunnel.State.DOWN, isEnabled = true)
                }
            }
        }
    }
}

@Preview(showBackground = true, name = "MainScreen UP")
@Composable
fun MainScreenPreviewUp() {
    MaterialTheme {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
        ) {
            StatusMessageDisplay(vpnState = Tunnel.State.UP, lastError = null, configStatus = null) 
            Spacer(modifier = Modifier.height(48.dp)) 
            Button(onClick = {}, enabled = true) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) { 
                    Text(stringResource(R.string.button_disconnect)) 
                    Spacer(Modifier.width(8.dp))
                    ConnectionStatusIndicator(vpnState = Tunnel.State.UP, isEnabled = true)
                }
            }
        }
    }
}

@Preview(showBackground = true, name = "MainScreen TOGGLE (Connecting)")
@Composable
fun MainScreenPreviewToggleConnecting() {
    MaterialTheme {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
        ) {
            StatusMessageDisplay(vpnState = Tunnel.State.TOGGLE, lastError = null, configStatus = "Contacting server...")
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = {}, enabled = false) { 
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Text(stringResource(R.string.button_connect))
                    Spacer(Modifier.width(8.dp))
                    ConnectionStatusIndicator(vpnState = Tunnel.State.DOWN, isEnabled = false) // Indicator will be gray
                }
            }
        }
    }
}

@Preview(showBackground = true, name = "MainScreen DOWN with Error")
@Composable
fun MainScreenPreviewDownWithError() {
    MaterialTheme {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
        ) {
            StatusMessageDisplay(vpnState = Tunnel.State.DOWN, lastError = "Sample Error", configStatus = "Error: Something bad")
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = {}, enabled = true) { 
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Text(stringResource(R.string.button_retry)) 
                    Spacer(Modifier.width(8.dp))
                    ConnectionStatusIndicator(vpnState = Tunnel.State.DOWN, isEnabled = true) // Red for Retry
                }
            }
        }
    }
}

@Preview(showBackground = true, name = "MainScreen DOWN (Config In Progress)")
@Composable
fun MainScreenPreviewDownConfigInProgress() {
    MaterialTheme {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
        ) {
            StatusMessageDisplay(vpnState = Tunnel.State.DOWN, lastError = null, configStatus = "Generating keypair...")
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = {}, enabled = false) { 
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Text(stringResource(R.string.button_connect))
                    Spacer(Modifier.width(8.dp))
                    ConnectionStatusIndicator(vpnState = Tunnel.State.DOWN, isEnabled = false) // Indicator will be gray
                }
            }
        }
    }
}
