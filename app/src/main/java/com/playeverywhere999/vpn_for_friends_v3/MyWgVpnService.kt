package com.playeverywhere999.vpn_for_friends_v3

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter

class MyWgVpnService : VpnService() {

    private val TAG = "MyWgVpnService"

    private val NOTIFICATION_CHANNEL_ID = "MyWgVpnServiceChannel"
    private val NOTIFICATION_ID = 1337

    private var currentTunnelName: String = VpnViewModel.DEFAULT_TUNNEL_NAME
    private var currentTunnel: MyWgTunnel? = null
    private var goBackend: GoBackend? = null
    private var currentWgConfig: Config? = null
    private var vpnInterfaceFd: ParcelFileDescriptor? = null

    private var connectAttemptsForCurrentConfig = 0
    private val retryHandler = Handler(Looper.getMainLooper())
    private var isRetryScheduled = false

    companion object {
        const val ACTION_CONNECT = "com.playeverywhere999.vpn_for_friends_v3.ACTION_CONNECT"
        const val ACTION_DISCONNECT = "com.playeverywhere999.vpn_for_friends_v3.ACTION_DISCONNECT"
        const val EXTRA_WG_CONFIG_STRING = "com.playeverywhere999.vpn_for_friends_v3.EXTRA_WG_CONFIG_STRING"
        const val EXTRA_TUNNEL_NAME = "com.playeverywhere999.vpn_for_friends_v3.EXTRA_TUNNEL_NAME"

        private val _tunnelStatus = MutableLiveData<Tunnel.State>(Tunnel.State.DOWN)
        val tunnelStatus: LiveData<Tunnel.State> = _tunnelStatus

        private val _vpnError = MutableLiveData<String?>(null)
        val vpnError: LiveData<String?> = _vpnError

        private val _serviceIsRunning = MutableLiveData<Boolean>(false)
        val serviceIsRunning: LiveData<Boolean> = _serviceIsRunning

        fun isServiceRunning(): Boolean = _serviceIsRunning.value ?: false

        fun newIntent(
            context: Context,
            action: String,
            configString: String? = null,
            tunnelName: String = VpnViewModel.DEFAULT_TUNNEL_NAME
        ): Intent {
            return Intent(context, MyWgVpnService::class.java).apply {
                this.action = action
                putExtra(EXTRA_TUNNEL_NAME, tunnelName)
                configString?.let { putExtra(EXTRA_WG_CONFIG_STRING, it) }
            }
        }
    }

