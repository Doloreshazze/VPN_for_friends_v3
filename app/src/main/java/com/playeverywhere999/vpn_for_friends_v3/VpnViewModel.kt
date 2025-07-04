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

    fun createAndPrepareConfig() {
        val clientPrivateKeyString = "yBEoBcecxi0aM0pAaoW6wzXZDvhsTtDpeB/k2kSsf0o="
        val clientAddressString = "10.0.1.2/32"
        val dnsServerString = "1.1.1.1"
        val serverPublicKeyString = "GkIxytsc2pBgEl9n7s3rGmaTxGxNB+5bO00rwasgUDY="
        val serverEndpointString = "144.91.74.177:51820"
        val allowedIpsString = "0.0.0.0/0"


        /*if (clientPrivateKeyString == "qMUVBNevBEcl1U8fAdOOT5K6QKRy+2XXGNGEKnC2Blk=" ||
            serverPublicKeyString == "k6eSEKXjyj+awlgCqLX2q8e8Y9YZox8nzYHh31p5THs=" ||
            serverEndpointString == "10.0.1.2/32" // Пример неверного значения для проверки
        ) {
            Log.e(TAG, "Placeholder keys/endpoint found. Please replace them in VpnViewModel.")
            _lastErrorMessage.postValue("Error: Default keys or server address not replaced.")
            _preparedConfig.postValue(null)
            // resetConfigAndTunnel() // Этот метод тоже изменится
            return
        }*/

        try {
            val clientPrivateKey = Key.fromBase64(clientPrivateKeyString)
            val serverPublicKey = Key.fromBase64(serverPublicKeyString)
            val clientKeyPair = KeyPair(clientPrivateKey)
            Log.d("VpnViewModel", "Client Public Key: ${clientKeyPair.publicKey.toBase64()}")
            val clientAddress = InetNetwork.parse(clientAddressString)
            val dnsServer = InetAddress.getByName(dnsServerString)
            val allowedIps = InetNetwork.parse(allowedIpsString)
            val serverEndpoint = InetEndpoint.parse(serverEndpointString)

            val interfaceBuilder = Interface.Builder()
                .addAddress(clientAddress)
                .addDnsServer(dnsServer)
                .setKeyPair(clientKeyPair)
                .setMtu(1420)

            val peerBuilder = Peer.Builder()
                .setPublicKey(serverPublicKey)
                .addAllowedIp(allowedIps)
                .setEndpoint(serverEndpoint)
                .setPersistentKeepalive(25)

            val config = Config.Builder()
                .setInterface(interfaceBuilder.build())
                .addPeer(peerBuilder.build())
                .build()
            Log.d("VpnViewModel", "Config is created")

            currentWireGuardConfigForPreparation = config // Сохраняем для возможного использования
            _preparedConfig.postValue(config) // Публикуем готовую конфигурацию
            Log.d(TAG, "WireGuard config created and posted successfully.")
            _lastErrorMessage.postValue(null) // Сбрасываем ошибку, если была

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