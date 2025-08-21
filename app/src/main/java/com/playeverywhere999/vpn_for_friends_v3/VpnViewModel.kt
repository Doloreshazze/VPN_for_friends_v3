package com.playeverywhere999.vpn_for_friends_v3

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.InetEndpoint
import com.wireguard.config.InetNetwork
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import com.wireguard.crypto.Key
import java.net.InetAddress
import com.wireguard.config.BadConfigException
import com.wireguard.crypto.KeyFormatException
import com.wireguard.crypto.KeyPair




class VpnViewModel(application: Application) : AndroidViewModel(application) {

    private val _vpnState = MutableLiveData<Tunnel.State>(Tunnel.State.DOWN)
    val vpnState: LiveData<Tunnel.State> = _vpnState

    private val _lastErrorMessage = MutableLiveData<String?>()
    val lastErrorMessage: LiveData<String?> = _lastErrorMessage

    // LiveData для хранения готовой конфигурации WireGuard
    private val _preparedConfig = MutableLiveData<Config?>()
    val preparedConfig: LiveData<Config?> = _preparedConfig

    // Token to request config from server
    private val _token = MutableLiveData<String>("")
    val token: LiveData<String> = _token

    // Loading indicator for config fetch
    private val _isFetchingConfig = MutableLiveData<Boolean>(false)
    val isFetchingConfig: LiveData<Boolean> = _isFetchingConfig

    private val repository: ServerRepository = ServerRepository(BuildConfig.SERVER_BASE_URL)

    private var backend: GoBackend? = null // GoBackend теперь может инициализироваться и использоваться в сервисе
    // currentTunnel и currentWireGuardConfig теперь в основном для createAndPrepareConfig
    // или если ViewModel напрямую управляет бэкендом (что мы меняем)
    private var currentTunnelForPreparation: MyWgTunnel? = null
    private var currentWireGuardConfigForPreparation: Config? = null


    companion object {
        private const val TAG = "VpnViewModel"
        private const val TUNNEL_NAME_FOR_PREP = "ConfigPrepTunnel" // Имя для временного туннеля при подготовке конфига
    }

    init {
        // Инициализация GoBackend здесь может быть избыточной, если сервис будет им управлять.
        // Оставим пока для createAndPrepareConfig, если он использует backend.
        // Или сервис будет создавать свой экземпляр GoBackend.
        // Если сервис будет использовать этот же backend, нужна осторожность с жизненным циклом.
        // Проще, если сервис создает свой backend и tunnel.
        // Пока что удалим инициализацию backend здесь, чтобы избежать конфликтов.
        // backend = GoBackend(application.applicationContext)
        // Log.d(TAG, "GoBackend potentially initialized in ViewModel.")
    }

    fun setToken(newToken: String) {
        _token.postValue(newToken)
    }

    fun createAndPrepareConfig() {
        // [Interface]
        val clientPrivateKeyString = "uDVWKj5nPZFLqZS0I3/R58LZnJBfCGsVvo2Ds4BD8XE=" // Sample only; prefer server
        val clientAddressString = "10.8.0.2/32"
        val dnsServersList = listOf("1.1.1.1", "1.0.0.1")
        val interfaceMtu = 1380

        // [Peer]
        val serverPublicKeyString = "GkIxytsc2pBgEl9n7s3rGmaTxGxNB+5bO00rwasgUDY"
        val serverEndpointString = "144.91.74.177:51820"
        val allowedIpsList = listOf("0.0.0.0/0", "::/0")
        val peerPersistentKeepalive = 25


        try {
            val clientPrivateKey = Key.fromBase64(clientPrivateKeyString)
            val serverPublicKey = Key.fromBase64(serverPublicKeyString)
            val clientKeyPair = KeyPair(clientPrivateKey)
            Log.d("VpnViewModel", "Client Public Key: ${clientKeyPair.publicKey.toBase64()}")
            val clientAddress = InetNetwork.parse(clientAddressString)

            val interfaceBuilder = Interface.Builder()
                .addAddress(clientAddress)
            dnsServersList.forEach { dnsStr ->
                interfaceBuilder.addDnsServer(InetAddress.getByName(dnsStr))
            }
            interfaceBuilder
                .setKeyPair(clientKeyPair)
                .setMtu(interfaceMtu) // Используем переменную

            val peerBuilder = Peer.Builder()
                .setPublicKey(serverPublicKey)
            allowedIpsList.forEach { ipStr ->
                peerBuilder.addAllowedIp(InetNetwork.parse(ipStr))
            }
            peerBuilder
                .setEndpoint(InetEndpoint.parse(serverEndpointString)) // Парсим здесь
                .setPersistentKeepalive(peerPersistentKeepalive) // Используем переменную

            val config = Config.Builder()
                .setInterface(interfaceBuilder.build())
                .addPeer(peerBuilder.build())
                .build()
            Log.d("VpnViewModel", "Config is created")

            currentWireGuardConfigForPreparation = config
            _preparedConfig.postValue(config)
            Log.d(TAG, "WireGuard config created and posted successfully.")
            _lastErrorMessage.postValue(null)

        } catch (e: KeyFormatException) {
            Log.e(TAG, "Error creating WireGuard config: Invalid key format", e)
            _lastErrorMessage.postValue("Error: Invalid key format. ${e.message}")
            _preparedConfig.postValue(null)
        } catch (e: BadConfigException) {
            Log.e(TAG, "Error creating WireGuard config: Bad configuration string", e)
            _lastErrorMessage.postValue("Error: Bad config string (IP, Endpoint). ${e.message}")
            _preparedConfig.postValue(null)
        } catch (e: java.net.UnknownHostException) {
            Log.e(TAG, "Error creating WireGuard config: Unknown DNS host", e)
            _lastErrorMessage.postValue("Error: Unknown DNS host. ${e.message}")
            _preparedConfig.postValue(null)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating WireGuard config", e)
            _lastErrorMessage.postValue("Error creating config: ${e.message}")
            _preparedConfig.postValue(null)
        }
    }