    private fun clearCurrentError() {
        if (_vpnError.value != null) {
            _vpnError.postValue(null)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate called")
        _serviceIsRunning.postValue(true)
        createNotificationChannel()
        try {
            goBackend = GoBackend(this)
            Log.i(TAG, "GoBackend initialized successfully.")
        } catch (e: Exception) {
            val stackTrace = getStackTrace(e)
            Log.e(TAG, "Failed to initialize GoBackend in onCreate", e)
            _vpnError.postValue("FATAL: VPN backend init failed: ${e.message ?: "Unknown error"}. Stack: $stackTrace")
            _tunnelStatus.postValue(Tunnel.State.DOWN)
            stopSelfWithError()
            return
        }
        _tunnelStatus.postValue(Tunnel.State.DOWN)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: action=${intent?.action}, startId=$startId, receivedTunnelName=${intent?.getStringExtra(EXTRA_TUNNEL_NAME)}")

        clearCurrentError()
        if (!isRetryScheduled || intent?.action != ACTION_CONNECT) {
            retryHandler.removeCallbacksAndMessages(null)
            isRetryScheduled = false
        }

        val receivedTunnelName = intent?.getStringExtra(EXTRA_TUNNEL_NAME) ?: VpnViewModel.DEFAULT_TUNNEL_NAME
        if (receivedTunnelName != currentTunnelName) {
            Log.i(TAG, "Tunnel name change requested: from '$currentTunnelName' to '$receivedTunnelName'.")
            if (currentTunnel != null && (_tunnelStatus.value == Tunnel.State.UP || _tunnelStatus.value == Tunnel.State.TOGGLE)) {
                Log.w(TAG, "Active tunnel '$currentTunnelName' exists. Disconnecting it before switching to '$receivedTunnelName'.")
                disconnectTunnelInternal(stopService = false)
            }
            currentTunnelName = receivedTunnelName
            currentWgConfig = null
            connectAttemptsForCurrentConfig = 0
        }

        when (intent?.action) {
            ACTION_CONNECT -> {
                Log.i(TAG, "ACTION_CONNECT received for tunnel: $currentTunnelName")
                if (goBackend == null) {
                    Log.e(TAG, "GoBackend is null on ACTION_CONNECT.")
                    _vpnError.postValue("VPN backend error: Not initialized.")
                    _tunnelStatus.postValue(Tunnel.State.DOWN)
                    stopSelf()
                    return START_NOT_STICKY
                }

                // Проверка разрешений VPN
                val prepareIntent = prepare(this)
                if (prepareIntent != null) {
                    Log.e(TAG, "VPN permission not granted for $currentTunnelName")
                    _vpnError.postValue("VPN permission not granted. Please allow VPN access.")
                    _tunnelStatus.postValue(Tunnel.State.DOWN)
                    stopSelf()
                    return START_NOT_STICKY
                }

                val configString = intent.getStringExtra(EXTRA_WG_CONFIG_STRING)
                if (configString.isNullOrEmpty()) {
                    Log.e(TAG, "Config string is null or empty in ACTION_CONNECT for $currentTunnelName")
                    _vpnError.postValue("Internal error: VPN configuration not provided for $currentTunnelName.")
                    _tunnelStatus.postValue(Tunnel.State.DOWN)
                    stopSelf()
                    return START_NOT_STICKY
                }

                if (currentWgConfig == null || connectAttemptsForCurrentConfig == 0) {
                    try {
                        currentWgConfig = Config.parse(configString.reader().buffered())
                        Log.i(TAG, "Successfully parsed WireGuard config string for $currentTunnelName.")
                        connectAttemptsForCurrentConfig = 0
                    } catch (e: Exception) {
                        val stackTrace = getStackTrace(e)
                        Log.e(TAG, "Failed to parse WireGuard config string for $currentTunnelName", e)
                        _vpnError.postValue("Invalid VPN configuration for $currentTunnelName: ${e.message ?: "Unknown error"}. Stack: $stackTrace")
                        _tunnelStatus.postValue(Tunnel.State.DOWN)
                        currentWgConfig = null
                        connectAttemptsForCurrentConfig = 0
                        stopSelf()
                        return START_NOT_STICKY
                    }
                } else {
                    Log.i(TAG, "Using previously parsed WireGuard config for retry for $currentTunnelName.")
                }

                connectTunnel()
            }
            ACTION_DISCONNECT -> {
                Log.i(TAG, "ACTION_DISCONNECT received for tunnel: $currentTunnelName")
                disconnectTunnel()
            }
            else -> {
                Log.w(TAG, "Unknown or null action received: ${intent?.action}")
                if (currentTunnel == null && _tunnelStatus.value == Tunnel.State.DOWN) {
                    Log.i(TAG, "Service (re)started without specific action and no active tunnel. Stopping self.")
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private fun getStackTrace(e: Throwable): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        e.printStackTrace(pw)
        return sw.toString().take(1000) // Ограничиваем длину для логов
    }

    private fun connectTunnel() {
        val attemptNumber = connectAttemptsForCurrentConfig + 1
        Log.d(TAG, "Attempting to connect tunnel '$currentTunnelName'... (Attempt: $attemptNumber)")

        if (currentWgConfig == null) {
            Log.e(TAG, "currentWgConfig is null in connectTunnel for $currentTunnelName")
            _vpnError.postValue("Internal error: VPN configuration missing for $currentTunnelName.")
            _tunnelStatus.postValue(Tunnel.State.DOWN)
            stopSelf()
            return
        }
        if (goBackend == null) {
            Log.e(TAG, "goBackend is null in connectTunnel for $currentTunnelName.")
            _vpnError.postValue("VPN backend not available for $currentTunnelName.")
            _tunnelStatus.postValue(Tunnel.State.DOWN)
            stopSelf()
            return
        }

        startForeground(NOTIFICATION_ID, createNotification("VPN Connecting to $currentTunnelName..."))
        _tunnelStatus.postValue(Tunnel.State.TOGGLE)

        if (currentTunnel != null && currentTunnel?.getName() == currentTunnelName &&
            (_tunnelStatus.value == Tunnel.State.UP || _tunnelStatus.value == Tunnel.State.TOGGLE)) {
            Log.w(TAG, "Active/toggling tunnel '$currentTunnelName' object exists. Disconnecting it first for clean state.")
            disconnectTunnelInternal(stopService = false)
            try {
                Thread.sleep(300)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        val builder = Builder()
        try {
            currentWgConfig!!.getInterface().getAddresses().forEach { addr ->
                Log.d(TAG, "Adding address: ${addr.address}/${addr.mask}")
                builder.addAddress(addr.address, addr.mask)
            }
            currentWgConfig!!.getInterface().getDnsServers().forEach { dns ->
                Log.d(TAG, "Adding DNS server: $dns")
                builder.addDnsServer(dns)
            }
            currentWgConfig!!.getInterface().getMtu().ifPresent { mtu ->
                Log.d(TAG, "Setting MTU: $mtu")
                builder.setMtu(mtu)
            }
            currentWgConfig!!.getPeers().forEach { peer ->
                Log.d(TAG, "Processing peer: ${peer.getPublicKey()}")
                peer.getAllowedIps().forEach { allowedIp ->
                    try {
                        Log.d(TAG, "Adding route: ${allowedIp.address}/${allowedIp.mask}")
                        builder.addRoute(allowedIp.address, allowedIp.mask)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to add route for ${allowedIp.address}/${allowedIp.mask}: ${e.message}", e)
                    }
                }
            }

            builder.setSession(currentTunnelName)

        } catch (e: Exception) {
            val stackTrace = getStackTrace(e)
            Log.e(TAG, "Error configuring VpnService.Builder for $currentTunnelName: ${e.message}", e)
            _vpnError.postValue("Error setting up VPN network for $currentTunnelName: ${e.message ?: "Unknown error"}. Stack: $stackTrace")
            _tunnelStatus.postValue(Tunnel.State.DOWN)
            stopForeground(STOP_FOREGROUND_REMOVE)
            handleConnectFailure(e)
            return
        }

        try {
            closeVpnInterface()
            vpnInterfaceFd = builder.establish()

            if (vpnInterfaceFd == null) {
                Log.e(TAG, "VpnService.Builder.establish() returned null for $currentTunnelName!")
                _vpnError.postValue("Failed to establish VPN interface (OS error) for $currentTunnelName.")
                _tunnelStatus.postValue(Tunnel.State.DOWN)
                stopForeground(STOP_FOREGROUND_REMOVE)
                handleConnectFailure(RuntimeException("establish() returned null"))
                return
            }
            Log.d(TAG, "VPN interface established with fd: ${vpnInterfaceFd!!.fd} for $currentTunnelName")

            currentTunnel = MyWgTunnel(currentTunnelName) { newState ->
                Log.i(TAG, "Tunnel '${currentTunnel?.getName()}' state changed to: $newState")
                _tunnelStatus.postValue(newState)

                if (newState == Tunnel.State.UP) {
                    clearCurrentError()
                    connectAttemptsForCurrentConfig = 0
                    isRetryScheduled = false
                    retryHandler.removeCallbacksAndMessages(null)
                    startForeground(NOTIFICATION_ID, createNotification("VPN Connected to ${currentTunnel?.getName()}"))
                } else if (newState == Tunnel.State.DOWN) {
                    if (currentTunnel != null) {
                        Log.w(TAG, "Tunnel ${currentTunnel?.getName()} is DOWN (possibly due to error), closing VPN interface FD.")
                        if (_vpnError.value == null) {
                            _vpnError.postValue("VPN for ${currentTunnel?.getName()} disconnected unexpectedly.")
                        }
                        closeVpnInterface()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    }
                }
            }

            Log.i(TAG, "Calling goBackend.setState UP for tunnel: ${currentTunnel?.getName()}")
            goBackend?.setState(currentTunnel, Tunnel.State.UP, currentWgConfig)
        } catch (e: Exception) {
            val stackTrace = getStackTrace(e)
            Log.e(TAG, "Failed to connect tunnel '$currentTunnelName': ${e.message}", e)
            _vpnError.postValue("Failed to connect VPN: ${e.message ?: "Unknown error"}. Stack: $stackTrace")
            _tunnelStatus.postValue(Tunnel.State.DOWN)
            closeVpnInterface()
            stopForeground(STOP_FOREGROUND_REMOVE)
            handleConnectFailure(e)
        }
    }

    private fun handleConnectFailure(exception: Exception) {
        val maxAttempts = 2
        val errorMessage = "Connection for $currentTunnelName failed: ${exception.message ?: "Unknown error"}"

        if (connectAttemptsForCurrentConfig < maxAttempts && !isRetryScheduled) {
            Log.w(TAG, "Connection attempt $connectAttemptsForCurrentConfig for $currentTunnelName failed. Scheduling automatic retry.")
            _vpnError.postValue("Connection failed, retrying for $currentTunnelName...")
            _tunnelStatus.postValue(Tunnel.State.DOWN)
            stopForeground(STOP_FOREGROUND_REMOVE)

            isRetryScheduled = true
            retryHandler.postDelayed({
                isRetryScheduled = false
                Log.d(TAG, "Executing delayed automatic retry for $currentTunnelName (Attempt: ${connectAttemptsForCurrentConfig + 1}).")
                if (_serviceIsRunning.value == true && currentWgConfig != null && goBackend != null) {
                    connectAttemptsForCurrentConfig++
                    connectTunnel()
                } else {
                    Log.w(TAG, "Retry for $currentTunnelName aborted: service/config/backend not ready.")
                    if (_serviceIsRunning.value == true) {
                        _vpnError.postValue("Retry aborted for $currentTunnelName.")
                        _tunnelStatus.postValue(Tunnel.State.DOWN)
                        connectAttemptsForCurrentConfig = 0
                        stopSelf()
                    }
                }
            }, 3000)
        } else {
            Log.e(TAG, "$errorMessage. No more retries (attempts: $connectAttemptsForCurrentConfig).")
            _vpnError.postValue(errorMessage)
            _tunnelStatus.postValue(Tunnel.State.DOWN)
            stopForeground(STOP_FOREGROUND_REMOVE)
            connectAttemptsForCurrentConfig = 0
            stopSelf()
        }
    }

    private fun disconnectTunnel() {
        retryHandler.removeCallbacksAndMessages(null)
        isRetryScheduled = false
        disconnectTunnelInternal(stopService = true)
    }

    private fun disconnectTunnelInternal(stopService: Boolean) {
        Log.i(TAG, "Disconnecting tunnel '$currentTunnelName' (stopService: $stopService)...")
        _tunnelStatus.postValue(Tunnel.State.TOGGLE)

        val tunnelToDisconnect = currentTunnel
        currentTunnel = null

        if (tunnelToDisconnect != null && goBackend != null) {
            try {
                Log.d(TAG, "Calling goBackend.setState DOWN for tunnel: ${tunnelToDisconnect.getName()}")
                goBackend?.setState(tunnelToDisconnect, Tunnel.State.DOWN, null)
            } catch (e: Exception) {
                val stackTrace = getStackTrace(e)
                Log.e(TAG, "Error disconnecting tunnel ${tunnelToDisconnect.getName()} via GoBackend: ${e.message}", e)
                _vpnError.postValue("Error during disconnect: ${e.message ?: "Unknown error"}. Stack: $stackTrace")
                _tunnelStatus.postValue(Tunnel.State.DOWN)
            }
        } else {
            Log.w(TAG, "No active tunnel object or backend to disconnect for '$currentTunnelName'. Setting state to DOWN manually.")
            _tunnelStatus.postValue(Tunnel.State.DOWN)
        }

        closeVpnInterface()
        connectAttemptsForCurrentConfig = 0

        if (stopService) {
            Log.i(TAG, "Service stopping itself after disconnect of $currentTunnelName.")
            currentWgConfig = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            if (_tunnelStatus.value == Tunnel.State.DOWN) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
        }
    }

    private fun stopSelfWithError() {
        Log.e(TAG, "Stopping service due to critical error.")
        _tunnelStatus.postValue(Tunnel.State.DOWN)
        stopForeground(STOP_FOREGROUND_REMOVE)
        _serviceIsRunning.postValue(false)
        stopSelf()
    }

    private fun closeVpnInterface() {
        vpnInterfaceFd?.let {
            try {
                it.close()
                Log.d(TAG, "VPN interface ParcelFileDescriptor closed.")
            } catch (e: IOException) {
                Log.w(TAG, "Failed to close VPN interface ParcelFileDescriptor for $currentTunnelName", e)
            }
            vpnInterfaceFd = null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "VPN Service"
            val descriptionText = "Shows VPN connection status"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        notificationIntent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP

        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("VPN for Friends")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onRevoke() {
        Log.w(TAG, "VPN service revoked by system or user for $currentTunnelName!")
        _vpnError.postValue("VPN connection revoked by system.")
        disconnectTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy called for service managing tunnel '$currentTunnelName'")
        retryHandler.removeCallbacksAndMessages(null)
        isRetryScheduled = false
        _serviceIsRunning.postValue(false)

        if (currentTunnel != null || vpnInterfaceFd != null) {
            Log.w(TAG, "Resources (tunnel/fd) might still be active in onDestroy. Performing cleanup for $currentTunnelName.")
            closeVpnInterface()
            if (currentTunnel != null && goBackend != null) {
                try {
                    goBackend?.setState(currentTunnel, Tunnel.State.DOWN, null)
                } catch (e: Exception) {
                    Log.w(TAG, "Exception during GoBackend setState DOWN in onDestroy for ${currentTunnel?.getName()}: ${e.message}", e)
                }
            }
        }
        currentTunnel = null
        currentWgConfig = null
        super.onDestroy()
        Log.i(TAG, "onDestroy completed for service managing tunnel '$currentTunnelName'")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

class MyWgTunnel(
    private val name: String,
    private val onTunnelStateChangedCallback: (Tunnel.State) -> Unit
) : Tunnel {
    override fun getName(): String = name
    override fun onStateChange(newState: Tunnel.State) {
        onTunnelStateChangedCallback(newState)
    }
}