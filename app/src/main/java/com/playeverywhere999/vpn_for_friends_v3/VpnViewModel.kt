package com.playeverywhere999.vpn_for_friends_v3
import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.playeverywhere999.vpn_for_friends_v3.util.Event // Ваш кастомный Event
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.BadConfigException
import com.wireguard.config.Config
import com.wireguard.config.InetEndpoint
import com.wireguard.config.InetNetwork
import com.wireguard.config.Interface // Для Interface.Builder()
import com.wireguard.config.Peer
import com.wireguard.crypto.Key
import com.wireguard.crypto.KeyFormatException
import com.wireguard.crypto.KeyPair
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.ANDROID as KtorLogger // Псевдоним во избежание конфликтов, если есть другой Logger
import io.ktor.client.plugins.logging.LogLevel as KtorLogLevel // Псевдоним
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.net.InetAddress
import io.ktor.client.plugins.logging.SIMPLE // Можно использовать как альтернативу, если AndroidLogger не сработает



// Модель данных для ответа от сервера
@Serializable
data class ServerConfigData(
    val clientAssignedAddress: String,
    val serverPublicKey: String,
    val serverEndpoint: String,
    val dnsServers: List<String>,
    val allowedIps: List<String>,
    val persistentKeepalive: Int? = null,
)

class VpnViewModel(application: Application) : AndroidViewModel(application) {

    private val _vpnState = MutableLiveData<Tunnel.State>(Tunnel.State.DOWN)
    val vpnState: LiveData<Tunnel.State> = _vpnState

    private val _requestPermissionsEvent = MutableLiveData<Event<Unit>>()
    val requestPermissionsEvent: LiveData<Event<Unit>> = _requestPermissionsEvent

    private val _lastErrorMessage = MutableLiveData<String?>()
    val lastErrorMessage: LiveData<String?> = _lastErrorMessage

    private val _preparedConfig = MutableLiveData<Config?>()
    val preparedConfig: LiveData<Config?> = _preparedConfig

    private val _configStatusMessage = MutableLiveData<String?>()
    val configStatusMessage: LiveData<String?> = _configStatusMessage

    private val _isConnectingAfterConfigFetch = MutableLiveData<Boolean>(false)

    private var clientKeyPair: KeyPair? = null // Для хранения сгенерированной пары ключей

    companion object {
        private const val TAG = "VpnViewModel"
        private const val API_SECRET_TOKEN = "hsdfbsKHHHGgyTFTygyguefj757y74rfwjfJUHYgydj8Iij;s;dffjTRERFdbT"
        const val DEFAULT_TUNNEL_NAME = "MyFriendsVPN"
    }

    fun onConnectDisconnectClicked() {
        Log.d(TAG, "onConnectDisconnectClicked called. Current state: ${vpnState.value}, Config exists: ${preparedConfig.value != null}")
        _lastErrorMessage.postValue(null)

        when (vpnState.value) {
            Tunnel.State.DOWN, null -> {
                if (preparedConfig.value == null) {
                    _isConnectingAfterConfigFetch.value = true
                    startConfigGenerationAndFetch()
                } else {
                    _isConnectingAfterConfigFetch.value = false
                    startVpnWithCurrentConfig()
                }
            }
            Tunnel.State.UP -> {
                _isConnectingAfterConfigFetch.value = false
                stopVpn()
            }
            Tunnel.State.TOGGLE -> {
                // Если мы в TOGGLE и есть ошибка, то onConnectDisconnectClicked должен попытаться снова.
                // Это может произойти, если разрешения были запрошены, но сервис не запустился.
                if(!lastErrorMessage.value.isNullOrEmpty()){
                    Log.d(TAG, "VPN is TOGGLE but has error, attempting to reconnect/reconfigure.")
                    _isConnectingAfterConfigFetch.value = true // Предполагаем, что нужно получить конфиг и подключиться
                    _preparedConfig.postValue(null) // Сбрасываем конфиг на всякий случай, чтобы получить свежий
                    startConfigGenerationAndFetch()
                } else {
                    Log.d(TAG, "VPN is already toggling, click ignored.")
                    _configStatusMessage.postValue("Processing, please wait...")
                }
            }
        }
    }

