package com.playeverywhere999.vpn_for_friends_v3

import android.Manifest
import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.playeverywhere999.vpn_for_friends_v3.BuildConfig
import com.playeverywhere999.vpn_for_friends_v3.util.Event
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.InetAddress
import java.net.URI

@Serializable
data class VlessConfig(
    val vlessUrl: String
)

class VpnViewModel(application: Application) : AndroidViewModel(application) {

    private val _vpnState = MutableLiveData<VpnTunnelState>(VpnTunnelState.DOWN)
    val vpnState: LiveData<VpnTunnelState> = _vpnState

    private val _requestPermissionsEvent = MutableLiveData<Event<Unit>>()
    val requestPermissionsEvent: LiveData<Event<Unit>> = _requestPermissionsEvent

    private val _lastErrorMessage = MutableLiveData<String?>()
    val lastErrorMessage: LiveData<String?> = _lastErrorMessage

    private val _preparedConfig = MutableLiveData<String?>()
    val preparedConfig: LiveData<String?> = _preparedConfig

    private val _configStatusMessage = MutableLiveData<String?>()
    val configStatusMessage: LiveData<String?> = _configStatusMessage

    private val _isConnectingAfterConfigFetch = MutableLiveData<Boolean>(false)

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
            VpnTunnelState.DOWN, null -> {
                _lastErrorMessage.value = null
                configFetchRetryCount = 0
                if (_preparedConfig.value == null) {
                    _isConnectingAfterConfigFetch.value = true
                    _configStatusMessage.value = getApplication<Application>().getString(R.string.status_preparing_config)
                    startConfigGenerationAndFetch()
                } else {
                    _isConnectingAfterConfigFetch.value = false
                    _configStatusMessage.value = null
                    startVpnWithCurrentConfig()
                }
            }
            VpnTunnelState.UP -> {
                stopVpn()
            }
            VpnTunnelState.TOGGLE -> {
                Log.d(TAG, "VPN is already toggling, click ignored.")
            }
        }
    }

    fun startConfigGenerationAndFetch() {
        Log.d(TAG, "startConfigGenerationAndFetch: CALLED. Initial _lastErrorMessage: '${lastErrorMessage.value}', configFetchRetryCount: $configFetchRetryCount")
        viewModelScope.launch {
            var success = false
            if (configFetchRetryCount == 0) {
                _lastErrorMessage.value = null
                if (_configStatusMessage.value != getApplication<Application>().getString(R.string.status_preparing_config)) {
                    _configStatusMessage.value = getApplication<Application>().getString(R.string.status_preparing_config)
                }
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
                    _configStatusMessage.value = "Contacting server..."
                }
                Log.d(TAG, "Starting config generation and fetch process. Attempt: $currentAttempt")

                try {
                    val serverDataActual: VlessConfig? = fetchConfigFromRealApi()

                    if (serverDataActual != null) {
                        Log.d(TAG, "Server data received on attempt $currentAttempt: $serverDataActual")
                        _configStatusMessage.value = "Configuration received. Preparing tunnel..."
                        val finalConfig = withContext(Dispatchers.IO) {
                            buildClientConfig(serverDataActual)
                        }
                        if (finalConfig.isNotEmpty()) {
                            _preparedConfig.value = finalConfig
                            _configStatusMessage.value = "Configuration ready!"
                            Log.d(TAG, "VLESS config prepared successfully on attempt $currentAttempt.")
                            success = true

                            val shouldConnectAfterThisFetch = _isConnectingAfterConfigFetch.value == true

                            if (shouldConnectAfterThisFetch) {
                                Log.d(TAG, "Configuration fetched. Auto-connecting as requested...")
                                startVpnWithCurrentConfig()
                            } else {
                                _configStatusMessage.value = "Configuration ready! Tap connect."
                            }

                            _isConnectingAfterConfigFetch.value = false
                        } else {
                            handleConfigFetchError("Failed to generate a valid config.", null, currentAttempt)
                        }

                    } else {
                        Log.e(TAG, "Failed to get server configuration (serverDataActual is null) on attempt $currentAttempt.")
                        handleConfigFetchError("Failed to get server config from API.", null, currentAttempt)
                    }

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
            _configStatusMessage.value = "Config fetch attempt $attempt failed. Retrying..."
        }
    }

    private suspend fun fetchConfigFromRealApi(): VlessConfig? {
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

            val response: VlessConfig = client.post("https://vpnforfriends.com:8443/api/config") {
                contentType(ContentType.Application.Json)
                setBody(
                    mapOf(
                        "token" to token
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

    private suspend fun buildClientConfig(serverData: VlessConfig): String {
        Log.d(TAG, "Building V2Ray config from VLESS URL...")

        try {
            val vlessUri = URI(serverData.vlessUrl)
            val uuid = vlessUri.userInfo
            val address = vlessUri.host
            val port = vlessUri.port

            val resolvedAddress = try {
                withContext(Dispatchers.IO) { InetAddress.getByName(address).hostAddress }
            } catch (e: Exception) { address }

            val queryParams = vlessUri.query?.split("&")?.associate {
                val parts = it.split("=", limit = 2)
                parts[0] to (if (parts.size > 1) java.net.URLDecoder.decode(parts[1], "UTF-8") else "")
            } ?: emptyMap()

            val security = queryParams["security"] ?: "none"
            val network = queryParams["type"] ?: "tcp"
            val encryption = queryParams["encryption"] ?: "none"
            val sniRaw = queryParams["sni"] ?: ""
            val fp = queryParams["fp"] ?: "chrome"
            val pbk = queryParams["pbk"] ?: ""
            val sid = queryParams["sid"] ?: ""
            val spx = queryParams["spx"] ?: "/"
            val flow = queryParams["flow"]
            val path = queryParams["path"] ?: vlessUri.path.takeIf { it.isNotEmpty() } ?: "/"

            // === ОЧИСТКА SNI ОТ ПОРТА ===
            val safeSni = when {
                sniRaw.contains("google.com", ignoreCase = true) -> "www.microsoft.com"
                sniRaw.contains(":") -> sniRaw.substringBefore(":")
                sniRaw.isNotBlank() -> sniRaw
                else -> "www.microsoft.com"
            }

            // === ЛОГИРОВАНИЕ КЛЮЧЕВЫХ ПАРАМЕТРОВ ===
            Log.i(TAG, "=== VLESS CONNECTION PARAMS ===")
            Log.i(TAG, "URL: ${serverData.vlessUrl}")
            Log.i(TAG, "UUID: $uuid")
            Log.i(TAG, "Address: $address to $resolvedAddress")
            Log.i(TAG, "Port: $port")
            Log.i(TAG, "Security: $security")
            Log.i(TAG, "Network: $network")
            Log.i(TAG, "Raw SNI: '$sniRaw'")
            Log.i(TAG, "Clean SNI: '$safeSni'")
            Log.i(TAG, "Fingerprint: $fp")
            Log.i(TAG, "Public Key: $pbk")
            Log.i(TAG, "Short ID: $sid")
            Log.i(TAG, "SpiderX: '$spx'")
            Log.i(TAG, "Path: $path")
            Log.i(TAG, "Flow: $flow")
            Log.i(TAG, "=================================")

            val config = buildJsonObject {
                putJsonObject("log") {
                    put("loglevel", "debug")  // ← ВАЖНО: debug для логов!
                }

                // === DNS (обязательно!) ===
                putJsonObject("dns") {
                    putJsonArray("servers") {
                        add("1.1.1.1")
                        add("8.8.8.8")
                    }
                }

                // === INBOUNDS ===
                putJsonArray("inbounds") {
                    add(buildJsonObject {
                        put("tag", "tun-in")
                        put("protocol", "dokodemo-door")
                        put("port", 0)
                        putJsonObject("settings") {
                            put("address", "127.0.0.1")
                            put("port", 0)
                            put("network", "tcp,udp")
                            put("followRedirect", true)
                            put("fd", 1)
                        }
                        putJsonObject("streamSettings") {
                            putJsonObject("sockopt") {
                                put("tproxy", "redirect")  // ← redirect, не tproxy
                            }
                        }
                        putJsonObject("sniffing") {
                            put("enabled", true)
                            putJsonArray("destOverride") { add("http"); add("tls") }
                        }
                    })
                }

                // === OUTBOUNDS ===
                putJsonArray("outbounds") {
                    add(buildJsonObject {
                        put("tag", "proxy")
                        put("protocol", "vless")
                        putJsonObject("settings") {
                            putJsonArray("vnext") {
                                add(buildJsonObject {
                                    put("address", resolvedAddress)
                                    put("port", port)
                                    putJsonArray("users") {
                                        add(buildJsonObject {
                                            put("id", uuid)
                                            put("encryption", encryption)
                                            flow?.takeIf { it.isNotBlank() }?.let { put("flow", it) }
                                        })
                                    }
                                })
                            }
                        }
                        putJsonObject("streamSettings") {
                            put("network", network)
                            put("security", security)
                            when (security) {
                                "tls" -> putJsonObject("tlsSettings") {
                                    put("serverName", safeSni)
                                    put("fingerprint", fp)
                                }
                                "reality" -> putJsonObject("realitySettings") {
                                    put("serverName", safeSni)
                                    put("fingerprint", fp)
                                    put("publicKey", pbk)
                                    put("shortId", sid)
                                    put("spiderX", spx)
                                }
                            }
                            if (network == "ws") {
                                putJsonObject("wsSettings") {
                                    put("path", path)
                                    putJsonObject("headers") { put("Host", safeSni) }
                                }
                            }
                        }
                    })
                    add(buildJsonObject { put("tag", "direct"); put("protocol", "freedom") })
                    add(buildJsonObject { put("tag", "block"); put("protocol", "blackhole") })
                }

                // === ROUTING ===
                putJsonObject("routing") {
                    put("domainStrategy", "AsIs")
                    putJsonArray("rules") {
                        add(buildJsonObject {
                            put("type", "field")
                            putJsonArray("ip") {
                                add("10.0.0.0/8")
                                add("172.16.0.0/12")
                                add("192.168.0.0/16")
                                add("fc00::/7")
                                add("fe80::/10")
                                add("::1/128")
                                add("127.0.0.0/8")
                            }
                            put("outboundTag", "direct")
                        })
                        add(buildJsonObject {
                            put("type", "field")
                            putJsonArray("inboundTag") { add("tun-in") }
                            put("outboundTag", "proxy")
                        })
                    }
                }
            }.toString()

            Log.i(TAG, "Config generated: ${config.length} chars")
            Log.d("FINAL_CONFIG", config)
            return config

        } catch (e: Exception) {
            Log.e(TAG, "Config build failed", e)
            withContext(Dispatchers.Main) {
                _lastErrorMessage.value = "Config error: ${e.message}"
            }
            return ""
        }
    }

    private fun startVpnWithCurrentConfig() {
        Log.d(TAG, "startVpnWithCurrentConfig called.")
        val currentConfig = preparedConfig.value
        if (currentConfig.isNullOrEmpty()) {
            Log.e(TAG, "Cannot start VPN: config is null or empty. Attempting to re-fetch.")
            _vpnState.value = VpnTunnelState.DOWN
            configFetchRetryCount = 0
            _isConnectingAfterConfigFetch.value = true
            _configStatusMessage.value = getApplication<Application>().getString(R.string.status_preparing_config)
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

        Log.i(TAG, "FINAL_CONFIG: $currentConfig")  // ← ВАЖНО: ВИДНО В LOGCAT

        val intent = VlessVpnService.newIntent(
            context = getApplication(),
            action = VlessVpnService.ACTION_CONNECT,
            tunnelName = DEFAULT_TUNNEL_NAME
        ).apply {
            putExtra("CONFIG", currentConfig)  // ← КОНФИГ В INTENT
        }

        try {
            Log.i(TAG, "Starting foreground service with ACTION_CONNECT.")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getApplication<Application>().startForegroundService(intent)
            } else {
                getApplication<Application>().startService(intent)
            }
            setIntermediateVpnState(VpnTunnelState.TOGGLE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN service", e)
            _lastErrorMessage.value = "Error: Could not start VPN service. ${e.message}"
            setIntermediateVpnState(VpnTunnelState.DOWN)
        }
    }

    private fun stopVpn() {
        Log.d(TAG, "stopVpn: Stopping VPN.")
        if (vpnState.value != VpnTunnelState.UP) {
            Log.w(TAG, "stopVpn called but state is not UP. Ignoring.")
            return
        }
        val intent = VlessVpnService.newIntent(
            context = getApplication(),
            action = VlessVpnService.ACTION_DISCONNECT,
            tunnelName = DEFAULT_TUNNEL_NAME
        )
        try {
            getApplication<Application>().startService(intent)
            setIntermediateVpnState(VpnTunnelState.TOGGLE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop VPN service", e)
            _lastErrorMessage.value = "Error: Could not stop VPN service. ${e.message}"
            setIntermediateVpnState(VpnTunnelState.DOWN)
        }
    }

    fun clearLastError() {
        _lastErrorMessage.value = null
        _configStatusMessage.value = null
        configFetchRetryCount = 0
        Log.d(TAG, "clearLastError: Called. _lastErrorMessage and _configStatusMessage are now null. configFetchRetryCount is 0.")
    }

    fun setExternalVpnState(newState: VpnTunnelState, errorMessage: String? = null) {
        Log.d(TAG, "setExternalVpnState: newState=$newState, current _vpnState=${_vpnState.value}, error=$errorMessage")

        if (newState == _vpnState.value && errorMessage == _lastErrorMessage.value) {
            Log.d(TAG, "setExternalVpnState: State is already the same. Ignoring.")
            return
        }

        _vpnState.value = newState

        if (newState == VpnTunnelState.DOWN) {
            if (!errorMessage.isNullOrEmpty()) {
                _lastErrorMessage.value = errorMessage
                _configStatusMessage.value = "VPN Disconnected: Error"
            } else {
                if (_configStatusMessage.value?.contains("ready", ignoreCase = true) != true) {
                    _configStatusMessage.value = "VPN Disconnected"
                }
            }
        } else if (newState == VpnTunnelState.UP) {
            _lastErrorMessage.value = null
            _configStatusMessage.value = "VPN Connected"
        }
    }

    fun setIntermediateVpnState(intermediateState: VpnTunnelState) {
        if (_vpnState.value == intermediateState) return
        _vpnState.value = intermediateState
        if (intermediateState == VpnTunnelState.TOGGLE) {
            _configStatusMessage.value = getApplication<Application>().getString(R.string.status_processing)
        }
    }

    fun importConfigFromClipboard() {
        val clipboard = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipData = clipboard.primaryClip
        if (clipData != null && clipData.itemCount > 0) {
            val vlessUrl = clipData.getItemAt(0).text?.toString()
            if (!vlessUrl.isNullOrBlank() && vlessUrl.startsWith("vless://")) {
                viewModelScope.launch {
                    val config = withContext(Dispatchers.IO) {
                        buildClientConfig(VlessConfig(vlessUrl))
                    }
                    if (config.isNotEmpty()) {
                        _preparedConfig.value = config
                        _configStatusMessage.value = "Config imported from clipboard!"
                        _lastErrorMessage.value = null
                    }
                }
            } else {
                _configStatusMessage.value = "Clipboard does not contain a valid config."
            }
        } else {
            _configStatusMessage.value = "Clipboard is empty."
        }
    }

    fun onQrCodeScanned(qrContent: String?) {
        if (!qrContent.isNullOrBlank() && qrContent.startsWith("vless://")) {
            viewModelScope.launch {
                val config = withContext(Dispatchers.IO) {
                    buildClientConfig(VlessConfig(qrContent))
                }
                if (config.isNotEmpty()) {
                    _preparedConfig.value = config
                    _configStatusMessage.value = "Config imported from QR code!"
                    _lastErrorMessage.value = null
                }
            }
        } else {
            _configStatusMessage.value = "Scanned QR does not contain a valid config."
        }
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "VpnViewModel cleared.")
    }
}
