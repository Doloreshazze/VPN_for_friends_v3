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

import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray

enum class VpnTunnelState {
    UP,
    DOWN,
    TOGGLE
}

class VlessVpnService : VpnService() {

    private val TAG = "VlessVpnService"
    private val NOTIFICATION_CHANNEL_ID = "VlessVpnServiceChannel"
    private val NOTIFICATION_ID = 1337

    private var currentTunnelName: String = VpnViewModel.DEFAULT_TUNNEL_NAME
    private var vpnInterfaceFd: ParcelFileDescriptor? = null
    private var coreController: CoreController? = null

    private val handler = Handler(Looper.getMainLooper())
    private val isDisconnecting = AtomicBoolean(false)
    private val singleThreadExecutor = Executors.newSingleThreadExecutor()

    // === CALLBACK: 3 МЕТОДА ===
    private val coreCallbackHandler = object : CoreCallbackHandler {
        override fun startup(): Long {
            Log.d(TAG, "CoreCallback: startup()")
            return 0L
        }

        override fun onEmitStatus(p0: Long, p1: String): Long {
            Log.d(TAG, "V2Ray [$p0]: $p1")

            when {
                p1.contains("connected to vnext", ignoreCase = true) -> {
                    Log.i(TAG, "REAL CONNECTION ESTABLISHED!")
                }
                p1.contains("handshake", ignoreCase = true) -> {
                    Log.w(TAG, "Handshake: $p1")
                }
                p1.contains("failed", ignoreCase = true) -> {
                    Log.e(TAG, "V2Ray FAILED: $p1")
                }
            }

            handler.post {
                if (p1.contains("running", ignoreCase = true) && _tunnelStatus.value != VpnTunnelState.UP) {
                    _tunnelStatus.postValue(VpnTunnelState.UP)
                    updateNotification("Connected")
                    _vpnError.postValue(null)
                }
            }
            return 0L
        }

        override fun shutdown(): Long {
            Log.d(TAG, "CoreCallback: shutdown()")
            handler.post {
                if (_tunnelStatus.value != VpnTunnelState.DOWN) {
                    _tunnelStatus.postValue(VpnTunnelState.DOWN)
                    updateNotification("Disconnected")
                    closeVpnInterface()
                    stopForeground(true)
                    stopSelf()
                    isDisconnecting.set(false)
                }
            }
            return 0L
        }
    }

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

