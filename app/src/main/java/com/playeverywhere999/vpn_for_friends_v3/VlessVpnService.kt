package com.playeverywhere999.vpn_for_friends_v3

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

// Импортируем классы из вашей новой библиотеки
import libv2ray.Libv2ray
import libv2ray.CoreController
import libv2ray.CoreCallbackHandler

enum class VpnTunnelState {
    UP,
    DOWN,
    TOGGLE
}

class VlessVpnService : VpnService(), CoreCallbackHandler {

    private val TAG = "VlessVpnService"

    private val NOTIFICATION_CHANNEL_ID = "VlessVpnServiceChannel"
    private val NOTIFICATION_ID = 1337

    private var currentTunnelName: String = VpnViewModel.DEFAULT_TUNNEL_NAME
    private var vpnInterfaceFd: ParcelFileDescriptor? = null

    private var coreController: CoreController? = null

    private val handler = Handler(Looper.getMainLooper())
    private val isDisconnecting = AtomicBoolean(false)
    private val singleThreadExecutor = Executors.newSingleThreadExecutor()

    companion object {
        const val ACTION_CONNECT = "com.playeverywhere999.vpn_for_friends_v3.ACTION_CONNECT"
        const val ACTION_DISCONNECT = "com.playeverywhere999.vpn_for_friends_v3.ACTION_DISCONNECT"
        const val EXTRA_TUNNEL_NAME = "com.playeverywhere999.vpn_for_friends_v3.EXTRA_TUNNEL_NAME"

        private val _tunnelStatus = MutableLiveData<VpnTunnelState>(VpnTunnelState.DOWN)
        val tunnelStatus: LiveData<VpnTunnelState> = _tunnelStatus

        private val _vpnError = MutableLiveData<String?>(null)
        val vpnError: LiveData<String?> = _vpnError

        private val _serviceIsRunning = MutableLiveData<Boolean>(false)
        val serviceIsRunning: LiveData<Boolean> = _serviceIsRunning

        @Volatile
        private var vlessConfig: String? = null

        fun newIntent(context: Context, action: String, tunnelName: String = VpnViewModel.DEFAULT_TUNNEL_NAME): Intent {
            return Intent(context, VlessVpnService::class.java).apply {
                this.action = action
                putExtra(EXTRA_TUNNEL_NAME, tunnelName)
            }
        }

        fun setConfig(config: String) {
            this.vlessConfig = config
            Log.d("VlessVpnService", "Static config set, length: ${config.length}")
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate called")
        _serviceIsRunning.postValue(true)
        createNotificationChannel()

        try {
            coreController = Libv2ray.newCoreController(this)
            Log.i(TAG, "CoreController initialized successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize CoreController", e)
            _vpnError.postValue("Fatal: Failed to init V2Ray: ${e.message}")
            stopSelfWithError()
            return
        }

        _tunnelStatus.postValue(VpnTunnelState.DOWN)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(TAG, "onStartCommand: action=$action, intent=$intent")

        if (action == null) {
            Log.w(TAG, "onStartCommand received null action. Stopping service.")
            stopSelfWithError("Service started with no action.")
            return START_NOT_STICKY
        }

        currentTunnelName = intent.getStringExtra(EXTRA_TUNNEL_NAME) ?: VpnViewModel.DEFAULT_TUNNEL_NAME
        Log.d(TAG, "Tunnel name set to: $currentTunnelName")

        when (action) {
            ACTION_CONNECT -> {
                val config = vlessConfig
                if (config == null) {
                    Log.e(TAG, "VLESS config is null. Cannot connect.")
                    stopSelfWithError("VLESS config is missing.")
                    return START_NOT_STICKY
                }
                Log.d(TAG, "Connecting with config of length: ${config.length}")
                connectTunnel(config)
            }
            ACTION_DISCONNECT -> {
                disconnectTunnel()
            }
        }

        return START_STICKY
    }

    private fun connectTunnel(config: String) {
        Log.i(TAG, "connectTunnel called.")
        if (_tunnelStatus.value == VpnTunnelState.UP || _tunnelStatus.value == VpnTunnelState.TOGGLE) {
            Log.w(TAG, "connectTunnel called but tunnel is already UP or TOGGLE. Ignoring.")
            return
        }
        isDisconnecting.set(false)
        _tunnelStatus.postValue(VpnTunnelState.TOGGLE)
        updateNotification("Connecting...")

        singleThreadExecutor.execute {
            try {
                Log.d(TAG, "Calling coreController.startLoop() on background thread...")
                coreController?.startLoop(config)
                Log.d(TAG, "coreController.startLoop() returned on background thread.")
            } catch (e: Exception) {
                Log.e(TAG, "Exception when calling startLoop", e)
                handler.post { stopSelfWithError("Error starting V2Ray loop: ${e.message}") }
            }
        }
    }

    private fun disconnectTunnel() {
        Log.i(TAG, "disconnectTunnel called. Current status: ${_tunnelStatus.value}")
        if (isDisconnecting.getAndSet(true)) {
            Log.w(TAG, "disconnectTunnel is already in progress. Ignoring.")
            return
        }

        _tunnelStatus.postValue(VpnTunnelState.TOGGLE)
        updateNotification("Disconnecting...")

        singleThreadExecutor.execute {
            try {
                Log.d(TAG, "Calling coreController.stopLoop() on background thread...")
                coreController?.stopLoop()
                Log.d(TAG, "coreController.stopLoop() returned. Manually triggering shutdown sequence as callback is unreliable.")

                // Принудительно запускаем последовательность отключения, так как обратный вызов не приходит
                handler.post {
                    if (_tunnelStatus.value != VpnTunnelState.DOWN) {
                        Log.i(TAG, "Manual shutdown sequence initiated.")
                        _tunnelStatus.postValue(VpnTunnelState.DOWN)
                        updateNotification("Disconnected")
                        closeVpnInterface()
                        stopForeground(true)
                        stopSelf()
                        vlessConfig = null
                        isDisconnecting.set(false)
                    }
                }
            } catch(e: Exception) {
                Log.e(TAG, "Exception when calling coreController.stopLoop()", e)
                handler.post { stopSelfWithError("Error stopping loop: ${e.message}") }
                isDisconnecting.set(false) // Reset on error
            }
        }
    }

    private fun stopSelfWithError(errorMessage: String? = null) {
        Log.e(TAG, "stopSelfWithError called. Error: $errorMessage")
        if (errorMessage != null) {
            _vpnError.postValue(errorMessage)
        }
        _tunnelStatus.postValue(VpnTunnelState.DOWN)
        closeVpnInterface()
        stopForeground(true)
        stopSelf()
    }

    private fun closeVpnInterface() {
        try {
            vpnInterfaceFd?.close()
            vpnInterfaceFd = null
            Log.d(TAG, "VPN interface ParcelFileDescriptor closed.")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to close VPN fd", e)
        }
    }

    private fun updateNotification(text: String) {
        startForeground(NOTIFICATION_ID, createNotification(text))
    }

    // --- CoreCallbackHandler Implementation ---

    override fun startup(): Long {
        Log.d(TAG, "CoreCallback: startup() called")
        isDisconnecting.set(false)

        val builder = Builder()
            .addAddress("10.8.0.2", 24)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.8.8")
            .setSession(currentTunnelName)
            .setMtu(1500)

        try {
            builder.addDisallowedApplication(packageName)
            Log.i(TAG, "Excluded package: $packageName")
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "Failed to exclude app", e)
            _vpnError.postValue("Failed to exclude app from VPN.")
            return -1L
        }

        vpnInterfaceFd = builder.establish() ?: run {
            Log.e(TAG, "Failed to establish VPN interface")
            _vpnError.postValue("Failed to establish VPN interface")
            _tunnelStatus.postValue(VpnTunnelState.DOWN)
            return -1L
        }

        val vpnFd = vpnInterfaceFd!!.fd.toLong()
        Log.i(TAG, "VPN interface established, fd: $vpnFd")

        return vpnFd
    }

