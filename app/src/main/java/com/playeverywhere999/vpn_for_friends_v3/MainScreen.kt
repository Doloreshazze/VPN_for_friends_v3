package com.playeverywhere999.vpn_for_friends_v3

import android.widget.Toast
import androidx.compose.foundation.Image
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.playeverywhere999.vpn_for_friends_v3.ui.theme.VPN_for_friends_v3Theme

@Composable
fun MainScreen(
    viewModel: VpnViewModel,
    onScanQrClicked: () -> Unit
) {
    val vpnState by viewModel.vpnState.observeAsState(VpnTunnelState.DOWN)
    val preparedConfig by viewModel.preparedConfig.observeAsState()
    val lastError by viewModel.lastErrorMessage.observeAsState()
    val context = LocalContext.current
    val configStatus by viewModel.configStatusMessage.observeAsState()


    configStatus?.let {
        Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
    }

    MainScreenUI(
        vpnState = vpnState,
        hasConfig = preparedConfig != null,
        lastError = lastError,
        onConnectDisconnect = { viewModel.onConnectDisconnectClicked() },
        onImportConfig = { viewModel.importConfigFromClipboard() },
        onScanQrClicked = onScanQrClicked
    )
}

@Composable
private fun MainScreenUI(
    vpnState: VpnTunnelState,
    hasConfig: Boolean,
    lastError: String?,
    onConnectDisconnect: () -> Unit,
    onImportConfig: () -> Unit,
    onScanQrClicked: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
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
            modifier = Modifier.fillMaxSize(),
            color = Color.Transparent
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (!lastError.isNullOrBlank()) {
                    Text(
                        text = lastError,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                }

                // Основная кнопка Connect/Disconnect
                Button(
                    onClick = onConnectDisconnect,
                    enabled = hasConfig && vpnState != VpnTunnelState.TOGGLE,
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .height(80.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFBBBBBB),
                        contentColor = Color.Black,
                        disabledContainerColor = Color(0xFF888888),
                        disabledContentColor = Color(0xFFBBBBBB)
                    )
                ) {
                    val buttonText = when(vpnState) {
                        VpnTunnelState.UP -> stringResource(R.string.button_disconnect)
                        VpnTunnelState.TOGGLE -> stringResource(R.string.status_processing)
                        VpnTunnelState.DOWN -> stringResource(R.string.button_connect)
                    }
                    Text(buttonText, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.width(8.dp))
                    ConnectionStatusIndicator(vpnState)
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(0.8f),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Button(onClick = onImportConfig) {
                        Text("Импорт")
                    }
                    Button(onClick = onScanQrClicked) {
                        Text("Скан QR")
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionStatusIndicator(vpnState: VpnTunnelState) {
    val color = when (vpnState) {
        VpnTunnelState.UP -> Color.Green
        VpnTunnelState.DOWN -> Color.Red
        VpnTunnelState.TOGGLE -> Color.Yellow
    }
    Box(modifier = Modifier.size(20.dp).background(color, CircleShape))
}

// --- Previews ---

@Preview(showBackground = true, name = "UI - No Config")
@Composable
fun MainScreenUIPreview_NoConfig() {
    VPN_for_friends_v3Theme {
        MainScreenUI(
            vpnState = VpnTunnelState.DOWN,
            hasConfig = false,
            lastError = null,
            onConnectDisconnect = {},
            onImportConfig = {},
            onScanQrClicked = {}
        )
    }
}

@Preview(showBackground = true, name = "UI - Has Config, Down")
@Composable
fun MainScreenUIPreview_HasConfig_Down() {
    VPN_for_friends_v3Theme {
        MainScreenUI(
            vpnState = VpnTunnelState.DOWN,
            hasConfig = true,
            lastError = null,
            onConnectDisconnect = {},
            onImportConfig = {},
            onScanQrClicked = {}
        )
    }
}

@Preview(showBackground = true, name = "UI - Connected")
@Composable
fun MainScreenUIPreview_Connected() {
    VPN_for_friends_v3Theme {
        MainScreenUI(
            vpnState = VpnTunnelState.UP,
            hasConfig = true,
            lastError = null,
            onConnectDisconnect = {},
            onImportConfig = {},
            onScanQrClicked = {}
        )
    }
}

@Preview(showBackground = true, name = "UI - Error")
@Composable
fun MainScreenUIPreview_Error() {
    VPN_for_friends_v3Theme {
        MainScreenUI(
            vpnState = VpnTunnelState.DOWN,
            hasConfig = true,
            lastError = "Something went wrong!",
            onConnectDisconnect = {},
            onImportConfig = {},
            onScanQrClicked = {}
        )
    }
}