    fun fetchConfigByTokenAndPrepare(onSuccess: (() -> Unit)? = null) {
        val currentToken = _token.value?.trim().orEmpty()
        if (currentToken.isEmpty()) {
            _lastErrorMessage.postValue("Token is empty")
            return
        }
        _isFetchingConfig.postValue(true)
        Thread {
            try {
                val configString = repository.fetchWireGuardConfigByToken(currentToken)
                val cfg = Config.parse(configString.reader().buffered())
                currentWireGuardConfigForPreparation = cfg
                _preparedConfig.postValue(cfg)
                _lastErrorMessage.postValue(null)
                onSuccess?.invoke()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch/parse config by token", e)
                _preparedConfig.postValue(null)
                _lastErrorMessage.postValue("Failed to fetch config: ${'$'}{e.message}")
            } finally {
                _isFetchingConfig.postValue(false)
            }
        }.start()
    }


    fun clearLastError() {
        _lastErrorMessage.postValue(null) // или .value = null, если вы уверены, что вызывается из главного потока
    }

    // Этот метод теперь вызывается из MainActivity, когда сервис сообщает о новом состоянии или ошибке
    fun setExternalVpnState(newState: Tunnel.State, errorMessage: String?) {
        Log.d(TAG, "setExternalVpnState: NewState=$newState, Error=$errorMessage, CurrentState=${_vpnState.value}")

        // Если пришла ошибка, и состояние не DOWN, устанавливаем DOWN
        if (errorMessage != null) {
            _lastErrorMessage.postValue(errorMessage)
            if (_vpnState.value != Tunnel.State.DOWN) {
                _vpnState.postValue(Tunnel.State.DOWN)
            }
            return // Ошибка имеет приоритет
        }

        // Если ошибки нет, обновляем состояние и сбрасываем ошибку
        if (_vpnState.value != newState) { // Обновляем, только если состояние изменилось
            _vpnState.postValue(newState)
        }
        if (_lastErrorMessage.value != null && newState == Tunnel.State.UP) { // Сбрасываем ошибку при успешном UP
            _lastErrorMessage.postValue(null)
        }
    }


    // Метод для установки промежуточного состояния (например, TOGGLE) из MainActivity
    // перед отправкой команды сервису. Сервис потом пришлет окончательное состояние.
    fun setIntermediateVpnState(intermediateState: Tunnel.State) {
        if (intermediateState == Tunnel.State.TOGGLE) {
            Log.d(TAG, "Setting intermediate state to TOGGLE. Current: ${_vpnState.value}")
            if (_vpnState.value != Tunnel.State.UP && _vpnState.value != Tunnel.State.DOWN) {
                // Если мы уже в стабильном состоянии, TOGGLE не нужен,
                // сервис сам переведет в нужное состояние.
                // Этот метод полезен, если UI должен немедленно показать "Connecting..."
            }
            // Всегда очищаем ошибку при новой попытке действия
            if (_lastErrorMessage.value != null) {
                _lastErrorMessage.postValue(null)
            }
            // Устанавливаем TOGGLE, только если текущее состояние не является целевым стабильным состоянием
            // и не является уже TOGGLE.
            val currentState = _vpnState.value
            if (currentState != Tunnel.State.UP && currentState != Tunnel.State.DOWN && currentState != Tunnel.State.TOGGLE) {
                _vpnState.postValue(Tunnel.State.TOGGLE)
            } else if ( (currentState == Tunnel.State.DOWN && _vpnState.value != Tunnel.State.UP) || // Хотим вверх из DOWN
                (currentState == Tunnel.State.UP && _vpnState.value != Tunnel.State.DOWN) ) { // Хотим вниз из UP
                _vpnState.postValue(Tunnel.State.TOGGLE)
            }


        } else {
            Log.w(TAG, "setIntermediateVpnState called with non-TOGGLE state: $intermediateState. This is not its intended use.")
            _vpnState.postValue(intermediateState) // На всякий случай, но не рекомендуется
        }
    }

    // Старый метод setTunnelState, который напрямую работал с backend, больше не нужен в таком виде.
    // Оставляем его закомментированным для истории или если понадобится логика.
    /*
    fun setTunnelState(targetState: Tunnel.State) {
        // ... старая логика ...
    }
    */

    // resetConfigAndTunnel больше не актуален в таком виде, так как сервис управляет туннелем.
    // ViewModel только готовит конфиг.
    /*
    private fun resetConfigAndTunnel() {
        currentWireGuardConfigForPreparation = null
        // currentTunnelForPreparation = null // Экземпляр туннеля теперь в сервисе
        _preparedConfig.postValue(null) // Очищаем подготовленный конфиг
        // Не меняем _vpnState здесь, это делает сервис
    }
    */

    override fun onCleared() {
        super.onCleared()
        // ViewModel очищается, но сервис может продолжать работать.
        // Остановка сервиса должна управляться из MainActivity или самим сервисом.
        Log.d(TAG, "VpnViewModel cleared.")
        // backend = null // Если backend создается и управляется сервисом, здесь его трогать не нужно.
    }
}