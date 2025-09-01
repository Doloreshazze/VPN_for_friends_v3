package com.playeverywhere999.vpn_for_friends_v3

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
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

class MyWgVpnService : VpnService() {

    private val TAG = "MyWgVpnService"

    private val NOTIFICATION_CHANNEL_ID = "MyWgVpnServiceChannel"
    private val NOTIFICATION_ID = 1337

    // Используем VpnViewModel.DEFAULT_TUNNEL_NAME как значение по умолчанию, если ничего не пришло
    private var currentTunnelName: String = VpnViewModel.DEFAULT_TUNNEL_NAME

    private var currentTunnel: MyWgTunnel? = null
    private var goBackend: GoBackend? = null
    private var currentWgConfig: Config? = null
    private var vpnInterfaceFd: ParcelFileDescriptor? = null

    private var connectAttemptsForCurrentConfig = 0 // Переименовано для ясности
    private var lastUsedStartId: Int = START_NOT_STICKY

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
            tunnelName: String = VpnViewModel.DEFAULT_TUNNEL_NAME, // Сделаем не-nullable, т.к. имя всегда нужно
        ): Intent {
            return Intent(context, MyWgVpnService::class.java).also { intent ->
                intent.action = action
                intent.putExtra(EXTRA_TUNNEL_NAME, tunnelName) // Имя туннеля всегда передается
                configString?.let { intent.putExtra(EXTRA_WG_CONFIG_STRING, it) }
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
        // connectAttemptsForCurrentConfig инициализируется в onStartCommand при ACTION_CONNECT
        try {
            goBackend = GoBackend(this)
            Log.i(TAG, "GoBackend initialized successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize GoBackend in onCreate", e)
            _vpnError.postValue("FATAL: VPN backend init failed: ${e.message}")
            _tunnelStatus.postValue(Tunnel.State.DOWN)
            stopSelfWithError() // Не можем работать без бэкенда
            return
        }
        _tunnelStatus.postValue(Tunnel.State.DOWN) // Начальное состояние после успешного onCreate
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastUsedStartId = startId
        val action = intent?.action
        val receivedTunnelName = intent?.getStringExtra(EXTRA_TUNNEL_NAME) ?: VpnViewModel.DEFAULT_TUNNEL_NAME

        Log.d(TAG, "onStartCommand: action=$action, startId=$startId, receivedTunnelName=$receivedTunnelName, currentTunnelName=$currentTunnelName")

        clearCurrentError() // Очищаем старые ошибки при новой команде
        // Отменяем предыдущие ретраи только если это не сам ретрай или если действие не CONNECT
        // (чтобы ретрай мог выполниться)
        if (!isRetryScheduled || action != ACTION_CONNECT) {
            retryHandler.removeCallbacksAndMessages(null)
            isRetryScheduled = false
        }


        // Обработка смены имени туннеля
        if (receivedTunnelName != currentTunnelName) {
            Log.i(TAG, "Tunnel name change requested: from '$currentTunnelName' to '$receivedTunnelName'.")
            if (currentTunnel != null && (_tunnelStatus.value == Tunnel.State.UP || _tunnelStatus.value == Tunnel.State.TOGGLE)) {
                Log.w(TAG, "Active tunnel '$currentTunnelName' exists. Disconnecting it before switching to '$receivedTunnelName'.")
                disconnectTunnelInternal(stopService = false) // Отключаем старый, не останавливая сервис
            }
            currentTunnelName = receivedTunnelName // Обновляем имя
            currentWgConfig = null // Сбрасываем конфиг, т.к. имя туннеля изменилось
            connectAttemptsForCurrentConfig = 0 // Сбрасываем счетчик для нового туннеля
        }


        when (action) {
            ACTION_CONNECT -> {
                Log.i(TAG, "ACTION_CONNECT received for tunnel: $currentTunnelName")

                if (goBackend == null) {
                    Log.e(TAG, "GoBackend is null on ACTION_CONNECT. This should not happen if onCreate succeeded.")
                    _vpnError.postValue("VPN backend error: Not initialized.")
                    _tunnelStatus.postValue(Tunnel.State.DOWN)
                    return START_NOT_STICKY // Не перезапускать, если критическая ошибка
                }

                val configString = intent.getStringExtra(EXTRA_WG_CONFIG_STRING)
                if (configString.isNullOrEmpty()) {
                    Log.e(TAG, "Config string is null or empty in ACTION_CONNECT for $currentTunnelName")
                    _vpnError.postValue("Internal error: VPN configuration not provided for $currentTunnelName.")
                    _tunnelStatus.postValue(Tunnel.State.DOWN)
                    return START_NOT_STICKY
                }

                // Если это первая попытка для этого конфига (или имя туннеля сменилось), парсим конфиг.
                // connectAttemptsForCurrentConfig инкрементируется перед вызовом connectTunnel
                if (currentWgConfig == null || connectAttemptsForCurrentConfig == 0) {
                    try {
                        currentWgConfig = Config.parse(configString.reader().buffered())
                        Log.i(TAG, "Successfully parsed WireGuard config string for $currentTunnelName.")
                        connectAttemptsForCurrentConfig = 0 // Сбрасываем для нового конфига/туннеля
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse WireGuard config string for $currentTunnelName", e)
                        _vpnError.postValue("Invalid VPN configuration for $currentTunnelName: ${e.localizedMessage}")
                        _tunnelStatus.postValue(Tunnel.State.DOWN)
                        currentWgConfig = null // Убедимся, что плохой конфиг не используется
                        connectAttemptsForCurrentConfig = 0
                        return START_NOT_STICKY
                    }
                } else {
                    Log.i(TAG, "Using previously parsed WireGuard config for retry for $currentTunnelName.")
                }
                connectAttemptsForCurrentConfig++ // Увеличиваем счетчик перед попыткой подключения
                connectTunnel()
            }
            ACTION_DISCONNECT -> {
                Log.i(TAG, "ACTION_DISCONNECT received for tunnel: $currentTunnelName")
                disconnectTunnel() // Это вызовет disconnectTunnelInternal(stopService = true)
            }
            else -> {
                Log.w(TAG, "Unknown or null action received: $action")
                // Если сервис был перезапущен системой (START_STICKY)
                if (currentTunnel == null && _tunnelStatus.value == Tunnel.State.DOWN) {
                    Log.i(TAG, "Service (re)started without specific action and no active tunnel. Stopping self.")
                    stopSelfResult(startId)
                }
            }
        }
        // START_STICKY, чтобы система перезапустила сервис, если он будет убит,
        // но только если мы не приняли решение его остановить (например, START_NOT_STICKY при ошибке)
        return if (_serviceIsRunning.value == true) START_STICKY else START_NOT_STICKY
    }

    private fun connectTunnel() {
        val isFirstAttemptForThisConfig = (connectAttemptsForCurrentConfig == 1)
        Log.d(TAG, "Attempting to connect tunnel '$currentTunnelName'... (Attempt: $connectAttemptsForCurrentConfig)")

        if (currentWgConfig == null) {
            Log.e(TAG, "currentWgConfig is null in connectTunnel for $currentTunnelName")
            _vpnError.postValue("Internal error: VPN configuration missing for $currentTunnelName.")
            _tunnelStatus.postValue(Tunnel.State.DOWN)
            return
        }
        if (goBackend == null) {
            Log.e(TAG, "goBackend is null in connectTunnel for $currentTunnelName.")
            _vpnError.postValue("VPN backend not available for $currentTunnelName.")
            _tunnelStatus.postValue(Tunnel.State.DOWN)
            return
        }

        // Уведомление о попытке подключения (или обновляем, если уже есть)
        startForeground(NOTIFICATION_ID, createNotification("VPN Connecting to $currentTunnelName..."))
        _tunnelStatus.postValue(Tunnel.State.TOGGLE)

        // Если уже есть активный туннель (должно быть обработано в onStartCommand при смене имени,
        // но здесь проверка на случай, если что-то пошло не так с предыдущим дисконнектом)
        if (currentTunnel != null && currentTunnel?.getName() == currentTunnelName &&
            (_tunnelStatus.value == Tunnel.State.UP || _tunnelStatus.value == Tunnel.State.TOGGLE) &&
            !isFirstAttemptForThisConfig) { // Не делаем этого для первой попытки, т.к. там может быть "pre-set DOWN"
            Log.w(TAG, "Active/toggling tunnel '$currentTunnelName' object exists. Disconnecting it first for clean state.")
            disconnectTunnelInternal(stopService = false) // Не останавливаем сервис
            // Даем время на завершение операций. Вместо sleep лучше использовать Handler.
            // Но для простоты, если это редкий случай, оставим.
            // Для более надежного решения, этот connectTunnel нужно было бы отложить через Handler
            // после завершения disconnectTunnelInternal.
            try { Thread.sleep(300) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
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
            // Маршруты для VPN
            currentWgConfig!!.getPeers().forEach { peer ->
                peer.getAllowedIps().forEach { allowedIp ->
                    builder.addRoute(allowedIp.address, allowedIp.mask)
                }
            }
            // Android VpnService не требует указания AllowedIPs для самого интерфейса,
            // он использует их для маршрутизации. Для WireGuard "Interface.AllowedIPs" не существует.
            // Трафик, который не попадает в AllowedIPs пиров, не пойдет через туннель.

            builder.setSession(currentTunnelName) // Имя сессии для Android VpnService
            // builder.setBlocking(true) // Полезно для отладки, но может блокировать UI поток, если establish долгий
            // builder.setConfigureIntent(pendingIntentToMainActivity) // Если нужно открыть UI при подключении

        } catch (e: Exception) {
            Log.e(TAG, "Error configuring VpnService.Builder for $currentTunnelName: ${e.message}", e)
            _vpnError.postValue("Error setting up VPN network for $currentTunnelName: ${e.localizedMessage}")
            _tunnelStatus.postValue(Tunnel.State.DOWN)
            stopForeground(STOP_FOREGROUND_REMOVE)
            // connectAttemptsForCurrentConfig уже инкрементирован, ретрай логика ниже
            handleConnectFailure(e)
            return
        }

        try {
            vpnInterfaceFd?.close()
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
                    connectAttemptsForCurrentConfig = 0 // Успешное соединение, сбрасываем счетчик
                    isRetryScheduled = false
                    retryHandler.removeCallbacksAndMessages(null) // Отменяем ретраи при успехе
                    startForeground(NOTIFICATION_ID, createNotification("VPN Connected to ${currentTunnel?.getName()}"))
                } else if (newState == Tunnel.State.DOWN) {
                    // Если состояние DOWN, это может быть из-за ошибки или явного дисконнекта.
                    // Если currentTunnel еще не был обнулен (т.е. это не явный дисконнект),
                    // то это, вероятно, ошибка во время работы туннеля.
                    if (currentTunnel != null) {
                        Log.w(TAG, "Tunnel ${currentTunnel?.getName()} is DOWN (possibly due to error), closing VPN interface FD.")
                        // Ошибку должен был выставить GoBackend или мы сами при попытке подключения
                        // Если это произошло спонтанно, _vpnError может быть не установлен
                        if (_vpnError.value == null) {
                            // _vpnError.postValue("VPN for ${currentTunnel?.getName()} disconnected unexpectedly.")
                        }
                        closeVpnInterface()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        // Здесь НЕ вызываем handleConnectFailure, т.к. это коллбэк от уже работавшего туннеля
                        // Если он упал, то упал. ViewModel должна это обработать.
                        // connectAttemptsForCurrentConfig НЕ сбрасываем, если это была ошибка,
                        // чтобы при следующем ACTION_CONNECT можно было попробовать ретрай.
                    }
                }
            }

            // Если это первая попытка для этого конфига/имени туннеля, "pre-set" состояние в DOWN
            // Это решает некоторые проблемы с GoBackend, если туннель с таким именем уже был.
            if (isFirstAttemptForThisConfig) {
                Log.d(TAG, "First attempt for $currentTunnelName. Pre-setting tunnel to DOWN in GoBackend.")
                try {
                    goBackend?.setState(currentTunnel, Tunnel.State.DOWN, null)
                } catch (e: Exception) {
                    Log.w(TAG, "Exception during GoBackend 'pre-set' DOWN for ${currentTunnel?.getName()}", e)
                    // Не критично, продолжаем
                }
            }

            Log.i(TAG, "Calling goBackend.setState UP for tunnel: ${currentTunnel?.getName()}")
            goBackend?.setState(currentTunnel, Tunnel.State.UP, currentWgConfig)
            // Успех или неудача будут обработаны в коллбэке MyWgTunnel

        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect tunnel '$currentTunnelName' (isFirstAttempt: $isFirstAttemptForThisConfig): ${e.message}", e)
            closeVpnInterface()
            // _tunnelStatus.postValue(Tunnel.State.DOWN) // уже должно быть установлено
            handleConnectFailure(e)
        }
    }

    private fun handleConnectFailure(exception: Exception) {
        val maxAttempts = 2 // 1-я обычная + 1 ретрай
        val errorMessage = "Connection for $currentTunnelName failed: ${exception.localizedMessage}"

        if (connectAttemptsForCurrentConfig < maxAttempts && !isRetryScheduled) {
            Log.w(TAG, "Connection attempt $connectAttemptsForCurrentConfig for $currentTunnelName failed. Scheduling automatic retry.")
            _vpnError.postValue("Connection failed, retrying for $currentTunnelName...")
            _tunnelStatus.postValue(Tunnel.State.DOWN) // Убедимся, что статус DOWN перед ретраем
            stopForeground(STOP_FOREGROUND_REMOVE) // Убираем "Connecting..."

            isRetryScheduled = true
            retryHandler.postDelayed({
                isRetryScheduled = false // Сбрасываем флаг перед выполнением ретрая
                Log.d(TAG, "Executing delayed automatic retry for $currentTunnelName (Attempt: ${connectAttemptsForCurrentConfig + 1}).")
                if (_serviceIsRunning.value == true && currentWgConfig != null && goBackend != null) {
                    // connectAttemptsForCurrentConfig уже был инкрементирован для текущей неудачной попытки,
                    // следующий вызов connectTunnel() будет с connectAttemptsForCurrentConfig + 1
                    connectAttemptsForCurrentConfig++ // Инкрементируем для следующей попытки
                    connectTunnel()
                } else {
                    Log.w(TAG, "Retry for $currentTunnelName aborted: service/config/backend not ready.")
                    if (_serviceIsRunning.value == true) {
                        _vpnError.postValue("Retry aborted for $currentTunnelName.")
                        _tunnelStatus.postValue(Tunnel.State.DOWN)
                        connectAttemptsForCurrentConfig = 0 // Сбрасываем, т.к. ретрай отменен
                    }
                }
            }, 3000) // Задержка 3 секунды
        } else {
            Log.e(TAG, "$errorMessage. No more retries (attempts: $connectAttemptsForCurrentConfig).")
            _vpnError.postValue(errorMessage)
            _tunnelStatus.postValue(Tunnel.State.DOWN)
            stopForeground(STOP_FOREGROUND_REMOVE)
            connectAttemptsForCurrentConfig = 0 // Сбрасываем для будущих ACTION_CONNECT
            // Не останавливаем сервис здесь, VpnViewModel может решить запросить новый конфиг
        }
    }


    private fun disconnectTunnel() {
        retryHandler.removeCallbacksAndMessages(null) // Отменяем любые запланированные ретраи
        isRetryScheduled = false
        disconnectTunnelInternal(stopService = true) // Явный дисконнект останавливает сервис
    }

    private fun disconnectTunnelInternal(stopService: Boolean) {
        Log.i(TAG, "Disconnecting tunnel '$currentTunnelName' (stopService: $stopService)...")
        _tunnelStatus.postValue(Tunnel.State.TOGGLE)

        val tunnelToDisconnect = currentTunnel
        currentTunnel = null // Обнуляем сразу, чтобы избежать гонок состояний
        // currentWgConfig = null // Конфиг сбрасываем только если это полный дисконнект с остановкой или сменой имени

        if (tunnelToDisconnect != null && goBackend != null) {
            try {
                Log.d(TAG, "Calling goBackend.setState DOWN for tunnel: ${tunnelToDisconnect.getName()}")
                goBackend?.setState(tunnelToDisconnect, Tunnel.State.DOWN, null)
                // Коллбэк от MyWgTunnel обработает newState == DOWN
            } catch (e: Exception) {
                Log.e(TAG, "Error disconnecting tunnel ${tunnelToDisconnect.getName()} via GoBackend: ${e.message}", e)
                _vpnError.postValue("Error during disconnect: ${e.message}")
                _tunnelStatus.postValue(Tunnel.State.DOWN) // Принудительно ставим DOWN
            }
        } else {
            Log.w(TAG, "No active tunnel object or backend to disconnect for '$currentTunnelName'. Setting state to DOWN manually.")
            _tunnelStatus.postValue(Tunnel.State.DOWN) // Если нечего отключать в Go, просто меняем статус
        }

        closeVpnInterface()
        connectAttemptsForCurrentConfig = 0 // Сбрасываем счетчик попыток

        if (stopService) {
            Log.i(TAG, "Service stopping itself after disconnect of $currentTunnelName.")
            currentWgConfig = null // Полностью сбрасываем конфиг при остановке сервиса
            stopForeground(STOP_FOREGROUND_REMOVE)
            // stopSelf(); // Используем stopSelfResult для конкретного startId
            stopSelfResult(lastUsedStartId)
        } else {
            // Если сервис не останавливается (например, при внутреннем реконнекте или ошибке)
            // и статус уже DOWN, убираем уведомление.
            if (_tunnelStatus.value == Tunnel.State.DOWN) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
        }
    }

    private fun stopSelfWithError() {
        Log.e(TAG, "Stopping service due to critical error.")
        _tunnelStatus.postValue(Tunnel.State.DOWN)
        stopForeground(STOP_FOREGROUND_REMOVE)
        _serviceIsRunning.postValue(false) // Явно указываем, что сервис не будет работать
        stopSelf()
    }

    private fun closeVpnInterface() {
        try {
            vpnInterfaceFd?.close()
            Log.d(TAG, "VPN interface ParcelFileDescriptor closed.")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to close VPN interface ParcelFileDescriptor for $currentTunnelName", e)
        }
        vpnInterfaceFd = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "VPN Service"
            val descriptionText = "Shows VPN connection status"
            val importance = NotificationManager.IMPORTANCE_LOW // Или IMPORTANCE_NONE, если не нужно звука/вибрации
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance).apply {
                description = descriptionText
                // Можно настроить setSound(null, null), setShowBadge(false) и т.д.
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java) // Открываем MainActivity при нажатии
        notificationIntent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP

        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("VPN for Friends") // Название вашего приложения
            .setContentText(text) // "Connecting to X", "Connected to X", "Disconnected"
            .setSmallIcon(R.drawable.ic_stat_vpn_key) // ЗАМЕНИТЕ НА ВАШУ ИКОНКУ (например, ic_vpn_key)
            .setOngoing(true) // Уведомление нельзя смахнуть, пока сервис работает в foreground
            .setPriority(NotificationCompat.PRIORITY_MIN) // Минимальный приоритет, чтобы не мешать
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent) // Действие при нажатии
            .build()
    }

    override fun onRevoke() {
        Log.w(TAG, "VPN service revoked by system or user for $currentTunnelName!")
        _vpnError.postValue("VPN connection revoked by system.")
        disconnectTunnel() // Полностью останавливаемся
        super.onRevoke()
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy called for service managing tunnel '$currentTunnelName'")
        retryHandler.removeCallbacksAndMessages(null)
        isRetryScheduled = false
        _serviceIsRunning.postValue(false)

        if (currentTunnel != null || vpnInterfaceFd != null) {
            Log.w(TAG, "Resources (tunnel/fd) might still be active in onDestroy. Performing cleanup for $currentTunnelName.")
            // Вызываем внутренний дисконнект без остановки сервиса (он и так уничтожается)
            // Но setState в GoBackend может не успеть, если сервис уже убивается
            // Главное - закрыть FD
            closeVpnInterface()
            if (currentTunnel != null && goBackend != null) {
                try {
                    // Попытка корректно остановить туннель в Go, если возможно
                    goBackend?.setState(currentTunnel, Tunnel.State.DOWN, null)
                } catch (e: Exception) {
                    Log.w(TAG, "Exception during GoBackend setState DOWN in onDestroy for ${currentTunnel?.getName()}", e)
                }
            }
        }
        currentTunnel = null
        currentWgConfig = null
        // goBackend = null // GoBackend должен сам управляться своим жизненным циклом
        super.onDestroy()
        Log.i(TAG, "onDestroy completed for service managing tunnel '$currentTunnelName'")
    }

    // IBinder не используется, так как это не bound service
    override fun onBind(intent: Intent?): IBinder? = null
}

// Простой класс-обертка для Tunnel, чтобы передавать имя и коллбэк
class MyWgTunnel(
    private val name: String, // Имя туннеля, с которым он был создан
    private val onTunnelStateChangedCallback: (Tunnel.State) -> Unit,
) : Tunnel {
    override fun getName(): String = name
    override fun onStateChange(newState: Tunnel.State) {
        onTunnelStateChangedCallback(newState)
    }
}
