package com.example.iot

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors

internal data class MqttTrafficStats(
    val sent: Long,
    val received: Long,
    val connectedMillis: Long,
    val sampledAtElapsed: Long = SystemClock.elapsedRealtime()
)
internal data class ConnectionHistoryItem(val connected: Boolean, val occurredAt: Long)
internal enum class DecisionDeliveryStatus {
    QUEUED_FOR_WIFI,
    PUBLISHED_TO_BROKER,
    CONFIRMED_BY_ESP32,
    EXPIRED
}

/** Estado visible de una decisión asociada a una caja concreta. */
internal data class DecisionDeliveryState(
    val id: Long,
    val action: String,
    val status: DecisionDeliveryStatus,
    val expiresAtElapsed: Long? = null
)

internal class MqttController(
    context: Context,
    private val preferences: SharedPreferences,
    private val onConnection: (String) -> Unit,
    private val onEvent: (String) -> Unit,
    private val onMessage: (String, String) -> Unit,
    private val onStats: (MqttTrafficStats) -> Unit,
    private val onHistory: (ConnectionHistoryItem) -> Unit,
    private val onDecisionDelivery: (DecisionDeliveryState) -> Unit
) {
    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
    private val powerManager = appContext.getSystemService(PowerManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val callbackExecutor = Executors.newSingleThreadExecutor()
    private val handledReadings = preferences.getStringSet(HANDLED_READINGS_KEY, emptySet())
        .orEmpty().mapNotNull(String::toLongOrNull).toMutableSet()
    private val seenReadings = mutableSetOf<Long>()

    @Volatile private var mqttClient: MqttAsyncClient? = null
    @Volatile private var brokerConfig: BrokerConfig? = null
    @Volatile private var wifiNetwork: Network? = null
    @Volatile private var wantsConnection = false
    @Volatile private var foreground = true
    @Volatile private var energySaving = false
    @Volatile private var reconnectIndex = 0
    @Volatile private var reconnectPending = false
    @Volatile private var connected = false
    @Volatile private var pendingReadingId: Long? = null
    @Volatile private var pendingReadingAt = 0L
    @Volatile private var decisionSent = false
    @Volatile private var pendingDecisionId: Long? = null
    @Volatile private var pendingDecisionPayload: String? = null
    @Volatile private var sentCount = 0L
    @Volatile private var receivedCount = 0L
    @Volatile private var connectedAt = 0L
    @Volatile private var accumulatedConnectionMillis = 0L
    @Volatile private var lastInvalidPayload = ""
    @Volatile private var lastInvalidNoticeAt = 0L
    @Volatile private var lastStatsPostedAt = 0L
    @Volatile private var wifiWasLost = false
    private var networkCallbackRegistered = false
    private var autoDisconnectedForBackground = false

    private val reconnectRunnable = Runnable {
        reconnectPending = false
        tryConnect()
    }
    private val backgroundDisconnectRunnable = Runnable {
        if (!foreground && wantsConnection && connected) {
            autoDisconnectedForBackground = true
            closeTransport("Sin conexión", recordHistory = false)
        }
    }
    private val pendingDecisionExpiryRunnable = Runnable { retryPendingDecision() }
    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            wifiNetwork = network
            if (foreground && wantsConnection) {
                if (wifiWasLost) {
                    wifiWasLost = false
                    scheduleReconnect("Wi-Fi recuperada")
                } else {
                    tryConnect()
                }
            }
        }

        override fun onLost(network: Network) {
            if (wifiNetwork == network) {
                wifiNetwork = null
                wifiWasLost = true
                cancelReconnect()
                closeTransport("Sin conexión", recordHistory = true)
            }
        }
    }

    fun connect(config: BrokerConfig) {
        val issue = config.validate()
        if (issue != null) {
            disconnect()
            postEvent(issue)
            return
        }
        cancelReconnect()
        disconnectTransport()
        brokerConfig = config.copy(host = config.host.trim())
        wantsConnection = true
        reconnectIndex = 0
        autoDisconnectedForBackground = false
        registerWifiCallback()
        if (wifiNetwork == null) {
            val active = connectivity.activeNetwork
            val capabilities = active?.let(connectivity::getNetworkCapabilities)
            if (active != null && capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                wifiNetwork = active
            }
        }
        if (wifiNetwork == null) {
            postConnection("Sin conexión")
            postEvent("Conecta el teléfono a la Wi-Fi local del broker.")
        } else if (foreground) {
            tryConnect()
        }
    }

    fun disconnect() {
        wantsConnection = false
        brokerConfig = null
        autoDisconnectedForBackground = false
        cancelReconnect()
        unregisterWifiCallback()
        closeTransport("Sin conexión", recordHistory = false)
    }

    fun setInForeground(value: Boolean) {
        foreground = value
        handler.removeCallbacks(backgroundDisconnectRunnable)
        if (!value) {
            handler.postDelayed(backgroundDisconnectRunnable, BACKGROUND_DISCONNECT_MS)
        } else if (wantsConnection) {
            autoDisconnectedForBackground = false
            registerWifiCallback()
            tryConnect()
        }
    }

    fun setEnergySavingEnabled(enabled: Boolean) {
        val automatic = powerManager.isPowerSaveMode
        val updated = enabled || automatic
        if (energySaving == updated) return
        energySaving = updated
        if (connected) {
            val config = brokerConfig
            closeTransport("Reconectando", recordHistory = false)
            if (config != null && foreground && wifiNetwork != null) tryConnect()
        }
    }

    fun publishCommand(command: JSONObject) {
        val action = command.optString("accion")
        val decisions = setOf("aceptar", "expulsar")
        val requiresId = action in decisions || action == "resultado_ack"
        val supported = action in setOf("iniciar", "detener", "velocidad") || requiresId
        if (!supported) {
            postEvent("Comando descartado: la acción no forma parte del protocolo MQTT.")
            return
        }
        val id = if (requiresId) command.opt("id") as? Number else null
        val candidate = id?.toLong()
        val validId = id?.let { value ->
            val longValue = value.toLong()
            value.toDouble().isFinite() && value.toDouble() == longValue.toDouble() && longValue > 0L
        } == true
        if (requiresId && !validId) {
            postEvent("Comando descartado: el ID debe ser un entero positivo.")
            return
        }
        if (action in decisions) {
            if (candidate != pendingReadingId) {
                postEvent("Decisión descartada: el ID no coincide con la lectura pendiente.")
                return
            }
            if (SystemClock.elapsedRealtime() - pendingReadingAt > DECISION_MAX_AGE_MS) {
                postDecisionDelivery(
                    DecisionDeliveryState(
                        id = candidate!!,
                        action = action,
                        status = DecisionDeliveryStatus.EXPIRED
                    )
                )
                pendingReadingId = null
                decisionSent = false
                pendingDecisionId = null
                pendingDecisionPayload = null
                cancelPendingDecisionExpiry()
                postEvent("Decisión descartada: pasaron más de 10 segundos desde la lectura.")
                return
            }
            if (decisionSent) return
        }
        if (action == "velocidad") {
            val speed = command.opt("valor") as? Number
            if (speed == null || speed.toInt() !in 10..100 || speed.toDouble() != speed.toInt().toDouble()) {
                postEvent("Velocidad descartada: el valor debe ser un entero entre 10 y 100.")
                return
            }
        }
        val isDecision = action in decisions
        if (isDecision) {
            pendingDecisionId = id?.toLong()
            pendingDecisionPayload = command.toString()
            decisionSent = false
            schedulePendingDecisionExpiry()
            postDecisionDelivery(
                DecisionDeliveryState(
                    id = candidate!!,
                    action = action,
                    status = DecisionDeliveryStatus.QUEUED_FOR_WIFI,
                    expiresAtElapsed = pendingReadingAt + DECISION_MAX_AGE_MS
                )
            )
        }
        val activeClient = mqttClient
        if (!connected || activeClient == null || activeClient.isConnected.not()) {
            if (isDecision) {
                val label = if (action == "expulsar") "Rechazo automático" else "Decisión"
                postEvent("$label en cola: el dato se enviará cuando se conecte a Wi-Fi/MQTT, si no supera 10 segundos.")
            } else {
                postEvent("Comando descartado porque el broker no está conectado.")
            }
            return
        }
        if (isDecision) decisionSent = true
        val payload = command.toString().toByteArray(Charsets.UTF_8)
        try {
            activeClient.publish(COMMAND_TOPIC, payload, 1, false, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: org.eclipse.paho.client.mqttv3.IMqttToken?) {
                    if (mqttClient !== activeClient) return
                    sentCount++
                    if (isDecision) {
                        postDecisionDelivery(
                            DecisionDeliveryState(
                                id = candidate!!,
                                action = action,
                                status = DecisionDeliveryStatus.PUBLISHED_TO_BROKER,
                                expiresAtElapsed = pendingReadingAt + DECISION_MAX_AGE_MS
                            )
                        )
                    }
                    postStats()
                }

                override fun onFailure(
                    asyncActionToken: org.eclipse.paho.client.mqttv3.IMqttToken?,
                    exception: Throwable?
                ) {
                    if (mqttClient !== activeClient) return
                    if (action == "aceptar" || action == "expulsar") decisionSent = false
                    postEvent("No se pudo publicar el comando MQTT: ${exception?.message ?: "error de conexión"}.")
                    if (exception != null && !activeClient.isConnected && wantsConnection && foreground) {
                        markDisconnected()
                        scheduleReconnect(exception.message)
                    } else if (isDecision && activeClient.isConnected) {
                        postDecisionDelivery(
                            DecisionDeliveryState(
                                id = candidate!!,
                                action = action,
                                status = DecisionDeliveryStatus.QUEUED_FOR_WIFI,
                                expiresAtElapsed = pendingReadingAt + DECISION_MAX_AGE_MS
                            )
                        )
                        postEvent("La conexión falló: el dato quedó en cola y se enviará cuando vuelva Wi-Fi/MQTT.")
                        handler.postDelayed({ retryPendingDecision() }, 2_000L)
                    }
                }
            })
        } catch (error: MqttException) {
            if (action == "aceptar" || action == "expulsar") decisionSent = false
            postEvent("No se pudo publicar el comando MQTT: ${error.message ?: "error de conexión"}.")
            if (!activeClient.isConnected && wantsConnection && foreground) {
                markDisconnected()
                scheduleReconnect(error.message)
            } else if (isDecision && activeClient.isConnected) {
                postDecisionDelivery(
                    DecisionDeliveryState(
                        id = candidate!!,
                        action = action,
                        status = DecisionDeliveryStatus.QUEUED_FOR_WIFI,
                        expiresAtElapsed = pendingReadingAt + DECISION_MAX_AGE_MS
                    )
                )
                postEvent("La conexión falló: el dato quedó en cola y se enviará cuando vuelva Wi-Fi/MQTT.")
                handler.postDelayed({ retryPendingDecision() }, 2_000L)
            }
        }
    }

    fun trafficStats(): MqttTrafficStats = MqttTrafficStats(
        sent = sentCount,
        received = receivedCount,
        connectedMillis = accumulatedConnectionMillis +
            if (connectedAt > 0L) SystemClock.elapsedRealtime() - connectedAt else 0L
    )

    private fun registerWifiCallback() {
        if (networkCallbackRegistered) return
        try {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            connectivity.registerNetworkCallback(request, wifiCallback)
            networkCallbackRegistered = true
        } catch (error: SecurityException) {
            postConnection("Sin conexión")
            postEvent("Android no permitió observar el estado de Wi-Fi: ${error.message ?: "permiso de red"}.")
        }
    }

    private fun unregisterWifiCallback() {
        if (!networkCallbackRegistered) return
        try {
            connectivity.unregisterNetworkCallback(wifiCallback)
        } catch (_: IllegalArgumentException) {
            Unit
        }
        networkCallbackRegistered = false
        wifiNetwork = null
    }

    private fun tryConnect() {
        if (!foreground || !wantsConnection || connected || reconnectPending) return
        val config = brokerConfig ?: return
        if (wifiNetwork == null) {
            postConnection("Sin conexión")
            return
        }
        postConnection("Reconectando")
        val clientId = "android-${UUID.randomUUID().toString().take(12)}"
        val client = try {
            MqttAsyncClient(config.uri(), clientId, MemoryPersistence())
        } catch (error: MqttException) {
            scheduleReconnect(error.message)
            return
        }
        mqttClient = client
        client.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                if (mqttClient !== client) return
                markConnected(client)
            }

            override fun connectionLost(cause: Throwable?) {
                if (mqttClient !== client) return
                markDisconnected()
                if (wantsConnection && foreground) scheduleReconnect(cause?.message)
            }

            override fun messageArrived(topic: String?, message: MqttMessage?) {
                if (mqttClient !== client || topic == null || message == null) return
                receivedCount++
                val payload = String(message.payload, Charsets.UTF_8)
                if (!validateIncoming(topic, payload)) {
                    postStats()
                    return
                }
                when (topic) {
                    COLOR_TOPIC -> {
                        val reading = MqttJsonValidator.color(payload)
                        val duplicate = synchronized(handledReadings) {
                            handledReadings.contains(reading.id) || synchronized(seenReadings) {
                                !seenReadings.add(reading.id)
                            }
                        }
                        if (duplicate) {
                            if (pendingDecisionId == reading.id && pendingDecisionPayload != null) {
                                retryPendingDecision()
                            }
                            postStats()
                            return
                        }
                        synchronized(seenReadings) {
                            while (seenReadings.size > MAX_HANDLED_IDS) {
                                seenReadings.remove(seenReadings.firstOrNull() ?: break)
                            }
                        }
                        pendingReadingId = reading.id
                        pendingReadingAt = SystemClock.elapsedRealtime()
                        decisionSent = false
                    }
                    RESULT_TOPIC -> {
                        val result = MqttJsonValidator.result(payload)
                        if (pendingDecisionId == result.id) {
                            postDecisionDelivery(
                                DecisionDeliveryState(
                                    id = result.id,
                                    action = pendingDecisionPayload?.let { JSONObject(it).optString("accion") }.orEmpty(),
                                    status = DecisionDeliveryStatus.CONFIRMED_BY_ESP32
                                )
                            )
                            cancelPendingDecisionExpiry()
                        }
                        if (pendingReadingId == result.id) {
                            pendingReadingId = null
                            decisionSent = false
                        }
                        markReadingHandled(result.id)
                    }
                }
                postStats()
                handler.post { onMessage(topic, payload) }
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
        })
        val options = MqttConnectOptions().apply {
            isCleanSession = true
            isAutomaticReconnect = false
            keepAliveInterval = if (energySaving || powerManager.isPowerSaveMode) ENERGY_KEEP_ALIVE_SECONDS else KEEP_ALIVE_SECONDS
            connectionTimeout = CONNECTION_TIMEOUT_SECONDS
            isHttpsHostnameVerificationEnabled = true
            if (config.username.isNotBlank()) userName = config.username
            if (config.password.isNotEmpty()) password = config.password.toCharArray()
            wifiNetwork?.let { network ->
                socketFactory = if (config.tls) NetworkBoundSslSocketFactory(network) else network.socketFactory
            }
        }
        try {
            client.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: org.eclipse.paho.client.mqttv3.IMqttToken?) {
                    if (mqttClient === client) markConnected(client)
                }

                override fun onFailure(
                    asyncActionToken: org.eclipse.paho.client.mqttv3.IMqttToken?,
                    exception: Throwable?
                ) {
                    if (mqttClient !== client) return
                    markDisconnected()
                    scheduleReconnect(exception?.message)
                }
            })
        } catch (error: MqttException) {
            markDisconnected()
            scheduleReconnect(error.message)
        }
    }

    private fun markConnected(client: MqttAsyncClient) {
        if (mqttClient !== client || connected) return
        connected = true
        reconnectIndex = 0
        connectedAt = SystemClock.elapsedRealtime()
        postConnection("Conectado")
        postHistory(ConnectionHistoryItem(true, System.currentTimeMillis()))
        subscribeTopics(client)
        retryPendingDecision()
        postStats(force = true)
    }

    private fun subscribeTopics(client: MqttAsyncClient?) {
        if (client == null || !connected || !client.isConnected) return
        try {
            client.subscribe(
                arrayOf(COLOR_TOPIC, STATUS_TOPIC, RESULT_TOPIC),
                intArrayOf(1, if (energySaving || powerManager.isPowerSaveMode) 0 else 1, 1)
            )
        } catch (error: MqttException) {
            postEvent("No se pudieron restaurar las suscripciones MQTT: ${error.message ?: "error de broker"}.")
        }
    }

    private fun validateIncoming(topic: String, payload: String): Boolean {
        try {
            when (topic) {
                COLOR_TOPIC -> MqttJsonValidator.color(payload)
                STATUS_TOPIC -> MqttJsonValidator.status(payload)
                RESULT_TOPIC -> MqttJsonValidator.result(payload)
                else -> return false
            }
            return true
        } catch (error: Exception) {
            Log.e(TAG, "JSON MQTT descartado en $topic: ${error.message}", error)
            val message = "Mensaje MQTT inválido en $topic: ${error.message ?: "formato incorrecto"}."
            val now = SystemClock.elapsedRealtime()
            if (message != lastInvalidPayload || now - lastInvalidNoticeAt >= INVALID_NOTICE_INTERVAL_MS) {
                lastInvalidPayload = message
                lastInvalidNoticeAt = now
                postEvent(message)
            }
            return false
        }
    }

    private fun scheduleReconnect(reason: String?) {
        if (!wantsConnection || !foreground || reconnectPending) return
        closeClientOnly()
        if (wifiNetwork == null) {
            postConnection("Sin conexión")
            return
        }
        val delay = RECONNECT_DELAYS_MS[reconnectIndex.coerceAtMost(RECONNECT_DELAYS_MS.lastIndex)]
        reconnectIndex = (reconnectIndex + 1).coerceAtMost(RECONNECT_DELAYS_MS.lastIndex)
        postConnection("Reconectando")
        reason?.takeIf(String::isNotBlank)?.let { postEvent("Se perdió MQTT; nuevo intento en ${delay / 1000}s ($it).") }
        cancelReconnect()
        reconnectPending = true
        handler.postDelayed(reconnectRunnable, delay)
    }

    private fun markDisconnected(recordHistory: Boolean = true) {
        if (!connected) return
        connected = false
        if (connectedAt > 0L) {
            accumulatedConnectionMillis += SystemClock.elapsedRealtime() - connectedAt
            connectedAt = 0L
        }
        if (recordHistory) postHistory(ConnectionHistoryItem(false, System.currentTimeMillis()))
        postStats(force = true)
    }

    private fun closeTransport(status: String, recordHistory: Boolean = true) {
        markDisconnected(recordHistory)
        closeClientOnly()
        postConnection(status)
    }

    private fun disconnectTransport() {
        markDisconnected(recordHistory = false)
        closeClientOnly()
        pendingReadingId = null
        decisionSent = false
        pendingDecisionId = null
        pendingDecisionPayload = null
        cancelPendingDecisionExpiry()
    }

    private fun closeClientOnly() {
        val client = mqttClient
        mqttClient = null
        if (client != null) {
            callbackExecutor.execute {
                try {
                    if (client.isConnected) client.disconnectForcibly(0, 500, false)
                    client.close()
                } catch (_: Exception) {
                    Unit
                }
            }
        }
    }

    private fun cancelReconnect() {
        handler.removeCallbacks(reconnectRunnable)
        reconnectPending = false
    }

    private fun schedulePendingDecisionExpiry() {
        handler.removeCallbacks(pendingDecisionExpiryRunnable)
        val remaining = (pendingReadingAt + DECISION_MAX_AGE_MS - SystemClock.elapsedRealtime())
        handler.postDelayed(pendingDecisionExpiryRunnable, remaining.coerceAtLeast(1L))
    }

    private fun cancelPendingDecisionExpiry() {
        handler.removeCallbacks(pendingDecisionExpiryRunnable)
    }

    private fun postStats(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        val minimumInterval = if (energySaving || powerManager.isPowerSaveMode) 10_000L else 1_000L
        if (!force && now - lastStatsPostedAt < minimumInterval) return
        lastStatsPostedAt = now
        val snapshot = trafficStats()
        handler.post { onStats(snapshot) }
    }

    private fun postConnection(value: String) {
        handler.post { onConnection(value) }
    }

    private fun postEvent(value: String) {
        handler.post { onEvent(value) }
    }

    private fun postHistory(event: ConnectionHistoryItem) {
        handler.post { onHistory(event) }
    }

    private fun postDecisionDelivery(state: DecisionDeliveryState) {
        handler.post { onDecisionDelivery(state) }
    }

    private fun markReadingHandled(id: Long) {
        synchronized(handledReadings) {
            handledReadings.add(id)
            while (handledReadings.size > MAX_HANDLED_IDS) {
                handledReadings.remove(handledReadings.minOrNull() ?: break)
            }
            preferences.edit()
                .putStringSet(HANDLED_READINGS_KEY, handledReadings.mapTo(mutableSetOf(), Long::toString))
                .apply()
        }
        if (pendingDecisionId == id) {
            cancelPendingDecisionExpiry()
            pendingDecisionPayload = null
            pendingDecisionId = null
            pendingReadingId = null
            decisionSent = false
        }
    }

    private fun retryPendingDecision() {
        val payload = pendingDecisionPayload ?: return
        val id = pendingDecisionId ?: return
        if (SystemClock.elapsedRealtime() - pendingReadingAt > DECISION_MAX_AGE_MS) {
            postDecisionDelivery(
                DecisionDeliveryState(
                    id = id,
                    action = JSONObject(payload).optString("accion"),
                    status = DecisionDeliveryStatus.EXPIRED
                )
            )
            pendingDecisionPayload = null
            cancelPendingDecisionExpiry()
            pendingDecisionId = null
            pendingReadingId = null
            decisionSent = false
            synchronized(seenReadings) { seenReadings.remove(id) }
            postEvent("Decisión pendiente descartada porque superó los 10 segundos.")
            return
        }
        decisionSent = false
        try {
            publishCommand(JSONObject(payload))
        } catch (error: Exception) {
            pendingDecisionPayload = null
            pendingDecisionId = null
            decisionSent = false
            postEvent("No se pudo recuperar la decisión pendiente para la caja $id: ${error.message ?: "JSON inválido"}.")
        }
    }

    private companion object {
        const val TAG = "ClasificadorMQTT"
        const val HANDLED_READINGS_KEY = "handled_mqtt_color_ids"
        const val MAX_HANDLED_IDS = 500
        const val COLOR_TOPIC = "banda/sensor/color"
        const val STATUS_TOPIC = "banda/estado"
        const val RESULT_TOPIC = "banda/resultado"
        const val COMMAND_TOPIC = "banda/comandos"
        const val KEEP_ALIVE_SECONDS = 30
        const val ENERGY_KEEP_ALIVE_SECONDS = 120
        const val CONNECTION_TIMEOUT_SECONDS = 10
        const val DECISION_MAX_AGE_MS = 10_000L
        const val BACKGROUND_DISCONNECT_MS = 120_000L
        const val INVALID_NOTICE_INTERVAL_MS = 5_000L
        val RECONNECT_DELAYS_MS = longArrayOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L)
    }
}
