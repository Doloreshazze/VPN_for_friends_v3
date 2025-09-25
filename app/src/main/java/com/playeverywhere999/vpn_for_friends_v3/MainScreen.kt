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
    isEnabled: Boolean, 
    size: Dp = 20.dp, 
    modifier: Modifier = Modifier // Allow passing custom modifiers, e.g., for alignment
) {
    val indicatorColor = if (!isEnabled) {
        Color.Gray
    } else {
        when (vpnState) {
            Tunnel.State.UP -> Color.Green
            Tunnel.State.DOWN -> Color.Red 
            else -> Color.Gray 
        }
    }

    Box(
        modifier = modifier // Apply passed modifier here
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
                enabled = isButtonEnabled,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp) 
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    // contentAlignment = Alignment.Center // Default for Box, children can override
                ) {
                    val textToShow:
                    @Composable
                    () -> Unit
                    val indicatorToShow:
                    @Composable
                    ((Modifier) -> Unit)? // Takes a Modifier for alignment

                    if (lastErrorMessage != null) {
                        textToShow = { Text(stringResource(id = R.string.button_retry)) }
                        indicatorToShow = { modifier -> ConnectionStatusIndicator(vpnState = Tunnel.State.DOWN, isEnabled = isButtonEnabled, modifier = modifier) }
                    } else if (currentVpnState == Tunnel.State.UP) {
                        textToShow = { Text(stringResource(id = R.string.button_disconnect)) }
                        indicatorToShow = { modifier -> ConnectionStatusIndicator(vpnState = Tunnel.State.UP, isEnabled = isButtonEnabled, modifier = modifier) }
                    } else {
                        textToShow = { Text(stringResource(id = R.string.button_connect)) }
                        indicatorToShow = { modifier -> ConnectionStatusIndicator(vpnState = Tunnel.State.DOWN, isEnabled = isButtonEnabled, modifier = modifier) }
                    }

                    // Text centered in the Box
                    Box(modifier = Modifier.align(Alignment.Center)) {
                        textToShow()
                    }
                    // Indicator aligned to the end of the Box
                    indicatorToShow?.invoke(Modifier.align(Alignment.CenterEnd))
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
            Button(onClick = {}, enabled = true, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Box(modifier = Modifier.align(Alignment.Center)) { Text(stringResource(R.string.button_connect)) }
                    ConnectionStatusIndicator(vpnState = Tunnel.State.DOWN, isEnabled = true, modifier = Modifier.align(Alignment.CenterEnd))
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
            Button(onClick = {}, enabled = true, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                 Box(modifier = Modifier.fillMaxWidth()) {
                    Box(modifier = Modifier.align(Alignment.Center)) { Text(stringResource(R.string.button_disconnect)) }
                    ConnectionStatusIndicator(vpnState = Tunnel.State.UP, isEnabled = true, modifier = Modifier.align(Alignment.CenterEnd))
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
            Button(onClick = {}, enabled = false, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) { 
                 Box(modifier = Modifier.fillMaxWidth()) {
                    Box(modifier = Modifier.align(Alignment.Center)) { Text(stringResource(R.string.button_connect)) }
                    ConnectionStatusIndicator(vpnState = Tunnel.State.DOWN, isEnabled = false, modifier = Modifier.align(Alignment.CenterEnd))
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
            Button(onClick = {}, enabled = true, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) { 
                Box(modifier = Modifier.fillMaxWidth()) {
                    Box(modifier = Modifier.align(Alignment.Center)) { Text(stringResource(R.string.button_retry)) }
                    ConnectionStatusIndicator(vpnState = Tunnel.State.DOWN, isEnabled = true, modifier = Modifier.align(Alignment.CenterEnd))
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
            Button(onClick = {}, enabled = false, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) { 
                Box(modifier = Modifier.fillMaxWidth()) {
                    Box(modifier = Modifier.align(Alignment.Center)) { Text(stringResource(R.string.button_connect)) }
                    ConnectionStatusIndicator(vpnState = Tunnel.State.DOWN, isEnabled = false, modifier = Modifier.align(Alignment.CenterEnd))
                }
            }
        }
    }
}