    fun startConfigGenerationAndFetch() {
        val currentStatus = _configStatusMessage.value
        if (currentStatus?.contains("Contacting server", ignoreCase = true) == true ||
            currentStatus?.contains("Generating client keys", ignoreCase = true) == true ||
            (_isConnectingAfterConfigFetch.value == true && _preparedConfig.value != null && _vpnState.value != Tunnel.State.DOWN)
        ) {
            Log.d(TAG, "Config generation/fetch or auto-connect already in progress or not in DOWN state.")
            if (_isConnectingAfterConfigFetch.value == true && (_vpnState.value == Tunnel.State.UP || _vpnState.value == Tunnel.State.TOGGLE)) {
                Log.d(TAG, "Auto-connect flag is set, but VPN is already UP/TOGGLE. Resetting flag.")
                _isConnectingAfterConfigFetch.value = false
            }
            return
        }

        viewModelScope.launch {
            _lastErrorMessage.postValue(null)
            _preparedConfig.postValue(null) // Всегда получаем новый конфиг при вызове этого метода
            _configStatusMessage.postValue("Generating client keys...")
            Log.d(TAG, "Starting config generation and fetch process.")

            try {
                val newClientKeyPair = withContext(Dispatchers.Default) { KeyPair() }
                clientKeyPair = newClientKeyPair
                val clientPublicKeyForServer = newClientKeyPair.publicKey.toBase64()
                // Приватный ключ используется только для локальной сборки конфига
                // val clientPrivateKeyForConfig = newClientKeyPair.privateKey.toBase64() // Не логгировать!

                Log.d(TAG, "Client keys generated. Public Key (for server): $clientPublicKeyForServer")
                _configStatusMessage.postValue("Client keys generated. Contacting server...")

                val serverDataActual: ServerConfigData? = fetchConfigFromRealApi(clientPublicKeyForServer)

                if (serverDataActual != null) {
                    Log.d(TAG, "Server data received: $serverDataActual")
                    _configStatusMessage.postValue("Configuration received. Preparing tunnel...")

                    // Используем приватный ключ из newClientKeyPair для сборки
                    val finalConfig = buildClientConfig(newClientKeyPair, serverDataActual)

                    _preparedConfig.postValue(finalConfig)
                    _configStatusMessage.postValue("Configuration ready!")
                    Log.d(TAG, "WireGuard config prepared successfully.")
                    // Log.d(TAG, "Final Config String: ${finalConfig.toWgQuickString()}") // Для отладки

                    if (_isConnectingAfterConfigFetch.value == true) {
                        Log.d(TAG, "Auto-connecting after config fetch.")
                        _isConnectingAfterConfigFetch.value = false
                        startVpnWithCurrentConfig()
                    }
                } else {
                    // Ошибка уже должна быть обработана в fetchConfigFromRealApi и _configStatusMessage обновлен
                    // _preparedConfig уже null
                    _isConnectingAfterConfigFetch.value = false
                    Log.e(TAG, "Failed to get server configuration (serverDataActual is null).")
                    // Дополнительно убедимся, что configStatusMessage отражает ошибку, если fetchConfigFromRealApi не установил
                    if (!(_configStatusMessage.value?.contains("Error", ignoreCase = true) == true)) {
                        _configStatusMessage.postValue("Error: Failed to get server data.")
                    }
                }

            } catch (e: KeyFormatException) {
                Log.e(TAG, "Error processing keys", e)
                _lastErrorMessage.postValue("Error: Invalid key format. ${e.message}")
                _configStatusMessage.postValue("Error: Invalid key format.")
                _preparedConfig.postValue(null)
                _isConnectingAfterConfigFetch.value = false
            } catch (e: BadConfigException) {
                Log.e(TAG, "Error parsing config data (IP, Endpoint, etc.)", e)
                _lastErrorMessage.postValue("Error: Bad config string. ${e.message}")
                _configStatusMessage.postValue("Error: Bad config string.")
                _preparedConfig.postValue(null)
                _isConnectingAfterConfigFetch.value = false
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "Error with configuration arguments", e)
                _lastErrorMessage.postValue("Error: Invalid argument in config. ${e.message}")
                _configStatusMessage.postValue("Error: Invalid argument in config.")
                _preparedConfig.postValue(null)
                _isConnectingAfterConfigFetch.value = false
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error in config generation/fetch", e)
                _lastErrorMessage.postValue("Error: ${e.message ?: "Failed to get configuration"}")
                _configStatusMessage.postValue("Error: Unexpected issue during config setup.")
                _preparedConfig.postValue(null)
                _isConnectingAfterConfigFetch.value = false
            }
        }
    }


    private suspend fun fetchConfigFromRealApi(clientPublicKeyForServer: String): ServerConfigData? {
        val client = HttpClient(Android) {
            install(ContentNegotiation) {
                json(Json {
                    prettyPrint = true
                    isLenient = true
                    ignoreUnknownKeys = true // Оставим true на время тестирования
                })
            }
            // Добавляем Logging для отладки Ktor запросов
            install(Logging) {
                logger = object : io.ktor.client.plugins.logging.Logger {
                    override fun log(message: String) {
                        android.util.Log.d("ktor_client", message)
                    }
                }
                level = io.ktor.client.plugins.logging.LogLevel.ALL   // Явно указываем полный путь к Ktor LogLevel
            }
            engine {
                connectTimeout = 10_000 // 10 секунд
                socketTimeout = 10_000  // 10 секунд
            }
        }

        // ЗАМЕНИТЕ ЭТОТ URL НА ВАШ URL, СГЕНЕРИРОВАННЫЙ MOCKY ИЛИ ДРУГИМ МОК-СЕРВИСОМ
        var apiUrl = "ВАШ_MOCKY_URL_ИЛИ_ДРУГОЙ_МОК_URL_СЮДА"
        // Пример: apiUrl = "https://run.mocky.io/v3/your-generated-id"

        if (apiUrl == "ВАШ_MOCKY_URL_ИЛИ_ДРУГОЙ_МОК_URL_СЮДА") {
            Log.e(TAG, "fetchConfigFromRealApi: API URL is not set. Please replace placeholder with your mock URL.")
            _lastErrorMessage.postValue("Internal Error: API URL not configured for testing.")
            _configStatusMessage.postValue("Error: API URL not set.")
            return null
        }

        Log.d(TAG, "Attempting to fetch config from: $apiUrl with public key: $clientPublicKeyForServer")
        _configStatusMessage.postValue("Contacting server for config...")

        // --- НАЧАЛО ЗАГЛУШКИ ДЛЯ ТЕСТИРОВАНИЯ БЕЗ РЕАЛЬНОГО БЭКЕНДА --- (ТЕПЕРЬ ЗАКОММЕНТИРОВАНО)
        /*
        Log.d(TAG, "USING MOCK SERVER RESPONSE FOR TESTING!")
        delay(1500) // Имитация задержки сети
        // Успешный мок-ответ:
        return ServerConfigData(
            clientAssignedAddress = "10.100.0.2/32", // Пример
            serverPublicKey = "SERVER_PUBLIC_KEY_REPLACE_ME", // ЗАМЕНИТЕ НА РЕАЛЬНЫЙ ПУБЛИЧНЫЙ КЛЮЧ ВАШЕГО СЕРВЕРA WG
            serverEndpoint = "123.123.123.123:51820",      // ЗАМЕНИТЕ НА РЕАЛЬНЫЙ IP:PORT ВАШЕГО СЕРВЕРА WG
            dnsServers = listOf("1.1.1.1", "8.8.8.8"),
            allowedIps = listOf("0.0.0.0/0", "::/0"),
            persistentKeepalive = 25
        )
        // Мок-ответ с ошибкой сервера:
        // _lastErrorMessage.postValue("Mock Error: Server unavailable (503)")
        // _configStatusMessage.postValue("Error: Mock Server unavailable.")
        // return null
        */
        // --- КОНЕЦ ЗАГЛУШКИ ДЛЯ ТЕСТИРОВАНИЯ ---


        // --- НАЧАЛО РЕАЛЬНОГО KTOR ЗАПРОСА (ТЕПЕРЬ РАСКОММЕНТИРОВАНО) ---
        return try {
            val response: ServerConfigData = client.post(apiUrl) {
                contentType(io.ktor.http.ContentType.Application.Json) // Явно указываем полный путь к Ktor ContentType
                headers {
                    // Если ваш мок-сервер не требует токен, эту строку можно оставить закомментированной
                    // или удалить. Для реального API, вероятно, понадобится.
                    // append("Authorization", "Bearer $API_SECRET_TOKEN")
                }
                setBody(mapOf("client_public_key" to clientPublicKeyForServer))
            }.body()

            Log.d(TAG, "Successfully fetched and parsed config from API: $response")
            _configStatusMessage.postValue("Configuration received from server.") // Обновляем статус
            response
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching or parsing config from API", e)
            when (e) {
                is ClientRequestException -> {
                    val errorBody = try { e.response.bodyAsText() } catch (ex: Exception) { "No error body" }
                    Log.e(TAG, "API Client Error: ${e.response.status} - ${e.message}. Body: $errorBody")
                    if (e.response.status.value == 401) {
                        _lastErrorMessage.postValue("API Error: Authorization Failed (401).")
                        _configStatusMessage.postValue("Error: Authorization Failed.")
                    } else {
                        _lastErrorMessage.postValue("API Error: ${e.response.status.description} (${e.response.status.value}). Details: $errorBody")
                        _configStatusMessage.postValue("Error: Server responded ${e.response.status.value}.")
                    }
                }
                is ServerResponseException -> {
                    val errorBody = try { e.response.bodyAsText() } catch (ex: Exception) { "No error body" }
                    Log.e(TAG, "API Server Error: ${e.response.status} - ${e.message}. Body: $errorBody")
                    _lastErrorMessage.postValue("API Server Error: ${e.response.status.description} (${e.response.status.value}). Details: $errorBody")
                    _configStatusMessage.postValue("Error: Server error ${e.response.status.value}.")
                }
                is SerializationException -> {
                    Log.e(TAG, "API Response JSON parsing error", e)
                    _lastErrorMessage.postValue("API Error: Could not parse server response. ${e.localizedMessage}")
                    _configStatusMessage.postValue("Error: Invalid server response format.")
                }
                is java.net.UnknownHostException -> {
                    Log.e(TAG, "API Error: Unknown Host", e)
                    _lastErrorMessage.postValue("API Error: Unable to resolve host ($apiUrl).")
                    _configStatusMessage.postValue("Error: Unknown host.")
                }
                is java.net.ConnectException, is io.ktor.client.network.sockets.ConnectTimeoutException -> {
                    Log.e(TAG, "API Error: Connection Failed/Timeout to $apiUrl", e)
                    _lastErrorMessage.postValue("API Error: Could not connect to server.")
                    _configStatusMessage.postValue("Error: Connection failed/timeout.")
                }
                else -> {
                    Log.e(TAG, "API Error: Unexpected error", e)
                    _lastErrorMessage.postValue("API Error: ${e.message ?: "Unexpected error"}")
                    _configStatusMessage.postValue("Error: Failed to get server config.")
                }
            }
            null
        } finally {
            client.close()
        }
        // --- КОНЕЦ РЕАЛЬНОГО KTOR ЗАПРОСА ---
    }
    private fun buildClientConfig(
        clientKeys: KeyPair,
        serverData: ServerConfigData,
    ): Config {
        val interfaceBuilder = Interface.Builder()
            .setKeyPair(clientKeys)
            .addAddress(InetNetwork.parse(serverData.clientAssignedAddress))

        serverData.dnsServers.forEach { dnsString -> // dnsString это, например, "1.1.1.1"
            try {
                val dnsInetAddress: InetAddress = InetAddress.getByName(dnsString.trim()) // Получаем объект InetAddress
                interfaceBuilder.addDnsServer(dnsInetAddress) // <--- ИСПРАВЛЕНО: передаем объект InetAddress
            } catch (e: java.net.UnknownHostException) { // Лучше ловить более конкретное исключение
                Log.w(TAG, "Invalid or unknown DNS server host: $dnsString", e)
            } catch (e: Exception) { // Общий обработчик для других возможных проблем
                Log.w(TAG, "Error processing DNS server: $dnsString", e)
            }
        }

        val peerBuilder = Peer.Builder()
        // ... (остальная часть вашего кода) ...

        return Config.Builder()
            .setInterface(interfaceBuilder.build())
            .addPeer(peerBuilder.build())
            .build()
    }

    private fun startVpnWithCurrentConfig() {
        val currentConfig = preparedConfig.value
        if (currentConfig == null) {
            Log.e(TAG, "Cannot start VPN: config is null. Attempting to re-fetch.")
            _lastErrorMessage.postValue("Configuration not available. Please try again.")
            _configStatusMessage.postValue("Error: Config not ready.")
            _vpnState.postValue(Tunnel.State.DOWN)
            // Инициируем получение конфига снова, если его нет
            _isConnectingAfterConfigFetch.value = true
            startConfigGenerationAndFetch()
            return
        }

        val vpnPrepareIntent = VpnService.prepare(getApplication())
        val notificationsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                getApplication(),
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        if (vpnPrepareIntent != null || !notificationsGranted) {
            Log.d(TAG, "Permissions needed. VPN Prepare: ${vpnPrepareIntent != null}, Notifications Granted: $notificationsGranted. Signalling Activity.")
            _requestPermissionsEvent.postValue(Event(Unit))
            return
        }

        Log.d(TAG, "Attempting to start VPN with current config (permissions granted).")
        // Используем MyWgVpnService.newIntent для создания Intent
        val intent = MyWgVpnService.newIntent(
            context = getApplication(),
            action = MyWgVpnService.ACTION_CONNECT,
            configString = currentConfig.toWgQuickString(), // Преобразуем Config в строку
            tunnelName = DEFAULT_TUNNEL_NAME
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getApplication<Application>().startForegroundService(intent)
            } else {
                getApplication<Application>().startService(intent)
            }
            setIntermediateVpnState(Tunnel.State.TOGGLE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN service", e)
            _lastErrorMessage.postValue("Error: Could not start VPN service. ${e.message}")
            _vpnState.postValue(Tunnel.State.DOWN)
        }
    }

    private fun stopVpn() {
        Log.d(TAG, "Attempting to stop VPN.")
        // Используем MyWgVpnService.newIntent для создания Intent
        val intent = MyWgVpnService.newIntent(
            context = getApplication(),
            action = MyWgVpnService.ACTION_DISCONNECT,
            tunnelName = DEFAULT_TUNNEL_NAME
            // configString не нужен для ACTION_DISCONNECT
        )
        try {
            getApplication<Application>().startService(intent)
            setIntermediateVpnState(Tunnel.State.TOGGLE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send stop command to VPN service", e)
            _lastErrorMessage.postValue("Error: Could not stop VPN service. ${e.message}")
            // Состояние должно обновиться из сервиса. Если сервис "завис", это проблема.
        }
    }

    fun clearLastError() {
        _lastErrorMessage.postValue(null)
        // Можно также сбрасывать _configStatusMessage, если он содержит сообщение об ошибке
        if (_configStatusMessage.value?.contains("Error", ignoreCase = true) == true) {
            _configStatusMessage.postValue(null)
        }
    }

    fun setExternalVpnState(newState: Tunnel.State, errorMessage: String? = null) {
        Log.d(TAG, "setExternalVpnState: newState=$newState, current _vpnState=${_vpnState.value}, error=$errorMessage")
        _vpnState.postValue(newState)

        if (newState == Tunnel.State.DOWN && !errorMessage.isNullOrEmpty()) {
            _lastErrorMessage.postValue(errorMessage)
            _configStatusMessage.postValue("VPN Disconnected: Error") // Краткое сообщение об ошибке
        } else if (newState == Tunnel.State.UP) {
            _lastErrorMessage.postValue(null) // Очищаем старые ошибки при успешном подключении
            _configStatusMessage.postValue("VPN Connected")
        } else if (newState == Tunnel.State.DOWN) {
            // Если просто отключились без явной ошибки от сервиса, не перезаписываем _lastErrorMessage
            // _configStatusMessage.postValue("VPN Disconnected") // Можно оставить, если нужно
        }
        // Для TOGGLE, _configStatusMessage обычно устанавливается в "Processing..." методом setIntermediateVpnState
    }

    fun setIntermediateVpnState(intermediateState: Tunnel.State) {
        _vpnState.postValue(intermediateState)
        if (intermediateState == Tunnel.State.TOGGLE) {
            _configStatusMessage.postValue("Processing...")
        }
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "VpnViewModel cleared.")
        // Здесь можно отменить длительные операции, если они не в viewModelScope,
        // но viewModelScope автоматически отменяет свои корутины.
    }
}
