package com.playeverywhere999.vpn_for_friends_v3

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

@Composable
fun VpnTunnelState.toHumanReadableString(): String {
    return when (this) {
        VpnTunnelState.UP -> stringResource(id = R.string.vpn_status_connected)
        VpnTunnelState.DOWN -> stringResource(id = R.string.vpn_status_disconnected)
        VpnTunnelState.TOGGLE -> stringResource(id = R.string.status_processing)
    }
}
