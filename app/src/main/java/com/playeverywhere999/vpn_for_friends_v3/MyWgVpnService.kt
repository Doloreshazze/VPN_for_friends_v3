package com.playeverywhere999.vpn_for_friends_v3

import android.app.Application // Добавлен импорт
import android.app.Notification // Для createNotification
import android.app.NotificationChannel // Для createNotification
import android.app.NotificationManager // Для createNotification
import android.app.PendingIntent // Для createNotification
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat // Для createNotification
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelProvider // Добавлен импорт
import androidx.lifecycle.ViewModelStore // Добавлен импорт
import androidx.lifecycle.ViewModelStoreOwner // Добавлен импорт
import com.wireguard.android.backend.GoBackend // Предполагаемый импорт
import com.wireguard.android.backend.Tunnel // Для Tunnel.State
import com.wireguard.config.Config // Импорт для Config
import com.wireguard.config.Interface // Для доступа к параметрам интерфейса в Config
import com.wireguard.config.Peer // Для доступа к параметрам пира в Config (например, AllowedIPs для маршрутов)

class MyWgVpnService : VpnService(), ViewModelStoreOwner { // Реализуем ViewModelStoreOwner

    // Шаг 1: Реализация ViewModelStoreOwner
    private val _viewModelStore = ViewModelStore()
    override val viewModelStore: ViewModelStore
        get() = _viewModelStore

    // Шаг 2: Инициализация VpnViewModel
    private val vpnViewModel: VpnViewModel by lazy {
        ViewModelProvider(
            this,
            ViewModelProvider.AndroidViewModelFactory.getInstance(application)
        )[VpnViewModel::class.java]
    }

