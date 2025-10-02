package com.playeverywhere999.vpn_for_friends_v3

import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wireguard.android.backend.Tunnel

@Composable
private fun ConnectionStatusIndicator(
    vpnState: Tunnel.State,
    isEnabled: Boolean, // isEnabled here refers to the button's enabled state
    size: Dp = 20.dp,
    modifier: Modifier = Modifier
) {
    val indicatorColor = if (!isEnabled) {
        if (vpnState == Tunnel.State.TOGGLE) Color.Yellow else Color.Gray
    } else {
        when (vpnState) {
            Tunnel.State.UP -> Color.Green
            Tunnel.State.DOWN -> Color.Red
            Tunnel.State.TOGGLE -> Color.Yellow 
            else -> Color.Gray 
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
    lastError: String?,
    configStatusMessage: String? // Pass the full configStatusMessage
) {
    val message: String?
    val color: Color

    val preparingConfigStr = stringResource(R.string.status_preparing_config)
    val connectingStr = stringResource(R.string.status_connecting)
    val processingStr = stringResource(R.string.status_processing)
    
    val currentStatus = configStatusMessage // Local capture for stable smart cast

    when {
        !lastError.isNullOrEmpty() -> {
            message = lastError 
            color = MaterialTheme.colorScheme.error
        }
        // Avoid showing messages that are now on the button itself
        currentStatus == preparingConfigStr || 
        currentStatus == connectingStr || 
        currentStatus == processingStr ||
        currentStatus?.contains("Generating client keys...", ignoreCase = true) == true ||
        currentStatus?.contains("Client keys generated. Contacting server...", ignoreCase = true) == true ||
        currentStatus?.contains("Retrying config fetch", ignoreCase = true) == true ||
        currentStatus?.contains("Configuration received. Preparing tunnel...", ignoreCase = true) == true -> {
            message = null
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MainScreen(
    viewModel: VpnViewModel,
    modifier: Modifier = Modifier,
) {
    val currentVpnState by viewModel.vpnState.observeAsState(Tunnel.State.DOWN)
    val lastErrorMessage by viewModel.lastErrorMessage.observeAsState()
    val configStatusMessage by viewModel.configStatusMessage.observeAsState() // Type will be String?

    // Define strings needed for logic based on configStatusMessage
    val preparingConfigStr = stringResource(R.string.status_preparing_config)
    val processingStr = stringResource(R.string.status_processing) 
    val connectingStr = stringResource(R.string.status_connecting) 

    val configCurrentlyInProgress = remember(configStatusMessage, preparingConfigStr, processingStr) {
        val currentStatus = configStatusMessage // Local variable for stable smart cast
        currentStatus != null && (
            currentStatus.equals(preparingConfigStr, ignoreCase = true) ||
            currentStatus.contains("Generating client keys...", ignoreCase = true) || 
            currentStatus.contains("Client keys generated. Contacting server...", ignoreCase = true) || 
            currentStatus.contains("Retrying config fetch", ignoreCase = true) || 
            currentStatus.contains("Connection failed, VPN service is retrying...", ignoreCase = true) ||
            currentStatus.contains("Configuration received. Preparing tunnel...", ignoreCase = true) ||
            currentStatus.equals(processingStr, ignoreCase = true) 
        )
    }

    val isButtonEnabled = !(
        (currentVpnState == Tunnel.State.TOGGLE && lastErrorMessage.isNullOrEmpty()) ||
        (currentVpnState == Tunnel.State.DOWN && lastErrorMessage.isNullOrEmpty() && configCurrentlyInProgress)
    )

    MainScreenUI(
        modifier = modifier,
        currentVpnState = currentVpnState,
        lastErrorMessage = lastErrorMessage,
        configStatusMessage = configStatusMessage, // Pass the String? value
        isButtonEnabled = isButtonEnabled,
        preparingConfigStr = preparingConfigStr,
        connectingStr = connectingStr,
        onConnectDisconnectClicked = {
            Log.d("MainScreen", "Connect/Disconnect button clicked. Current state: ${currentVpnState.name}, Enabled: $isButtonEnabled, ConfigStatus: $configStatusMessage, Error: $lastErrorMessage")
            if (isButtonEnabled) {
                viewModel.onConnectDisconnectClicked()
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MainScreenUI(
    modifier: Modifier = Modifier,
    currentVpnState: Tunnel.State,
    lastErrorMessage: String?,
    configStatusMessage: String?, // Expects String?
    isButtonEnabled: Boolean,
    preparingConfigStr: String, 
    connectingStr: String, 
    onConnectDisconnectClicked: () -> Unit
) {
    Box(modifier = modifier.fillMaxSize()) {
        Image(
            painter = painterResource(id = R.drawable.background),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        Image(
            painter = painterResource(id = R.drawable.shield),
            contentDescription = "Shield Icon",
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxSize(),
            contentScale = ContentScale.Fit
        )

        Surface(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
            color = Color.Transparent
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    StatusMessageDisplay(
                        lastError = lastErrorMessage,
                        configStatusMessage = configStatusMessage // Pass the String? value
                    )
                }

                Button(
                    onClick = onConnectDisconnectClicked,
                    modifier = Modifier.fillMaxWidth(0.8f), // Standard height
                    enabled = isButtonEnabled,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFBBBBBB),      // Medium gray for enabled background
                        contentColor = Color.Black,                // Black for enabled content (text)
                        disabledContainerColor = Color(0xFF888888), // Darker gray for disabled background
                        disabledContentColor = Color(0xFFBBBBBB)     // Medium gray for disabled content (text)
                    )
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        val textToShow: @Composable () -> Unit
                        val visualVpnStateForIndicator: Tunnel.State

                        val isConnectingDisplayState = !isButtonEnabled && lastErrorMessage.isNullOrEmpty()
                        val currentStatus = configStatusMessage // Local capture for stable smart cast

                        if (isConnectingDisplayState) {
                            if (currentStatus == preparingConfigStr) { // Use local capture
                                textToShow = { Text(preparingConfigStr) } 
                            } else {
                                textToShow = { Text(connectingStr) } 
                            }
                            visualVpnStateForIndicator = Tunnel.State.TOGGLE 
                        } else if (lastErrorMessage != null) {
                            textToShow = { Text(stringResource(id = R.string.button_retry)) }
                            visualVpnStateForIndicator = Tunnel.State.DOWN 
                        } else if (currentVpnState == Tunnel.State.UP) {
                            textToShow = { Text(stringResource(id = R.string.button_disconnect)) }
                            visualVpnStateForIndicator = Tunnel.State.UP 
                        } else {
                            textToShow = { Text(stringResource(id = R.string.button_connect)) }
                            visualVpnStateForIndicator = Tunnel.State.DOWN 
                        }

                        Box(modifier = Modifier.align(Alignment.Center)) {
                            textToShow()
                        }
                        ConnectionStatusIndicator(
                            vpnState = visualVpnStateForIndicator,
                            isEnabled = isButtonEnabled,
                            modifier = Modifier.align(Alignment.CenterEnd)
                        )
                    }
                }
            }
        }
    }
}

// --- Preview Functions Updated --- 

@OptIn(ExperimentalLayoutApi::class)
@Preview(showBackground = true, name = "MainScreen DOWN (Idle)")
@Composable
fun MainScreenPreviewDownIdle() {
    MaterialTheme {
        val context = LocalContext.current
        MainScreenUI(
            currentVpnState = Tunnel.State.DOWN,
            lastErrorMessage = null,
            configStatusMessage = null,
            isButtonEnabled = true,
            preparingConfigStr = context.getString(R.string.status_preparing_config),
            connectingStr = context.getString(R.string.status_connecting),
            onConnectDisconnectClicked = {}
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Preview(showBackground = true, name = "MainScreen UP")
@Composable
fun MainScreenPreviewUp() {
    MaterialTheme {
        val context = LocalContext.current
        MainScreenUI(
            currentVpnState = Tunnel.State.UP,
            lastErrorMessage = null,
            configStatusMessage = "VPN Connected", // Example status
            isButtonEnabled = true,
            preparingConfigStr = context.getString(R.string.status_preparing_config),
            connectingStr = context.getString(R.string.status_connecting),
            onConnectDisconnectClicked = {}
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Preview(showBackground = true, name = "MainScreen TOGGLE (Connecting via Processing)")
@Composable
fun MainScreenPreviewToggleConnectingProcessing() {
    MaterialTheme {
        val context = LocalContext.current
        MainScreenUI(
            currentVpnState = Tunnel.State.TOGGLE, 
            lastErrorMessage = null,
            configStatusMessage = context.getString(R.string.status_processing), 
            isButtonEnabled = false, 
            preparingConfigStr = context.getString(R.string.status_preparing_config),
            connectingStr = context.getString(R.string.status_connecting),
            onConnectDisconnectClicked = {}
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Preview(showBackground = true, name = "MainScreen DOWN (Preparing Config)")
@Composable
fun MainScreenPreviewDownPreparingConfig() {
    MaterialTheme {
        val context = LocalContext.current
        MainScreenUI(
            currentVpnState = Tunnel.State.DOWN,
            lastErrorMessage = null,
            configStatusMessage = context.getString(R.string.status_preparing_config), 
            isButtonEnabled = false, 
            preparingConfigStr = context.getString(R.string.status_preparing_config),
            connectingStr = context.getString(R.string.status_connecting),
            onConnectDisconnectClicked = {}
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Preview(showBackground = true, name = "MainScreen DOWN with Error")
@Composable
fun MainScreenPreviewDownWithError() {
    MaterialTheme {
        val context = LocalContext.current
        MainScreenUI(
            currentVpnState = Tunnel.State.DOWN,
            lastErrorMessage = "Sample Detailed Error Message",
            configStatusMessage = "Error: Config fetch failed",
            isButtonEnabled = true, 
            preparingConfigStr = context.getString(R.string.status_preparing_config),
            connectingStr = context.getString(R.string.status_connecting),
            onConnectDisconnectClicked = {}
        )
    }
}

