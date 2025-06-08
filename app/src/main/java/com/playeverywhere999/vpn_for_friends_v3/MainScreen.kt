package com.playeverywhere999.vpn_for_friends_v3


//import VpnState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.playeverywhere999.vpn_for_friends_v3.VpnState
import com.playeverywhere999.vpn_for_friends_v3.ui.theme.VPN_for_friends_v3Theme

@Composable
fun MainScreen(
    vpnState: VpnState,
    onConnectClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "VPN Status: ${vpnState.name}",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        Button(
            onClick = onConnectClick,
            modifier = Modifier.fillMaxWidth(0.8f)
        ) {
            Text(
                when (vpnState) {
                    VpnState.IDLE -> "Connect"
                    VpnState.CONNECTING -> "Connecting..."
                    VpnState.CONNECTED -> "Disconnect"
                    VpnState.DISCONNECTING -> "Disconnecting..."
                }
            )
        }
        // Здесь вы можете добавить другие элементы UI, например, выбор сервера, логи и т.д.
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreviewIdle() {
    VPN_for_friends_v3Theme { // Оберните в вашу тему
        MainScreen(vpnState = VpnState.IDLE, onConnectClick = {})
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreviewConnected() {
    VPN_for_friends_v3Theme { // Оберните в вашу тему
        MainScreen(vpnState = VpnState.CONNECTED, onConnectClick = {})
    }
}