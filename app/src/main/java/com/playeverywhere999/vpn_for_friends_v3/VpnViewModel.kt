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
import com.playeverywhere999.vpn_for_friends_v3.util.Event
import com.playeverywhere999.vpn_for_friends_v3.BuildConfig
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.BadConfigException
import com.wireguard.config.Config
import com.wireguard.config.InetEndpoint
import com.wireguard.config.InetNetwork
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import com.wireguard.crypto.Key
import com.wireguard.crypto.KeyFormatException
import com.wireguard.crypto.KeyPair
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.InetAddress

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

    private var clientKeyPair: KeyPair? = null
    private var configFetchRetryCount = 0

    companion object {
        private const val TAG = "VpnViewModel"
        const val DEFAULT_TUNNEL_NAME = "MyFriendsVPN"
        private const val MAX_CONFIG_FETCH_RETRIES = 2
        private const val RETRY_DELAY_MS = 2500L
    }

    private val API_SECRET_TOKEN: String by lazy {
        try {
            val token = BuildConfig.API_SECRET_TOKEN
            if (token == "MISSING_TOKEN_CHECK_LOCAL_PROPERTIES" || token.isBlank()) {
                Log.e(TAG, "API_SECRET_TOKEN from BuildConfig is a placeholder or empty. Check local.properties!")
                "FALLBACK_PLACEHOLDER_TOKEN_ERROR_IN_SETUP"
            } else {
                token
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load API_SECRET_TOKEN from BuildConfig, falling back to placeholder.", e)
            "FALLBACK_PLACEHOLDER_TOKEN_EXCEPTION"
        }
    }

    fun onConnectDisconnectClicked() {
        Log.d(TAG, "onConnectDisconnectClicked called. Current state: ${_vpnState.value}, Config exists: ${_preparedConfig.value != null}")
        when (_vpnState.value) {
            Tunnel.State.DOWN, null -> {
                _lastErrorMessage.value = null
                _configStatusMessage.value = null
                configFetchRetryCount = 0
                if (_preparedConfig.value == null) {
                    _isConnectingAfterConfigFetch.value = true
                    startConfigGenerationAndFetch()
                } else {
                    _isConnectingAfterConfigFetch.value = false
                    startVpnWithCurrentConfig()
                }
            }
            Tunnel.State.UP -> {
                _isConnectingAfterConfigFetch.value = false
                _configStatusMessage.value = null
                stopVpn()
            }
            Tunnel.State.TOGGLE -> {
                if (!_lastErrorMessage.value.isNullOrEmpty()) {
                    Log.d(TAG, "VPN is TOGGLE but has error, attempting to reconnect/reconfigure.")
                    _lastErrorMessage.value = null
                    _configStatusMessage.value = null
                    configFetchRetryCount = 0
                    _isConnectingAfterConfigFetch.value = true
                    _preparedConfig.value = null
                    startConfigGenerationAndFetch()
                } else {
                    Log.d(TAG, "VPN is already toggling, click ignored.")
                    _configStatusMessage.value = "Processing, please wait..."
                }
            }
        }
    }

    fun startConfigGenerationAndFetch() {
        Log.d(TAG, "startConfigGenerationAndFetch: CALLED. Initial _lastErrorMessage: '${lastErrorMessage.value}', configFetchRetryCount: $configFetchRetryCount")
        viewModelScope.launch {
            var success = false
            if (configFetchRetryCount == 0) {
                _lastErrorMessage.value = null
            }

            var lastAttemptNumber = 0
            while (configFetchRetryCount <= MAX_CONFIG_FETCH_RETRIES && !success) {
                val currentAttempt = configFetchRetryCount + 1
                lastAttemptNumber = currentAttempt
                _preparedConfig.value = null

                if (configFetchRetryCount > 0) {
                    _configStatusMessage.value = "Retrying config fetch ($currentAttempt/${MAX_CONFIG_FETCH_RETRIES + 1})..."
                    Log.d(TAG, "Retrying config fetch. Attempt: $currentAttempt. Delaying for $RETRY_DELAY_MS ms.")
                    delay(RETRY_DELAY_MS)
                } else {
                    _configStatusMessage.value = "Generating client keys..."
                }
                Log.d(TAG, "Starting config generation and fetch process. Attempt: $currentAttempt")

                try {
                    val newClientKeyPair = withContext(Dispatchers.Default) { KeyPair() }
                    clientKeyPair = newClientKeyPair
                    val clientPublicKeyForServer = newClientKeyPair.publicKey.toBase64()

                    if (configFetchRetryCount == 0 && currentAttempt == 1) _configStatusMessage.value = "Client keys generated. Contacting server..."
                    Log.d(TAG, "Client keys generated for attempt $currentAttempt. Public Key (for server): $clientPublicKeyForServer")

                    val serverDataActual: ServerConfigData? = fetchConfigFromRealApi(clientPublicKeyForServer)

                    if (serverDataActual != null) {
                        Log.d(TAG, "Server data received on attempt $currentAttempt: $serverDataActual")
                        _configStatusMessage.value = "Configuration received. Preparing tunnel..."
                        val finalConfig = withContext(Dispatchers.IO) {
                            buildClientConfig(newClientKeyPair, serverDataActual)
                        }
                        _preparedConfig.value = finalConfig
                        _configStatusMessage.value = "Configuration ready!"
                        Log.d(TAG, "WireGuard config prepared successfully on attempt $currentAttempt.")

                        success = true

                        val shouldConnectAfterThisFetch = _isConnectingAfterConfigFetch.value == true

                        if (shouldConnectAfterThisFetch) {
                            Log.d(TAG, "Configuration fetched. Auto-connecting as requested...")
                            startVpnWithCurrentConfig()
                        }

                        _isConnectingAfterConfigFetch.value = false

                    } else {
                        Log.e(TAG, "Failed to get server configuration (serverDataActual is null) on attempt $currentAttempt.")
                        handleConfigFetchError("Failed to get server config from API.", null, currentAttempt)
                    }

                } catch (e: KeyFormatException) {
                    handleConfigFetchError("Error: Invalid key format. ${e.message}", e, currentAttempt)
                } catch (e: BadConfigException) {
                    handleConfigFetchError("Error: Bad config string. ${e.message}", e, currentAttempt)
                } catch (e: IllegalArgumentException) {
                    handleConfigFetchError("Error: Invalid argument in config. ${e.message}", e, currentAttempt)
                } catch (e: Exception) {
                    handleConfigFetchError("Error: ${e.message ?: "Failed to get configuration"}", e, currentAttempt)
                }

                if (!success) {
                    configFetchRetryCount++
                } else {
                    configFetchRetryCount = 0
                }
            }

            if (!success) {
                Log.e(TAG, "All config fetch attempts failed after $lastAttemptNumber attempts.")
                _isConnectingAfterConfigFetch.value = false
            }
        }
    }

    private fun handleConfigFetchError(baseErrorMessage: String, exception: Exception?, attempt: Int) {
        val fullErrorMessage = exception?.message?.let { "$baseErrorMessage - Details: $it" } ?: baseErrorMessage
        Log.e(TAG, "handleConfigFetchError: CALLED for attempt $attempt. Current configFetchRetryCount = $configFetchRetryCount. Error: '$fullErrorMessage'", exception)
        _preparedConfig.value = null

        if (configFetchRetryCount >= MAX_CONFIG_FETCH_RETRIES) {
            Log.d(TAG, "handleConfigFetchError: FINAL ATTEMPT FAILED (attempt $attempt, configFetchRetryCount $configFetchRetryCount). Setting _lastErrorMessage.")
            _lastErrorMessage.value = fullErrorMessage
            _configStatusMessage.value = "Error: Config fetch failed after $attempt attempts. Please check connection or try later."
        } else {
            Log.d(TAG, "handleConfigFetchError: INTERMEDIATE ATTEMPT FAILED (attempt $attempt, configFetchRetryCount $configFetchRetryCount). NOT setting _lastErrorMessage. Will retry.")
        }
    }

    private suspend fun fetchConfigFromRealApi(clientPublicKeyForServer: String): ServerConfigData? {
        return try {
            val client = HttpClient {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true })
                }
                install(Logging) {
                    level = LogLevel.INFO
                }
            }
            val token = API_SECRET_TOKEN
            Log.d(TAG, "Using API_SECRET_TOKEN (first 5 chars): ${token.take(5)}...")

            val response: ServerConfigData = client.post("https://vpnforfriends.com:8443/api/config") {
                contentType(ContentType.Application.Json)
                setBody(
                    mapOf(
                        "token" to token,
                        "client_public_key" to clientPublicKeyForServer
                    )
                )
            }.body()
            client.close()
            Log.d(TAG, "Successfully fetched config: $response")
            response
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching config from API: ${e.message}", e)
            null
        }
    }

    private suspend fun buildClientConfig(
        clientKeys: KeyPair,
        serverData: ServerConfigData,
    ): Config {
        val resolvedEndpointString = withContext(Dispatchers.IO) {
            val host = serverData.serverEndpoint.split(":").first()
            val port = serverData.serverEndpoint.split(":").last()
            try {
                val ip = InetAddress.getByName(host).hostAddress
                Log.d(TAG, "Resolved hostname $host to IP $ip")
                "$ip:$port"
            } catch (e: Exception) {
                Log.e(TAG, "Failed to resolve hostname $host: ${e.message}, using original endpoint", e)
                serverData.serverEndpoint
            }
        }

        Log.d(TAG, "Building WireGuard config with endpoint: $resolvedEndpointString, client address: ${serverData.clientAssignedAddress}, server public key: ${serverData.serverPublicKey}")

        val interfaceBuilder = Interface.Builder()
            .setKeyPair(clientKeys)
            .addAddress(InetNetwork.parse(serverData.clientAssignedAddress))
            .setMtu(1280) // Уменьшенный MTU для совместимости
        serverData.dnsServers.forEach { dnsString ->
            try {
                val dnsInetAddress = withContext(Dispatchers.IO) {
                    InetAddress.getByName(dnsString) // Используем dnsServers из serverData
                }
                interfaceBuilder.addDnsServer(dnsInetAddress)
                Log.d(TAG, "Added DNS server: $dnsString")
            } catch (e: java.net.UnknownHostException) {
                Log.w(TAG, "Invalid or unknown DNS server host: $dnsString", e)
            } catch (e: Exception) {
                Log.w(TAG, "Error processing DNS server: $dnsString", e)
            }
        }

        val peerBuilder = Peer.Builder()
            .setPublicKey(Key.fromBase64(serverData.serverPublicKey))
            .setEndpoint(InetEndpoint.parse(resolvedEndpointString))
            .also { builder ->
                serverData.allowedIps.forEach { allowedIp ->
                    try {
                        builder.addAllowedIp(InetNetwork.parse(allowedIp))
                        Log.d(TAG, "Added AllowedIP: $allowedIp")
                    } catch (e: Exception) {
                        Log.w(TAG, "Skipping invalid allowed IP: $allowedIp", e)
                    }
                }
            }
        serverData.persistentKeepalive?.let {
            peerBuilder.setPersistentKeepalive(it)
            Log.d(TAG, "Set PersistentKeepalive: $it")
        }

        val config = Config.Builder()
            .setInterface(interfaceBuilder.build())
            .addPeer(peerBuilder.build())
            .build()

        Log.d(TAG, "WireGuard config built: ${config.toWgQuickString()}")
        return config
    }

    private fun startVpnWithCurrentConfig() {
        val currentConfig = preparedConfig.value
        if (currentConfig == null) {
            Log.e(TAG, "Cannot start VPN: config is null. Attempting to re-fetch.")
            _vpnState.value = Tunnel.State.DOWN
            configFetchRetryCount = 0
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
            _requestPermissionsEvent.value = Event(Unit)
            return
        }
        Log.d(TAG, "Attempting to start VPN with current config (permissions granted). Config: ${currentConfig.toWgQuickString()}")
        val intent = MyWgVpnService.newIntent(
            context = getApplication(),
            action = MyWgVpnService.ACTION_CONNECT,
            configString = currentConfig.toWgQuickString(),
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
            _lastErrorMessage.value = "Error: Could not start VPN service. ${e.message}"
            setIntermediateVpnState(Tunnel.State.DOWN)
        }
    }

    private fun stopVpn() {
        Log.d(TAG, "stopVpn: Stopping VPN.")
        val intent = MyWgVpnService.newIntent(
            context = getApplication(),
            action = MyWgVpnService.ACTION_DISCONNECT,
            tunnelName = DEFAULT_TUNNEL_NAME
        )
        try {
            getApplication<Application>().startService(intent)
            setIntermediateVpnState(Tunnel.State.TOGGLE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop VPN service", e)
            _lastErrorMessage.value = "Error: Could not stop VPN service. ${e.message}"
            setIntermediateVpnState(Tunnel.State.DOWN)
        }
    }

    fun clearLastError() {
        _lastErrorMessage.value = null
        _configStatusMessage.value = null
        configFetchRetryCount = 0
        Log.d(TAG, "clearLastError: Called. _lastErrorMessage and _configStatusMessage are now null. configFetchRetryCount is 0.")
    }

    fun setExternalVpnState(newState: Tunnel.State, errorMessage: String? = null) {
        Log.d(TAG, "setExternalVpnState: newState=$newState, current _vpnState=${_vpnState.value}, error=$errorMessage")
        _vpnState.value = newState

        if (newState == Tunnel.State.DOWN) {
            if (!errorMessage.isNullOrEmpty()) {
                if (errorMessage.contains("retrying", ignoreCase = true)) {
                    _configStatusMessage.value = "Connection failed, VPN service is retrying..."
                    Log.d(TAG, "setExternalVpnState: Service reported DOWN with a 'retrying' message. _lastErrorMessage not set.")
                } else {
                    _lastErrorMessage.value = errorMessage
                    _configStatusMessage.value = "VPN Disconnected: Error"
                    Log.d(TAG, "setExternalVpnState: Service reported DOWN with a non-retrying error. _lastErrorMessage SET.")
                }
            } else {
                _configStatusMessage.value = "VPN Disconnected"
            }
        } else if (newState == Tunnel.State.UP) {
            _lastErrorMessage.value = null
            _configStatusMessage.value = "VPN Connected"
            Log.d(TAG, "setExternalVpnState: Service reported UP. _lastErrorMessage cleared.")
        }
    }

    fun setIntermediateVpnState(intermediateState: Tunnel.State) {
        _vpnState.value = intermediateState
        if (intermediateState == Tunnel.State.TOGGLE) {
            _configStatusMessage.value = "Processing..."
        }
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "VpnViewModel cleared.")
    }
}