    companion object {
        private const val TAG = "MyWgVpnService"
        const val ACTION_CONNECT = "com.playeverywhere999.vpn.ACTION_CONNECT"
        const val ACTION_DISCONNECT = "com.playeverywhere999.vpn.ACTION_DISCONNECT"
        private const val NOTIFICATION_ID = 1 // Уникальный ID для уведомления
        private const val VPN_SERVICE_CHANNEL_ID = "VPN_SERVICE_CHANNEL"
        private const val TUNNEL_NAME_IN_SERVICE = "MyWgServiceTunnel"

        val serviceIsRunning = MutableLiveData<Boolean>().apply { postValue(false) }
        val tunnelStatus = MutableLiveData<Tunnel.State>().apply { postValue(Tunnel.State.DOWN) }
        val vpnError = MutableLiveData<String?>().apply { postValue(null) }
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var backend: GoBackend? = null
    private var currentTunnel: MyWgTunnel? = null
    private var currentWgConfig: Config? = null // Храним текущий конфиг для disconnect

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        serviceIsRunning.postValue(true)
        vpnError.postValue(null)
        tunnelStatus.postValue(Tunnel.State.DOWN)

        // Инициализация GoBackend. Делаем это один раз при создании сервиса.
        try {
            backend = GoBackend(applicationContext)
            Log.d(TAG, "GoBackend initialized successfully in service.")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "SERVICE: Error initializing GoBackend: Native library not found.", e)
            vpnError.postValue("CRITICAL: WireGuard library not found. ${e.message}")
            // Если backend не создан, сервис не сможет работать.
            // Можно остановить сервис или просто логировать и не давать подключаться.
            // connectTunnel() будет проверять backend на null.
        } catch (e: Exception) {
            Log.e(TAG, "SERVICE: Error initializing GoBackend", e)
            vpnError.postValue("Error initializing GoBackend: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: action = ${intent?.action}")
        vpnError.postValue(null) // Сбрасываем предыдущую ошибку при новой команде

        when (intent?.action) {
            ACTION_CONNECT -> {
                Log.i(TAG, "Action connect received")
                connectTunnel()
            }
            ACTION_DISCONNECT -> {
                Log.i(TAG, "Action disconnect received")
                disconnectTunnel()
            }
            else -> {
                Log.w(TAG, "Unknown action or null intent received.")
                if (intent == null && serviceIsRunning.value == true && tunnelStatus.value == Tunnel.State.UP) {
                    Log.w(TAG, "Service restarted by system (null intent) while VPN was UP. Attempting to restore.")
                    // Попытка восстановить состояние, если система перезапустила сервис
                    // (для START_STICKY, хотя мы используем START_NOT_STICKY)
                    // connectTunnel() // Это может быть опасно без проверки текущего состояния
                } else {
                    // stopSelf() // Не останавливаем при неизвестной команде, если уже запущен
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun connectTunnel() {
        Log.d(TAG, "Attempting to connect tunnel...")

        if (tunnelStatus.value == Tunnel.State.UP) {
            Log.w(TAG, "Connect command received, but tunnel is already UP.")
            return
        }

        if (backend == null) {
            Log.e(TAG, "GoBackend not initialized. Cannot connect.")
            vpnError.postValue("VPN Backend not available. Cannot connect.")
            tunnelStatus.postValue(Tunnel.State.DOWN)
            return
        }

        currentWgConfig = vpnViewModel.preparedConfig.value
        if (currentWgConfig == null) {
            Log.e(TAG, "WireGuard configuration is not available from ViewModel.")
            vpnError.postValue("Configuration not prepared. Please load/reload configuration first.")
            tunnelStatus.postValue(Tunnel.State.DOWN)
            // Не останавливаем сервис, чтобы пользователь мог загрузить конфиг и попробовать снова.
            return
        }
        Log.d(TAG, "Using config: ${currentWgConfig?.getInterface()?.getAddresses()}")

        val builder = Builder()
        builder.setSession(getString(R.string.app_name)) // Используйте имя вашего приложения

        try {
            val wgInterface = currentWgConfig!!.getInterface() // Non-null asserted due to check above

            // 1. Установка адресов интерфейса
            wgInterface.getAddresses().forEach { addr ->
                Log.d(TAG, "Adding address: ${addr.getAddress()} with prefix ${addr.getMask()}")
                builder.addAddress(addr.getAddress(), addr.getMask())
            }

            // 2. Установка DNS-серверов
            wgInterface.getDnsServers().forEach { dns ->
                Log.d(TAG, "Adding DNS server: ${dns.hostAddress}")
                builder.addDnsServer(dns)
            }

            // 3. Установка MTU
            if (wgInterface.getMtu().isPresent) {
                val mtu = wgInterface.getMtu().get()
                Log.d(TAG, "Setting MTU: $mtu")
                builder.setMtu(mtu)
            }

            // 4. Установка маршрутов
            // Обычно, это AllowedIPs из конфигурации пира(ов).
            // Если AllowedIPs включает 0.0.0.0/0, весь трафик пойдет через VPN.
            // Если есть конкретные подсети, только они будут маршрутизироваться.
            // VpnService.Builder также нуждается в маршрутах для IP-адресов DNS-серверов,
            // если они не попадают в AllowedIPs.
            // WireGuard Android обычно добавляет все AllowedIPs как маршруты.
            var includesAllTrafficRoute = false
            currentWgConfig!!.getPeers().forEach { peer ->
                peer.getAllowedIps().forEach { allowedIp ->
                    Log.d(TAG, "Adding route for AllowedIP: ${allowedIp.getAddress()} with prefix ${allowedIp.getMask()}")
                    builder.addRoute(allowedIp.getAddress(), allowedIp.getMask())
                    if (allowedIp.toString() == "0.0.0.0/0") {
                        includesAllTrafficRoute = true
                    }
                }
            }
            // Если не весь трафик маршрутизируется, и DNS-серверы внешние,
            // нужно убедиться, что для них есть маршруты.
            // WireGuard GoBackend обычно сам обрабатывает это правильно,
            // передавая нужные маршруты системе через файловый дескриптор.
            // Поэтому явное добавление маршрутов для DNS здесь может быть избыточным,
            // если они уже покрыты AllowedIPs.

            // Для Android Q (API 29) и выше
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // builder.setMetered(false) // Установить, если VPN не тарифицируется
                // builder.setAllowBypass(false) // По умолчанию false, весь трафик через VPN
                // wgInterface.getIncludedApplications() и getExcludedApplications()
                // используются для per-app VPN.
                if (wgInterface.getIncludedApplications().isNotEmpty()) {
                    wgInterface.getIncludedApplications().forEach { appPkg ->
                        builder.addAllowedApplication(appPkg)
                    }
                }
                if (wgInterface.getExcludedApplications().isNotEmpty()) {
                    wgInterface.getExcludedApplications().forEach { appPkg ->
                        builder.addDisallowedApplication(appPkg)
                    }
                }
            }

            // Закрываем предыдущий интерфейс, если он был
            vpnInterface?.close()
            Log.d(TAG, "Establishing VPN interface...")
            vpnInterface = builder.establish()

            if (vpnInterface != null) {
                val fd = vpnInterface!!.detachFd() // detachFd делает FD независимым от ParcelFileDescriptor
                Log.i(TAG, "VPN interface established. FD: $fd. Handing off to GoBackend.")

                // Создаем туннель для GoBackend
                // Колбэк будет обновлять LiveData нашего сервиса
                currentTunnel = MyWgTunnel(TUNNEL_NAME_IN_SERVICE,
                    onTunnelStateChanged = { newState ->
                        Log.d(TAG, "GoBackend reported state change for tunnel ${currentTunnel?.getName()}: $newState")
                        tunnelStatus.postValue(newState)
                        if (newState == Tunnel.State.UP) {
                            vpnError.postValue(null) // Сбрасываем ошибку при успешном UP
                        } else if (newState == Tunnel.State.DOWN) {
                            // Если состояние DOWN, возможно, была ошибка или штатное отключение.
                            // Ошибку должен выставить сам backend или мы здесь, если знаем причину.
                        }
                    }
                )

                // Передаем управление GoBackend
                // GoBackend.turnOn() или backend.setState()
                // wg-quick-go использует wgTurnOn(ifName, fd, settings)
                // WireGuard Android library использует backend.setState
                backend!!.setState(currentTunnel!!, Tunnel.State.UP, currentWgConfig!!)

                Log.i(TAG, "GoBackend instructed to bring tunnel UP.")
                // Фактическое состояние UP придет через колбэк MyWgTunnel.onStateChange

                // Запускаем сервис в Foreground режиме
                startForeground(NOTIFICATION_ID, createNotification(getString(R.string.vpn_status_connected)))

            } else {
                Log.e(TAG, "Failed to establish VPN interface (returned null).")
                vpnError.postValue("Failed to establish VPN interface.")
                tunnelStatus.postValue(Tunnel.State.DOWN)
                disconnectTunnelCleanup() // Попытка очистки без остановки сервиса сразу
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error establishing VPN or setting up GoBackend", e)
            vpnError.postValue("Error: ${e.localizedMessage ?: e.message ?: "Unknown error during connect"}")
            tunnelStatus.postValue(Tunnel.State.DOWN)
            disconnectTunnelCleanup()
        }
    }

    private fun disconnectTunnel() {
        Log.d(TAG, "Disconnecting tunnel by user/app request...")
        try {
            if (backend != null && currentTunnel != null) {
                Log.i(TAG, "Instructing GoBackend to bring tunnel ${currentTunnel!!.name} DOWN.")
                backend!!.setState(currentTunnel!!, Tunnel.State.DOWN, null)
                // Состояние DOWN должно прийти через колбэк MyWgTunnel и обновить tunnelStatus
            } else {
                Log.w(TAG, "Backend or tunnel not initialized, cannot command DOWN state.")
            }

            vpnInterface?.close()
            vpnInterface = null
            Log.i(TAG, "VPN interface ParcelFileDescriptor closed.")

        } catch (e: Exception) {
            Log.e(TAG, "Error during explicit disconnectTunnel", e)
            vpnError.postValue("Error during disconnect: ${e.localizedMessage}")
        } finally {
            // tunnelStatus должен обновиться через колбэк из GoBackend.
            // Если колбэк не пришел, или для надежности:
            if (tunnelStatus.value != Tunnel.State.DOWN) {
                tunnelStatus.postValue(Tunnel.State.DOWN)
            }
            stopForeground(true) // true - также убрать уведомление
            stopSelf() // Останавливаем сам сервис
            Log.i(TAG, "Service stop requested after disconnect.")
        }
    }

    /**
     * Используется для очистки ресурсов при ошибке подключения,
     * не останавливая сервис полностью сразу, чтобы дать возможность UI показать ошибку.
     */
    private fun disconnectTunnelCleanup() {
        Log.d(TAG, "Cleaning up tunnel resources after connection failure or revocation...")
        try {
            // Если currentTunnel был создан и передан в backend, но произошла ошибка,
            // можно попробовать отправить команду DOWN, хотя это может быть избыточно.
            if (backend != null && currentTunnel != null && tunnelStatus.value != Tunnel.State.DOWN) {
                try {
                    backend?.setState(currentTunnel!!, Tunnel.State.DOWN, null)
                } catch (be: Exception) {
                    Log.e(TAG, "Error sending final DOWN state to backend during cleanup", be)
                }
            }
            vpnInterface?.close()
            vpnInterface = null
        } catch (e: Exception) {
            Log.e(TAG, "Error during disconnectTunnelCleanup", e)
            // Не устанавливаем vpnError здесь, так как основная ошибка уже должна быть установлена
        } finally {
            if (tunnelStatus.value != Tunnel.State.DOWN) {
                tunnelStatus.postValue(Tunnel.State.DOWN) // Убедимся, что состояние DOWN
            }
            stopForeground(true)
            // Не вызываем stopSelf() здесь, так как это может быть вызвано из connectTunnel
            // и мы хотим, чтобы сервис оставался для показа ошибки.
            // stopSelf() будет вызван из disconnectTunnel() при явном отключении
            // или если connectTunnel решит полностью остановиться.
        }
    }


    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        // Очищаем ViewModelStore, когда сервис уничтожается
        _viewModelStore.clear()

        // Убедимся, что все ресурсы освобождены, если сервис уничтожается неожиданно
        // disconnectTunnel() уже должен был быть вызван, если остановка штатная.
        if (tunnelStatus.value == Tunnel.State.UP || vpnInterface != null) {
            Log.w(TAG, "onDestroy called with potentially active tunnel/interface, attempting cleanup.")
            disconnectTunnelCleanup() // Используем более мягкую очистку
        }
        serviceIsRunning.postValue(false)
        backend = null // Освобождаем backend
        currentTunnel = null
        Log.d(TAG, "Service destroyed.")
        super.onDestroy()
    }

    override fun onRevoke() {
        Log.w(TAG, "VPN revoked by user or system!")
        vpnError.postValue("VPN permission revoked by user/system.")
        // Пользователь отозвал разрешение VPN. Необходимо остановить туннель.
        disconnectTunnelCleanup() // Очищаем ресурсы
        stopSelf() // Останавливаем сервис
        super.onRevoke()
    }

    private fun createNotification(contentText: String): Notification {
        // Создаем канал уведомлений (для Android O и выше)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.vpn_notification_channel_name) // Добавьте в strings.xml
            val descriptionText = getString(R.string.vpn_notification_channel_description) // Добавьте в strings.xml
            val importance = NotificationManager.IMPORTANCE_LOW // Используйте LOW, чтобы избежать звука/вибрации
            val channel = NotificationChannel(VPN_SERVICE_CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }

        // Интент для открытия MainActivity при нажатии на уведомление
        val pendingIntent: PendingIntent =
            Intent(this, MainActivity::class.java).let { notificationIntent ->
                notificationIntent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP // Чтобы не создавать новую активити поверх
                PendingIntent.getActivity(this, 0, notificationIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }

        // Интент для кнопки "Отключить"
        val disconnectIntent = Intent(this, MyWgVpnService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPendingIntent: PendingIntent =
            PendingIntent.getService(this, 0, disconnectIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)


        return NotificationCompat.Builder(this, VPN_SERVICE_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name) + " VPN")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_vpn_notification) // Замените на свою иконку (например, из material icons)
            .setContentIntent(pendingIntent)
            .setOngoing(true) // Делает уведомление постоянным, пока сервис работает в foreground
            .setSilent(true) // Для NotificationManager.IMPORTANCE_LOW и выше, чтобы не было звука
            .addAction(R.drawable.ic_vpn_disconnect_notification, // Иконка для действия
                getString(R.string.vpn_disconnect_button), // Текст для кнопки
                disconnectPendingIntent)
            .build()
    }
}