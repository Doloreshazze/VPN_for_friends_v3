package com.playeverywhere999.vpn_for_friends_v3

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Handler // <-- Добавленный импорт
import android.os.Looper  // <-- Добавленный импорт
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.IOException

class MyWgVpnService : VpnService() {

    private val TAG = "MyWgVpnService"
    private val NOTIFICATION_CHANNEL_ID = "MyWgVpnServiceChannel"
    private val NOTIFICATION_ID = 1337
    private val TUNNEL_NAME_IN_SERVICE = "wg_friends_tunnel_service"

    private var currentTunnel: MyWgTunnel? = null
    private var goBackend: GoBackend? = null
    private var currentWgConfig: Config? = null
    private var vpnInterfaceFd: ParcelFileDescriptor? = null

    private var connectAttemptsInThisLifecycle = 0
    private var serviceStartIdForRetries: Int = 0

    // Объявляем Handler для отложенных задач
    private val retryHandler = Handler(Looper.getMainLooper()) // <-- Объявлен Handler

    companion object {
        const val ACTION_CONNECT = "com.playeverywhere999.vpn_for_friends_v3.ACTION_CONNECT"
        const val ACTION_DISCONNECT = "com.playeverywhere999.vpn_for_friends_v3.ACTION_DISCONNECT"
        const val EXTRA_WG_CONFIG_STRING = "com.playeverywhere999.vpn_for_friends_v3.EXTRA_WG_CONFIG_STRING"

        private val _tunnelStatus = MutableLiveData<Tunnel.State>(Tunnel.State.DOWN)
        val tunnelStatus: LiveData<Tunnel.State> = _tunnelStatus

        private val _vpnError = MutableLiveData<String?>(null)
        val vpnError: LiveData<String?> = _vpnError

        private val _serviceIsRunning = MutableLiveData<Boolean>(false)
        val serviceIsRunning: LiveData<Boolean> = _serviceIsRunning

        fun isServiceRunning(): Boolean = _serviceIsRunning.value ?: false
    }