        fun newIntent(context: Context, action: String, tunnelName: String = VpnViewModel.DEFAULT_TUNNEL_NAME): Intent {
            return Intent(context, VlessVpnService::class.java).apply {
                this.action = action
                putExtra(EXTRA_TUNNEL_NAME, tunnelName)
            }
        }
    }

    private val _lastErrorMessage = MutableLiveData<String?>()
    val lastErrorMessage: LiveData<String?> = _lastErrorMessage

    // === ONCREATE ===
    override fun onCreate() {
        super.onCreate()

        // ИНИЦИАЛИЗАЦИЯ НЕ НУЖНА!
        coreController = CoreController(coreCallbackHandler)
        Log.i(TAG, "CoreController created — V2Ray ready")

        _serviceIsRunning.postValue(true)
        createNotificationChannel()
        _tunnelStatus.postValue(VpnTunnelState.DOWN)
    }

    // === ONSTARTCOMMAND ===
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(TAG, "onStartCommand: $action")

        if (action == null) {
            stopSelfWithError("No action")
            return START_NOT_STICKY
        }

        currentTunnelName = intent.getStringExtra(EXTRA_TUNNEL_NAME) ?: VpnViewModel.DEFAULT_TUNNEL_NAME

        when (action) {
            ACTION_CONNECT -> {
                val config = intent.getStringExtra("CONFIG")
                if (config.isNullOrEmpty()) {
                    stopSelfWithError("Config missing")
                    return START_NOT_STICKY
                }
                connectTunnel(config)
            }
            ACTION_DISCONNECT -> disconnectTunnel()
        }
        return START_STICKY
    }

    // === CONNECT TUNNEL ===
    private fun connectTunnel(config: String) {
        Log.i(TAG, "connectTunnel called.")
        if (_tunnelStatus.value == VpnTunnelState.UP || _tunnelStatus.value == VpnTunnelState.TOGGLE) {
            Log.w(TAG, "Already connecting.")
            return
        }
        isDisconnecting.set(false)
        _tunnelStatus.postValue(VpnTunnelState.TOGGLE)
        updateNotification("Connecting...")

        singleThreadExecutor.execute {
            try {
                val builder = Builder()
                builder.setSession("MyFriendsVPN")
                builder.setMtu(1280)
                builder.addAddress("10.0.0.2", 32)
                builder.addRoute("0.0.0.0", 0)
                builder.addDnsServer("1.1.1.1")
                try { builder.addDisallowedApplication(packageName) } catch (e: Exception) { }

                val vpnInterface = builder.establish()
                    ?: throw IOException("TUN failed")

                val vpnFd = vpnInterface.fd
                Log.i(TAG, "TUN fd: $vpnFd")

                val finalConfig = config.replace("\"fd\":1", "\"fd\":$vpnFd")
                Log.d(TAG, "Final config length: ${finalConfig.length}")

                if (coreController == null) {
                    throw IllegalStateException("coreController null")
                }

                Log.d(TAG, "Starting V2Ray...")
                coreController?.startLoop(finalConfig)
                Log.i(TAG, "V2Ray started")

                _tunnelStatus.postValue(VpnTunnelState.UP)
                updateNotification("Connected")

            } catch (e: Exception) {
                Log.e(TAG, "VPN FAILED", e)
                handler.post {
                    _lastErrorMessage.value = "Error: ${e.message}"
                    _tunnelStatus.value = VpnTunnelState.DOWN
                    updateNotification("Disconnected")
                }
            }
        }
    }

    // === DISCONNECT ===
    private fun disconnectTunnel() {
        Log.i(TAG, "disconnectTunnel called.")
        if (isDisconnecting.getAndSet(true)) return

        _tunnelStatus.postValue(VpnTunnelState.TOGGLE)
        updateNotification("Disconnecting...")

        singleThreadExecutor.execute {
            try {
                Log.d(TAG, "Stopping V2Ray...")
                coreController?.stopLoop()  // ← stopLoop
                Log.d(TAG, "V2Ray stopped.")
            } catch (e: Exception) {
                Log.e(TAG, "Stop failed", e)
            } finally {
                handler.post { finishDisconnect() }
            }
        }
    }

    private fun finishDisconnect() {
        if (_tunnelStatus.value != VpnTunnelState.DOWN) {
            _tunnelStatus.postValue(VpnTunnelState.DOWN)
            updateNotification("Disconnected")
            closeVpnInterface()
            stopForeground(true)
            stopSelf()
            isDisconnecting.set(false)
        }
    }

    // === NOTIFICATION ===
    private fun updateNotification(text: String) {
        startForeground(NOTIFICATION_ID, createNotification(text))
    }

    private fun createNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("VPN for Friends")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_vpn_key)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "VPN Status",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    // === UTILS ===
    private fun stopSelfWithError(msg: String) {
        Log.e(TAG, "Error: $msg")
        _vpnError.postValue(msg)
        finishDisconnect()
    }

    private fun closeVpnInterface() {
        try {
            vpnInterfaceFd?.close()
            vpnInterfaceFd = null
            Log.d(TAG, "TUN closed.")
        } catch (e: IOException) {
            Log.e(TAG, "Close failed", e)
        }
    }

    // === LIFECYCLE ===
    override fun onRevoke() {
        Log.w(TAG, "VPN revoked!")
        disconnectTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        try {
            Log.i(TAG, "onDestroy")
            _serviceIsRunning.postValue(false)
            singleThreadExecutor.shutdown()
            coreController?.stopLoop()  // ← stopLoop
            Log.i(TAG, "CoreController stopped")
        } catch (e: Exception) {
            Log.e(TAG, "onDestroy error", e)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
