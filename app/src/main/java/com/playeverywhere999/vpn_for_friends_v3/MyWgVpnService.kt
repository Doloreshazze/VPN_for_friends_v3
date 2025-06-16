package com.playeverywhere999.vpn_for_friends_v3

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
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

    // Метод для сброса текущей ошибки изнутри сервиса
    private fun clearCurrentError() {
        _vpnError.postValue(null)
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate called")
        _serviceIsRunning.postValue(true)
        createNotificationChannel()
        try {
            goBackend = GoBackend(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize GoBackend", e)
            _vpnError.postValue("Failed to initialize VPN backend: ${e.message}")
            stopSelf()
            return
        }
        _tunnelStatus.postValue(Tunnel.State.DOWN)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: action = ${intent?.action}")
        clearCurrentError() // Сбрасываем ошибку при получении новой команды

        when (intent?.action) {
            ACTION_CONNECT -> {
                Log.i(TAG, "Action connect received")
                val configString = intent.getStringExtra(EXTRA_WG_CONFIG_STRING)

                if (configString == null) {
                    Log.e(TAG, "Config string is null in intent. Cannot connect.")
                    _vpnError.postValue("Configuration data not received by service.")
                    _tunnelStatus.postValue(Tunnel.State.DOWN)
                    return START_NOT_STICKY
                }

                try {
                    this.currentWgConfig = Config.parse(configString.reader().buffered())
                    Log.i(TAG, "Successfully parsed WireGuard config string in service.")
                    connectTunnel()
                } catch (e: IOException) {
                    Log.e(TAG, "Failed to parse WireGuard config string (IOException): ${e.message}", e)
                    _vpnError.postValue("Error parsing configuration: ${e.localizedMessage}")
                    _tunnelStatus.postValue(Tunnel.State.DOWN)
                    return START_NOT_STICKY
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse WireGuard config string (Exception): ${e.message}", e)
                    _vpnError.postValue("Invalid configuration format: ${e.localizedMessage}")
                    _tunnelStatus.postValue(Tunnel.State.DOWN)
                    return START_NOT_STICKY
                }
            }
            ACTION_DISCONNECT -> {
                Log.i(TAG, "Action disconnect received")
                disconnectTunnel()
            }
            else -> {
                Log.w(TAG, "Unknown or null action received: ${intent?.action}")
                if (currentTunnel == null || _tunnelStatus.value == Tunnel.State.DOWN) {
                    Log.d(TAG, "No active tunnel and unknown action, considering stopping service.")
                    // stopSelfResult(startId) // Раскомментируйте, если нужно останавливать
                }
            }
        }
        return START_STICKY
    }

    private fun connectTunnel() {
        Log.d(TAG, "Attempting to connect tunnel...")
        clearCurrentError()

        if (this.currentWgConfig == null) {
            Log.e(TAG, "WireGuard configuration is not available in service.")
            _vpnError.postValue("Internal error: Configuration not available for connection.")
            _tunnelStatus.postValue(Tunnel.State.DOWN)
            return
        }

        if (goBackend == null) {
            Log.e(TAG, "GoBackend is not initialized!")
            _vpnError.postValue("Internal error: VPN backend not initialized.")
            _tunnelStatus.postValue(Tunnel.State.DOWN)
            return
        }

        if (currentTunnel != null && _tunnelStatus.value == Tunnel.State.UP) {
            Log.w(TAG, "An active tunnel already exists. Disconnecting it first.")
            disconnectTunnelInternal(stopService = false) // Отключим предыдущий, но сервис не остановим
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
            // Убедитесь, что setSession вызывается ПЕРЕД establish()
            builder.setSession(TUNNEL_NAME_IN_SERVICE) // Имя сессии для VpnService
        } catch (e: Exception) {
            Log.e(TAG, "Error configuring VpnService.Builder: ${e.message}", e)
            // Используем e.message если localizedMessage отсутствует
            val errorMsg = e.localizedMessage ?: e.message ?: "Unknown configuration error"
            _vpnError.postValue("Error setting up VPN network parameters: $errorMsg")
            _tunnelStatus.postValue(Tunnel.State.DOWN)
            return
        }

        try {
            vpnInterfaceFd?.close() // Закрываем старый, если был
            vpnInterfaceFd = builder.establish()

            if (vpnInterfaceFd == null) {
                Log.e(TAG, "VpnService.Builder.establish() returned null! Failed to setup VPN interface.")
                // Добавим более общее сообщение, если система не дала деталей
                _vpnError.postValue("Failed to establish VPN interface (OS denied or error, no specific message).")
                _tunnelStatus.postValue(Tunnel.State.DOWN)
                // Здесь может потребоваться вызвать stopSelf() или stopForeground(), если это критическая ошибка
                return
            }
            Log.d(TAG, "VPN interface established with fd: ${vpnInterfaceFd!!.fd}")

            currentTunnel = MyWgTunnel(TUNNEL_NAME_IN_SERVICE) { newState -> // Передаем актуальное имя
                Log.d(TAG, "Tunnel state changed: ${currentTunnel?.getName()} -> $newState")
                _tunnelStatus.postValue(newState)
                if (newState == Tunnel.State.UP) {
                    startForeground(NOTIFICATION_ID, createNotification("VPN Connected to ${currentTunnel?.getName()}"))
                } else if (newState == Tunnel.State.DOWN) {
                    if (vpnInterfaceFd != null) {
                        Log.d(TAG, "Tunnel is DOWN, closing VPN interface FD.")
                        closeVpnInterface()
                    }
                    // stopForeground(STOP_FOREGROUND_REMOVE) // Подумайте, нужно ли это здесь
                }
            }

            Log.i(TAG, "Calling goBackend.setState UP for tunnel: ${currentTunnel?.getName()} with config: ${this.currentWgConfig?.getInterface()?.getAddresses()}")
            goBackend?.setState(currentTunnel, Tunnel.State.UP, this.currentWgConfig)
            // Сразу после вызова setState, состояние туннеля еще не UP, оно изменится асинхронно
            // через коллбэк в MyWgTunnel. Поэтому TOGGLE здесь уместен.
            _tunnelStatus.postValue(Tunnel.State.TOGGLE)

            startForeground(NOTIFICATION_ID, createNotification("VPN Connecting to ${currentTunnel?.getName()}..."))

        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect tunnel or establish VPN interface: ${e.message}", e)
            // Улучшенное сообщение об ошибке
            val errorDetail = e.localizedMessage ?: e.message ?: e.toString()
            _vpnError.postValue("Connection failed: $errorDetail")
            _tunnelStatus.postValue(Tunnel.State.DOWN)
            closeVpnInterface()
            stopForeground(STOP_FOREGROUND_REMOVE)
            // stopSelf(); // Возможно, остановить сервис, если ошибка критична
        }
    }

    private fun disconnectTunnel() {
        disconnectTunnelInternal(stopService = true)
    }

    private fun disconnectTunnelInternal(stopService: Boolean) {
        Log.d(TAG, "Attempting to disconnect tunnel (stopService: $stopService)...")
        // clearCurrentError() // Ошибка будет сброшена при следующем ACTION_CONNECT

        if (currentTunnel != null && goBackend != null) {
            try {
                goBackend?.setState(currentTunnel, Tunnel.State.DOWN, null)
            } catch (e: Exception) {
                Log.e(TAG, "Error disconnecting tunnel via GoBackend: ${e.message}", e)
                _vpnError.postValue("Disconnection error: ${e.localizedMessage}")
                _tunnelStatus.postValue(Tunnel.State.DOWN)
                closeVpnInterface() // Закрываем интерфейс даже при ошибке в GoBackend
            }
        } else {
            Log.w(TAG, "Tunnel or backend not initialized, cannot disconnect.")
            _tunnelStatus.postValue(Tunnel.State.DOWN)
            closeVpnInterface()
        }
        currentTunnel = null

        if (stopService) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            Log.d(TAG, "Service attempting to stop itself after disconnect.")
            stopSelf()
        } else {
            // Если сервис не останавливается (например, в onDestroy или при переподключении),
            // можно обновить уведомление или убрать его, если туннель упал.
            if (_tunnelStatus.value == Tunnel.State.DOWN) {
                stopForeground(STOP_FOREGROUND_REMOVE)
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
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(text: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("VPN for Friends")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground) // ЗАМЕНИТЕ НА СВОЮ ИКОНКУ
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onRevoke() {
        Log.w(TAG, "VPN service revoked by system or user!")
        _vpnError.postValue("VPN connection revoked by system.")
        disconnectTunnelInternal(stopService = true)
        super.onRevoke()
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy called")
        _serviceIsRunning.postValue(false)
        if (currentTunnel != null || vpnInterfaceFd != null) {
            Log.w(TAG, "Service destroyed, but tunnel or VPN interface might still be active. Forcing disconnect.")
            disconnectTunnelInternal(stopService = false)
        }
        goBackend = null
        super.onDestroy()
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