    private fun clearCurrentError() {
        _vpnError.postValue(null)
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate called")
        _serviceIsRunning.postValue(true)
        createNotificationChannel()
        connectAttemptsInThisLifecycle = 0
        try {
            goBackend = GoBackend(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize GoBackend in onCreate", e)
            _vpnError.postValue("Failed to initialize VPN backend: ${e.message}")
        }
        _tunnelStatus.postValue(Tunnel.State.DOWN)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: action = ${intent?.action}, startId = $startId")
        serviceStartIdForRetries = startId
        clearCurrentError()
        // Отменяем предыдущие запланированные ретраи, если пользователь инициирует новое действие
        retryHandler.removeCallbacksAndMessages(null)


        when (intent?.action) {
            ACTION_CONNECT -> {
                Log.i(TAG, "Action connect received")
                connectAttemptsInThisLifecycle++

                if (goBackend == null) {
                    Log.w(TAG, "GoBackend was null on ACTION_CONNECT. Attempting to re-initialize.")
                    try {
                        goBackend = GoBackend(this)
                        Log.i(TAG, "GoBackend re-initialized in onStartCommand.")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to re-initialize GoBackend in onStartCommand", e)
                        _vpnError.postValue("VPN backend error: ${e.message}")
                        return START_NOT_STICKY
                    }
                }

                val configString = intent.getStringExtra(EXTRA_WG_CONFIG_STRING)
                if (configString == null) {
                    Log.e(TAG, "Config string is null in ACTION_CONNECT")
                    _vpnError.postValue("Internal error: VPN configuration not provided.")
                    return START_NOT_STICKY
                }

                try {
                    if (this.currentWgConfig == null || connectAttemptsInThisLifecycle == 1) {
                        this.currentWgConfig = Config.parse(configString.reader().buffered())
                        Log.i(TAG, "Successfully parsed WireGuard config string.")
                    } else {
                        Log.i(TAG, "Using previously parsed WireGuard config string for retry.")
                    }
                    connectTunnel(connectAttemptsInThisLifecycle == 1)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse WireGuard config string", e)
                    _vpnError.postValue("Invalid VPN configuration: ${e.localizedMessage}")
                    connectAttemptsInThisLifecycle = 0
                    return START_NOT_STICKY
                }
            }
            ACTION_DISCONNECT -> {
                Log.i(TAG, "Action disconnect received")
                disconnectTunnel() // retryHandler.removeCallbacksAndMessages(null) уже вызван выше
                connectAttemptsInThisLifecycle = 0
            }
            else -> {
                Log.w(TAG, "Unknown action received: ${intent?.action}")
            }
        }
        return START_STICKY
    }

    private fun connectTunnel(isFirstAttemptThisGoBackend: Boolean) {
        Log.d(TAG, "Attempting to connect tunnel... (isFirstAttemptThisGoBackend: $isFirstAttemptThisGoBackend)")

        if (this.currentWgConfig == null) {
            Log.e(TAG, "currentWgConfig is null in connectTunnel")
            _vpnError.postValue("Internal error: VPN configuration missing.")
            connectAttemptsInThisLifecycle = 0
            return
        }
        if (goBackend == null) {
            Log.e(TAG, "goBackend is null in connectTunnel. Attempting to re-initialize from connectTunnel.")
            try {
                goBackend = GoBackend(this)
                Log.i(TAG, "GoBackend re-initialized in connectTunnel.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to re-initialize GoBackend in connectTunnel", e)
                _vpnError.postValue("VPN backend error: ${e.message}")
                connectAttemptsInThisLifecycle = 0
                return
            }
        }

        Log.d(TAG, "Calling startForeground for connecting state BEFORE any delays or backend calls.")
        startForeground(NOTIFICATION_ID, createNotification("VPN Connecting..."))

        if (currentTunnel != null && (_tunnelStatus.value == Tunnel.State.UP || _tunnelStatus.value == Tunnel.State.TOGGLE)) {
            Log.w(TAG, "An active tunnel exists (${_tunnelStatus.value}). Disconnecting it first.")
            disconnectTunnelInternal(stopService = false)
            try {
                Thread.sleep(300)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                Log.w(TAG, "Delay after internal disconnect interrupted", e)
            }
        } else if (currentTunnel != null && !isFirstAttemptThisGoBackend) {
            Log.d(TAG, "Existing non-UP tunnel on non-first attempt. Ensuring it's DOWN in backend.")
            try {
                goBackend?.setState(currentTunnel, Tunnel.State.DOWN, null)
            } catch (e: Exception) {
                Log.w(TAG, "Exception trying to set existing non-UP tunnel to DOWN", e)
            }
        }

        val builder = Builder()
        try {
            currentWgConfig!!.getInterface().getAddresses().forEach { addr ->
                builder.addAddress(addr.address, addr.mask)
            }
            currentWgConfig!!.getInterface().getDnsServers().forEach { dns ->
                builder.addDnsServer(dns)
            }
            currentWgConfig!!.getInterface().getMtu().ifPresent { mtu ->
                builder.setMtu(mtu)
            }
            currentWgConfig!!.getPeers().forEach { peer ->
                peer.getAllowedIps().forEach { allowedIp ->
                    builder.addRoute(allowedIp.address, allowedIp.mask)
                }
            }
            builder.setSession(TUNNEL_NAME_IN_SERVICE)
        } catch (e: Exception) {
            Log.e(TAG, "Error configuring VpnService.Builder: ${e.message}", e)
            _vpnError.postValue("Error setting up VPN network: ${e.localizedMessage}")
            _tunnelStatus.postValue(Tunnel.State.DOWN)
            stopForeground(STOP_FOREGROUND_REMOVE)
            connectAttemptsInThisLifecycle = 0
            return
        }

        try {
            vpnInterfaceFd?.close()
            vpnInterfaceFd = builder.establish()

            if (vpnInterfaceFd == null) {
                Log.e(TAG, "VpnService.Builder.establish() returned null!")
                _vpnError.postValue("Failed to establish VPN interface (OS error).")
                _tunnelStatus.postValue(Tunnel.State.DOWN)
                stopForeground(STOP_FOREGROUND_REMOVE)
                connectAttemptsInThisLifecycle = 0
                return
            }
            Log.d(TAG, "VPN interface established with fd: ${vpnInterfaceFd!!.fd}")

            currentTunnel = MyWgTunnel(TUNNEL_NAME_IN_SERVICE) { newState ->
                Log.d(TAG, "Tunnel state changed: ${currentTunnel?.getName()} -> $newState")
                _tunnelStatus.postValue(newState)
                if (newState == Tunnel.State.UP) {
                    clearCurrentError()
                    // Успешное соединение, сбрасываем счетчик явных попыток для этого цикла
                    connectAttemptsInThisLifecycle = 0
                    startForeground(NOTIFICATION_ID, createNotification("VPN Connected to ${currentTunnel?.getName()}"))
                } else if (newState == Tunnel.State.DOWN) {
                    if (vpnInterfaceFd != null) {
                        Log.d(TAG, "Tunnel is DOWN, closing VPN interface FD.")
                        closeVpnInterface()
                    }
                    // Не сбрасываем foreground здесь, если это не явный дисконнект
                    // stopForeground(STOP_FOREGROUND_REMOVE) // Убрано, т.к. может быть авторетрай
                }
            }

            if (isFirstAttemptThisGoBackend) {
                Log.d(TAG, "First attempt for this GoBackend instance. Pre-setting tunnel to DOWN.")
                try {
                    goBackend?.setState(currentTunnel, Tunnel.State.DOWN, null)
                    Log.d(TAG, "GoBackend 'pre-set' DOWN call completed for ${currentTunnel?.getName()}.")
                } catch (e: Exception) {
                    Log.e(TAG, "Error during GoBackend 'pre-set' DOWN for ${currentTunnel?.getName()}", e)
                }
            }

            Log.i(TAG, "Calling goBackend.setState UP for tunnel: ${currentTunnel?.getName()}")
            goBackend?.setState(currentTunnel, Tunnel.State.UP, this.currentWgConfig)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect tunnel (isFirstAttemptThisGoBackend: $isFirstAttemptThisGoBackend): ${e.message}", e)
            closeVpnInterface()

            if (isFirstAttemptThisGoBackend) {
                Log.w(TAG, "First attempt with this GoBackend failed. Re-initializing GoBackend and scheduling automatic retry.")
                currentTunnel = null // Обнуляем туннель
                try {
                    goBackend = GoBackend(this) // Создаем новый экземпляр
                    Log.i(TAG, "GoBackend re-initialized. Scheduling automatic retry.")

                    // << --- ИЗМЕНЕНИЕ ЗДЕСЬ --- >>
                    retryHandler.postDelayed({
                        Log.d(TAG, "Executing delayed automatic retry.")
                        // Проверяем, не был ли сервис остановлен или конфиг сброшен тем временем
                        if (_serviceIsRunning.value == true && currentWgConfig != null) {
                            connectTunnel(isFirstAttemptThisGoBackend = false)
                        } else {
                            Log.w(TAG, "Retry aborted: service not running or config is null.")
                            if (_serviceIsRunning.value == true) { // Если сервис еще работает, но конфига нет
                                _vpnError.postValue("Internal error: VPN configuration missing for retry.")
                                _tunnelStatus.postValue(Tunnel.State.DOWN)
                                stopForeground(STOP_FOREGROUND_REMOVE)
                                connectAttemptsInThisLifecycle = 0
                            }
                        }
                    }, 1000) // Задержка 1 секунда, можно настроить

                } catch (reinitEx: Exception) {
                    Log.e(TAG, "Failed to re-initialize GoBackend for retry", reinitEx)
                    _vpnError.postValue("Critical backend error during retry: ${reinitEx.message}")
                    _tunnelStatus.postValue(Tunnel.State.DOWN)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    connectAttemptsInThisLifecycle = 0
                }
            } else {
                // Если и вторая (автоматическая, отложенная) попытка не удалась
                Log.e(TAG, "Automatic (delayed) retry also failed.")
                _vpnError.postValue("Connection failed after automatic retry: ${e.localizedMessage}")
                _tunnelStatus.postValue(Tunnel.State.DOWN)
                stopForeground(STOP_FOREGROUND_REMOVE)
                connectAttemptsInThisLifecycle = 0
            }
        }
    }

    private fun disconnectTunnel() {
        // Отменяем любые запланированные ретраи при дисконнекте
        retryHandler.removeCallbacksAndMessages(null)
        disconnectTunnelInternal(stopService = true)
    }

    private fun disconnectTunnelInternal(stopService: Boolean) {
        Log.d(TAG, "Attempting to disconnect tunnel (stopService: $stopService)...")
        // connectAttemptsInThisLifecycle = 0 // Уже сбрасывается в onStartCommand/ACTION_DISCONNECT или при успехе/полном провале

        val tunnelToDisconnect = currentTunnel
        if (tunnelToDisconnect != null && goBackend != null) {
            try {
                Log.d(TAG, "Calling goBackend.setState DOWN for tunnel: ${tunnelToDisconnect.getName()}")
                goBackend?.setState(tunnelToDisconnect, Tunnel.State.DOWN, null)
            } catch (e: Exception) {
                Log.e(TAG, "Error disconnecting tunnel via GoBackend: ${e.message}", e)
            }
        } else {
            Log.w(TAG, "Tunnel (${tunnelToDisconnect?.getName()}) or backend not initialized, cannot send DOWN to GoBackend.")
        }
        _tunnelStatus.postValue(Tunnel.State.DOWN)
        closeVpnInterface()
        currentTunnel = null

        if (stopService) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            Log.d(TAG, "Service attempting to stop itself after disconnect.")
            stopSelfResult(serviceStartIdForRetries)
            Log.d(TAG, "stopSelfResult called with startId: $serviceStartIdForRetries")
        } else {
            // Если сервис не останавливается (например, при внутреннем реконнекте или в onDestroy)
            // И если статус уже DOWN (например, из-за ошибки, а не явного дисконнекта)
            if (_tunnelStatus.value == Tunnel.State.DOWN) {
                stopForeground(STOP_FOREGROUND_REMOVE) // Убираем уведомление "Connecting..."
            }
        }
    }

    private fun closeVpnInterface() {
        try {
            vpnInterfaceFd?.close()
            Log.d(TAG, "VPN interface ParcelFileDescriptor closed.")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to close VPN interface ParcelFileDescriptor", e)
        }
        vpnInterfaceFd = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "VPN Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(text: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java) // Убедитесь, что MainActivity - это ваш главный экран
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
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Замените на вашу иконку
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onRevoke() {
        Log.w(TAG, "VPN service revoked by system or user!")
        retryHandler.removeCallbacksAndMessages(null) // Отменяем ретраи
        _vpnError.postValue("VPN connection revoked by system.")
        disconnectTunnelInternal(stopService = true)
        super.onRevoke()
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy called")
        retryHandler.removeCallbacksAndMessages(null) // Очищаем любые запланированные задачи
        _serviceIsRunning.postValue(false)
        closeVpnInterface()
        currentTunnel = null
        // goBackend здесь не нужно обнулять, он сам очистится.
        // Главное, чтобы не было ссылок на него в отложенных задачах.
        super.onDestroy()
        Log.d(TAG, "onDestroy completed.")
    }

    override fun onBind(intent: Intent?): android.os.IBinder? = null
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