    override fun shutdown(): Long {
        Log.d(TAG, "CoreCallback: shutdown() called. (This is now considered a fallback)")
        handler.post {
            if (_tunnelStatus.value != VpnTunnelState.DOWN) {
                Log.w(TAG, "Shutdown callback fired unexpectedly. Ensuring service is stopped.")
                _tunnelStatus.postValue(VpnTunnelState.DOWN)
                updateNotification("Disconnected")
                closeVpnInterface()
                stopForeground(true)
                stopSelf()
                vlessConfig = null
                isDisconnecting.set(false)
            }
        }
        return 0L
    }

    override fun onEmitStatus(p0: Long, p1: String): Long {
        Log.i(TAG, "CoreCallback: onEmitStatus() called with status: '$p1'")
        handler.post {
            if (p1.contains("running", ignoreCase = true)) {
                if (_tunnelStatus.value != VpnTunnelState.UP) {
                    Log.i(TAG, "VPN state changed to UP.")
                    _tunnelStatus.postValue(VpnTunnelState.UP)
                    updateNotification("Connected")
                    _vpnError.postValue(null)
                    isDisconnecting.set(false)
                }
            } else if (p1.contains("failed", ignoreCase = true)) {
                 Log.e(TAG, "V2Ray core reported a failure: $p1")
                 stopSelfWithError("V2Ray core error: $p1")
            }
        }
        return 0L
    }

    // --- Boilerplate code ---

    override fun onRevoke() {
        Log.w(TAG, "VPN revoked by system!")
        disconnectTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy called")
        _serviceIsRunning.postValue(false)
        singleThreadExecutor.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(text: String): Notification {
        val activityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, activityIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("VPN for Friends")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_vpn_key)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, "VPN Status", NotificationManager.IMPORTANCE_DEFAULT)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}