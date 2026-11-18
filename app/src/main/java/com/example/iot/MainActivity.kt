package com.example.iot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import kotlinx.coroutines.delay
import org.json.JSONException
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.UUID

private const val COLOR_TOPIC = "banda/sensor/color"
private const val STATUS_TOPIC = "banda/estado"
private const val RESULT_TOPIC = "banda/resultado"
private const val ROLE_ADMIN = "admin"
private const val ROLE_OPERATOR = "operador"
private const val ROLE_MAINTENANCE = "mantenimiento"
private const val RGB_TOLERANCE = 90.0
private const val HANDLED_RESULT_IDS_KEY = "handled_mqtt_result_ids"

data class Rgb(val red: Int, val green: Int, val blue: Int)

data class InventoryColor(
    val name: String,
    val rgb: Rgb,
    val allowed: Boolean = true,
    val accepted: Int = 0,
    val rejected: Int = 0,
    val id: String = UUID.randomUUID().toString()
)

data class ConveyorStatus(
    val belt: String = "Sin datos",
    val sensor: String = "Sin datos",
    val servo: String = "Sin datos",
    val accepted: Int = 0,
    val rejected: Int = 0,
    val speed: String = "Sin datos",
    val receivedAt: Long? = null
)

private data class BatteryStatus(val percentage: Int = 0, val charging: Boolean = false)

private data class SensorReading(val id: Long, val rgb: Rgb)

private enum class LearningStep { OFF, READY, WAITING_FOR_SAMPLE, NAMING_COLOR }
private enum class AppPage { HOME, INVENTORY, SETTINGS, ENERGY, HISTORY }

private val defaultColors = listOf(
    InventoryColor("Rojo", Rgb(220, 30, 30), id = "rojo"),
    InventoryColor("Verde", Rgb(30, 170, 60), id = "verde"),
    InventoryColor("Azul", Rgb(30, 90, 230), id = "azul"),
    InventoryColor("Amarillo", Rgb(240, 210, 20), id = "amarillo"),
    InventoryColor("Naranja", Rgb(255, 82, 0), id = "naranja"),
    InventoryColor("Morado", Rgb(140, 50, 190), id = "morado"),
    InventoryColor("Blanco", Rgb(240, 240, 240), id = "blanco"),
    InventoryColor("Café", Rgb(120, 75, 40), id = "cafe")
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = getSharedPreferences("mqtt_settings", MODE_PRIVATE)
        val brokerConfigStore = SecureBrokerConfigStore(applicationContext)
        val firebase = FirebaseRepository(applicationContext)
        setContent {
            ConveyorApp(
                firebase = firebase,
                devicePreferences = preferences,
                initialBrokerConfig = brokerConfigStore.load(),
                initialBeltSpeed = preferences.getInt("belt_speed", 60),
                initialAllowUnknownColors = preferences.getBoolean("allow_unknown_colors", false),
                saveBrokerConfig = brokerConfigStore::save,
                saveAllowUnknownColors = { allow ->
                    preferences.edit().putBoolean("allow_unknown_colors", allow).apply()
                },
                saveBeltSpeed = { speed ->
                    preferences.edit().putInt("belt_speed", speed).apply()
                }
            )
        }
    }
}

@Composable
private fun ConveyorApp(
    firebase: FirebaseRepository,
    devicePreferences: SharedPreferences,
    initialBrokerConfig: BrokerConfig,
    initialBeltSpeed: Int,
    initialAllowUnknownColors: Boolean,
    saveBrokerConfig: (BrokerConfig) -> Unit,
    saveAllowUnknownColors: (Boolean) -> Unit,
    saveBeltSpeed: (Int) -> Unit
) {
    val context = LocalContext.current.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    val localData = remember { LocalDataRepository(context) }
    val powerManager = remember { context.getSystemService(PowerManager::class.java) }
    val colors = remember { mutableStateListOf<InventoryColor>() }
    val connection = remember { mutableStateOf("Sin conexión") }
    val status = remember { mutableStateOf(ConveyorStatus()) }
    val eventMessage = remember { mutableStateOf("Ingresa para continuar.") }
    val loginAttemptLimiter = remember { LoginAttemptLimiter() }
    val pendingSyncCount = remember { mutableStateOf(0) }
    val session = remember { mutableStateOf<AppUser?>(null) }
    val inventoryLoaded = remember { mutableStateOf(false) }
    val boxHistory = remember { mutableStateOf<List<BoxHistoryEntry>>(emptyList()) }
    val connectionHistory = remember { mutableStateOf<List<ConnectionHistoryItem>>(emptyList()) }
    val mqttStats = remember { mutableStateOf(MqttTrafficStats(0, 0, 0)) }
    val decisionDelivery = remember { mutableStateOf<DecisionDeliveryState?>(null) }
    val batteryStatus = remember { mutableStateOf(BatteryStatus()) }
    val systemPowerSave = remember { mutableStateOf(powerManager.isPowerSaveMode) }
    val energySavingEnabled = remember {
        mutableStateOf(devicePreferences.getBoolean("energy_saving_enabled", false))
    }
    val lastDataReceivedAt = remember { mutableStateOf<Long?>(null) }
    val allowUnknownColors = remember { mutableStateOf(initialAllowUnknownColors) }
    val beltSpeed = remember { mutableStateOf(initialBeltSpeed.coerceIn(10, 100)) }
    val handledResults = remember {
        devicePreferences.getStringSet(HANDLED_RESULT_IDS_KEY, emptySet())
            .orEmpty()
            .mapNotNull(String::toLongOrNull)
            .toMutableSet()
    }
    val processingResults = remember { mutableSetOf<Long>() }
    val learning = remember { mutableStateOf(LearningStep.OFF) }
    val sampleToName = remember { mutableStateOf<SensorReading?>(null) }
    val enrollmentStopWasRecorded = remember { mutableStateOf(false) }
    val controllerHolder = remember { mutableStateOf<MqttController?>(null) }
    var lastStatusUiUpdate by remember { mutableStateOf(0L) }
    val controller = remember {
        MqttController(
            context = context,
            preferences = devicePreferences,
            onConnection = { value -> connection.value = value },
            onEvent = { value -> eventMessage.value = value },
            onStats = { mqttStats.value = it },
            onHistory = { event ->
                localData.saveConnectionEvent(event) { connectionHistory.value = it }
            },
            onDecisionDelivery = { state -> decisionDelivery.value = state },
            onMessage = { topic, payload ->
                when (topic) {
                    COLOR_TOPIC -> {
                        lastDataReceivedAt.value = System.currentTimeMillis()
                        receiveColor(
                            payload, colors, session.value, learning, sampleToName,
                            enrollmentStopWasRecorded, allowUnknownColors.value,
                            controller = controllerHolder.value,
                            firebase = firebase,
                            localData = localData,
                            eventMessage = eventMessage
                        )
                    }
                    STATUS_TOPIC -> {
                        val now = SystemClock.elapsedRealtime()
                        val minimumRefresh = if (energySavingEnabled.value || systemPowerSave.value) 5_000L else 1_000L
                        if (now - lastStatusUiUpdate >= minimumRefresh) {
                            lastStatusUiUpdate = now
                            lastDataReceivedAt.value = System.currentTimeMillis()
                            receiveStatus(payload, status, eventMessage)
                        }
                    }
                    RESULT_TOPIC -> {
                        lastDataReceivedAt.value = System.currentTimeMillis()
                        receiveResult(
                            payload,
                            colors,
                            handledResults,
                            processingResults,
                            controllerHolder.value,
                            session.value,
                            localData,
                            boxHistory,
                            onHandledResult = { ids ->
                                devicePreferences.edit()
                                    .putStringSet(HANDLED_RESULT_IDS_KEY, ids.mapTo(mutableSetOf(), Long::toString))
                                    .apply()
                            },
                            eventMessage = eventMessage
                        )
                    }
                }
            }
        )
    }
    controllerHolder.value = controller
    DisposableEffect(localData) {
        val pendingObservation = localData.observePendingCount { pendingSyncCount.value = it }
        onDispose { pendingObservation.cancel() }
    }
    var firebaseUsers by remember { mutableStateOf<List<FirebaseUserProfile>>(emptyList()) }
    val seedingStarted = remember { mutableStateOf(false) }

    DisposableEffect(context, controller) {
        fun updateBattery(intent: Intent?) {
            if (intent == null) return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            batteryStatus.value = BatteryStatus((level * 100 / scale).coerceIn(0, 100), plugged)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_BATTERY_CHANGED -> updateBattery(intent)
                    PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                        systemPowerSave.value = powerManager.isPowerSaveMode
                        controller.setEnergySavingEnabled(
                            energySavingEnabled.value || systemPowerSave.value
                        )
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        val stickyIntent = if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        updateBattery(stickyIntent)
        onDispose { context.unregisterReceiver(receiver) }
    }

    DisposableEffect(controller, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> controller.setInForeground(true)
                Lifecycle.Event.ON_STOP -> controller.setInForeground(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(energySavingEnabled.value, systemPowerSave.value) {
        controller.setEnergySavingEnabled(energySavingEnabled.value || systemPowerSave.value)
    }

    LaunchedEffect(firebase) {
        firebase.restoreSession { result ->
            result.onSuccess {
                session.value = it
                FirebaseSyncScheduler.enqueue(context)
                eventMessage.value = "Sesión restaurada."
            }.onFailure {
                eventMessage.value = "No se pudo restaurar la sesión guardada: ${it.message}"
            }
        }
    }

    DisposableEffect(session.value) {
        val currentUser = session.value
        if (currentUser == null) {
            inventoryLoaded.value = false
            onDispose { }
        } else {
            val colorListener = firebase.listenColors(
                onColors = { loaded ->
                    if (loaded.isNotEmpty()) {
                        colors.clear()
                        colors.addAll(loaded)
                        localData.cacheInventory(loaded)
                    }
                    inventoryLoaded.value = colors.isNotEmpty()
                    if (loaded.isEmpty() && colors.isEmpty() && currentUser.role == ROLE_ADMIN && !seedingStarted.value) {
                        seedingStarted.value = true
                        firebase.seedDefaultColors(defaultColors) { error ->
                            eventMessage.value = "No se pudo inicializar el inventario: ${error.message}"
                        }
                    }
                },
                onError = { error -> eventMessage.value = "Error al leer inventario: ${error.message}" }
            )
            val resultListener = if (currentUser.role == ROLE_OPERATOR) null else firebase.listenResults(
                onResults = {
                    boxHistory.value = (it + boxHistory.value)
                        .distinctBy(BoxHistoryEntry::boxId)
                        .sortedByDescending { entry -> entry.timestamp ?: 0L }
                        .take(100)
                },
                onError = { error -> eventMessage.value = "Error al cargar historial: ${error.message}" }
            )
            val userListener = if (currentUser.role == ROLE_ADMIN) {
                firebase.listenUsers(
                    onUsers = { firebaseUsers = it },
                    onError = { eventMessage.value = "Error al leer usuarios: ${it.message}" }
                )
            } else null
            localData.loadInventory { cached ->
                if (colors.isEmpty() && cached.isNotEmpty()) colors.addAll(cached)
                inventoryLoaded.value = colors.isNotEmpty()
            }
            localData.loadRecentResults { localResults ->
                boxHistory.value = (localResults + boxHistory.value)
                    .distinctBy(BoxHistoryEntry::boxId)
                    .sortedByDescending { it.timestamp ?: 0L }
                    .take(100)
            }
            localData.loadConnectionHistory { connectionHistory.value = it }
            onDispose {
                colorListener?.remove()
                resultListener?.remove()
                userListener?.remove()
                firebaseUsers = emptyList()
                boxHistory.value = emptyList()
            }
        }
    }
    LaunchedEffect(session.value) {
        if (session.value != null && initialBrokerConfig.host.isNotBlank()) {
            controller.connect(initialBrokerConfig)
        }
    }
    DisposableEffect(controller) {
        onDispose { controller.disconnect() }
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        if (!firebase.isConfigured) {
            FirebaseSetupRequired()
        } else if (session.value == null) {
            LoginScreen(
                eventMessage = eventMessage.value,
                attemptLimiter = loginAttemptLimiter,
                onSignIn = { email, password, onComplete ->
                    eventMessage.value = "Iniciando sesión..."
                    firebase.signIn(email, password) { result ->
                        result.onSuccess {
                            session.value = it
                            FirebaseSyncScheduler.enqueue(context)
                            eventMessage.value = "Sesión iniciada como ${roleLabel(it.role)}."
                            onComplete(true)
                        }.onFailure {
                            eventMessage.value = "No se pudo iniciar sesión. Verifica tus datos."
                            onComplete(false)
                        }
                    }
                },
                onRegister = { email, password ->
                    eventMessage.value = "Creando cuenta de usuario..."
                    firebase.register(email, password) { result ->
                        result.onSuccess {
                            session.value = it
                            FirebaseSyncScheduler.enqueue(context)
                            eventMessage.value = "Cuenta de operador creada. Un administrador puede cambiar tu rol."
                        }.onFailure {
                            eventMessage.value = "No se pudo crear la cuenta: ${it.message}"
                        }
                    }
                }
            )
        } else {
            val currentUser = session.value!!
            ConveyorScreen(
                initialBrokerConfig = initialBrokerConfig,
                beltSpeed = beltSpeed.value,
                allowUnknownColors = allowUnknownColors.value,
                connection = connection.value,
                pendingSyncCount = pendingSyncCount.value,
                status = status.value,
                colors = colors,
                boxHistory = boxHistory.value,
                connectionHistory = connectionHistory.value,
                batteryStatus = batteryStatus.value,
                systemPowerSave = systemPowerSave.value,
                energySavingEnabled = energySavingEnabled.value,
                mqttStats = mqttStats.value,
                decisionDelivery = decisionDelivery.value,
                lastDataReceivedAt = lastDataReceivedAt.value,
                inventoryLoaded = inventoryLoaded.value,
                eventMessage = eventMessage.value,
                user = currentUser,
                learning = learning.value,
                sampleToName = sampleToName.value,
                firebaseUsers = firebaseUsers,
                onSetEnergySaving = { enabled ->
                    energySavingEnabled.value = enabled
                    devicePreferences.edit().putBoolean("energy_saving_enabled", enabled).apply()
                },
                onSetAllowUnknownColors = {
                    allowUnknownColors.value = it
                    saveAllowUnknownColors(it)
                },
                onApplySpeed = { speed ->
                    beltSpeed.value = speed
                    saveBeltSpeed(speed)
                    controller.publishCommand(
                        JSONObject().put("accion", "velocidad").put("valor", speed)
                    )
                    eventMessage.value = "Velocidad solicitada: $speed pasos/s."
                },
                onConnect = { config ->
                    try {
                        saveBrokerConfig(config)
                        controller.connect(config)
                        eventMessage.value = "Configuración del broker guardada. Conectando por Wi-Fi local."
                    } catch (error: Exception) {
                        eventMessage.value = "No se pudo cifrar la configuración: ${error.message ?: "error del Keystore"}."
                    }
                },
                onDisconnect = { controller.disconnect() },
                onStart = { controller.publishCommand(JSONObject().put("accion", "iniciar")) },
                onStop = { reason ->
                    controller.publishCommand(JSONObject().put("accion", "detener"))
                    val stopReason = reason ?: "Parada solicitada por administrador"
                    eventMessage.value = "Parada solicitada. Guardando el registro..."
                    firebase.recordStop(currentUser, stopReason) { result ->
                        result.onSuccess { eventMessage.value = "Parada enviada y motivo registrado." }
                            .onFailure { eventMessage.value = "No se pudo registrar la parada: ${it.message}" }
                    }
                },
                onBeginEnrollment = {
                    controller.publishCommand(JSONObject().put("accion", "detener"))
                    firebase.recordStop(currentUser, "Preparación para dar de alta un color") { result ->
                        result.onFailure { eventMessage.value = "No se pudo registrar la parada de preparación: ${it.message}" }
                    }
                    enrollmentStopWasRecorded.value = true
                    learning.value = LearningStep.READY
                    eventMessage.value = "Banda detenida. Prepara la caja de muestra y pulsa «Pasar caja de muestra»."
                },
                onPassSample = {
                    learning.value = LearningStep.WAITING_FOR_SAMPLE
                    controller.publishCommand(JSONObject().put("accion", "iniciar"))
                    eventMessage.value = "Pasa una caja de muestra. Al leerla, la app volverá a detener la banda."
                },
                onCancelEnrollment = {
                    if (learning.value != LearningStep.OFF) {
                        controller.publishCommand(JSONObject().put("accion", "iniciar"))
                    }
                    learning.value = LearningStep.OFF
                    sampleToName.value = null
                    eventMessage.value = "Alta de color cancelada."
                },
                onSaveSampleColor = { name, allowed ->
                    val sample = sampleToName.value
                    if (sample == null) {
                        eventMessage.value = "No hay una lectura pendiente para guardar."
                    } else if (colors.any { it.name.equals(name, ignoreCase = true) }) {
                        eventMessage.value = "Ya existe un color con ese nombre."
                    } else {
                        val color = InventoryColor(name.trim(), sample.rgb, allowed = allowed)
                        localData.saveEditedColor(color, currentUser.uid)
                        if (colors.none { it.id == color.id }) colors.add(color)
                        sampleToName.value = null
                        learning.value = LearningStep.OFF
                        eventMessage.value = "Color ${color.name} guardado localmente; sincronización pendiente."
                    }
                },
                onToggleColor = { index ->
                    val color = colors[index]
                    val changed = color.copy(allowed = !color.allowed)
                    colors[index] = changed
                    localData.saveEditedColor(changed, currentUser.uid)
                },
                onSetUserRole = { uid, role ->
                    firebase.setUserRole(uid, role) {
                        eventMessage.value = "No se pudo cambiar el rol: ${it.message}"
                    }
                },
                onSignOut = {
                    controller.disconnect()
                    firebase.signOut()
                    session.value = null
                    learning.value = LearningStep.OFF
                    sampleToName.value = null
                    eventMessage.value = "Sesión cerrada."
                }
            )
        }
    }
}

@Composable
private fun FirebaseSetupRequired() {
    Surface(Modifier.fillMaxSize(), color = Color(0xFF0A0F1C)) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text("Falta configurar Firebase", style = MaterialTheme.typography.headlineSmall, color = Color.White)
            Spacer(Modifier.height(12.dp))
            Text(
                "La app necesita el archivo app/google-services.json y el plugin Google Services. Sigue el tutorial GUIA_PUESTA_EN_MARCHA.txt en la carpeta principal del proyecto.",
                color = Color(0xFFB8C6DF)
            )
        }
    }
}

@Composable
private fun LoginScreen(
    eventMessage: String,
    attemptLimiter: LoginAttemptLimiter,
    onSignIn: (String, String, (Boolean) -> Unit) -> Unit,
    onRegister: (String, String) -> Unit
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isSigningIn by remember { mutableStateOf(false) }
    var lockoutUntil by remember { mutableStateOf(0L) }
    var lockoutSeconds by remember { mutableStateOf(0) }
    var remainingAttempts by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(lockoutUntil) {
        val deadline = lockoutUntil
        while (deadline > 0L) {
            val remainingMillis = deadline - SystemClock.elapsedRealtime()
            if (remainingMillis <= 0L) {
                lockoutUntil = 0L
                lockoutSeconds = 0
                break
            }
            lockoutSeconds = ((remainingMillis + 999) / 1000).toInt()
            delay(minOf(remainingMillis, 1000L))
        }
    }

    Surface(Modifier.fillMaxSize(), color = Color(0xFF0A0F1C)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ConveyorLogo(Modifier.size(48.dp))
                Spacer(Modifier.size(12.dp))
                Text("Banda transportadora", style = MaterialTheme.typography.headlineSmall, color = Color.White)
            }
            Text("Ingresa para continuar", color = Color(0xFF9AA7C2))
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text("Correo") }, singleLine = true)
            OutlinedTextField(
                password, { password = it }, Modifier.fillMaxWidth(),
                label = { Text("Contraseña") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    isSigningIn = true
                    onSignIn(email.trim(), password) { succeeded ->
                        isSigningIn = false
                        if (succeeded) {
                            attemptLimiter.reset()
                            remainingAttempts = null
                        } else {
                            val attempt = attemptLimiter.recordFailure(SystemClock.elapsedRealtime())
                            remainingAttempts = attempt.remainingAttempts.takeIf { it > 0 }
                            if (attempt.lockoutMillis > 0L) {
                                lockoutUntil = SystemClock.elapsedRealtime() + attempt.lockoutMillis
                            }
                        }
                    }
                },
                enabled = email.contains("@") && password.isNotBlank() &&
                    !isSigningIn && lockoutUntil == 0L,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (isSigningIn) "Validando..." else "Iniciar sesión") }
            OutlinedButton(
                onClick = { onRegister(email.trim(), password) },
                enabled = email.contains("@") && password.length >= 6 && !isSigningIn,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Crear cuenta de usuario") }
            if (lockoutSeconds > 0) {
                Text(
                    "Demasiados intentos. Prueba de nuevo en $lockoutSeconds s.",
                    color = Color(0xFFFF6B6B)
                )
            } else if (remainingAttempts != null) {
                Text(
                    "Intentos restantes: $remainingAttempts",
                    color = Color(0xFFFF6B6B)
                )
            }
            Text(eventMessage, Modifier.padding(top = 12.dp), color = Color(0xFF9FCBFF))
        }
    }
}

@Composable
private fun ConveyorScreen(
    initialBrokerConfig: BrokerConfig,
    beltSpeed: Int,
    allowUnknownColors: Boolean,
    connection: String,
    pendingSyncCount: Int,
    status: ConveyorStatus,
    colors: List<InventoryColor>,
    boxHistory: List<BoxHistoryEntry>,
    connectionHistory: List<ConnectionHistoryItem>,
    batteryStatus: BatteryStatus,
    systemPowerSave: Boolean,
    energySavingEnabled: Boolean,
    mqttStats: MqttTrafficStats,
    decisionDelivery: DecisionDeliveryState?,
    lastDataReceivedAt: Long?,
    inventoryLoaded: Boolean,
    eventMessage: String,
    user: AppUser,
    learning: LearningStep,
    sampleToName: SensorReading?,
    firebaseUsers: List<FirebaseUserProfile>,
    onSetEnergySaving: (Boolean) -> Unit,
    onSetAllowUnknownColors: (Boolean) -> Unit,
    onApplySpeed: (Int) -> Unit,
    onConnect: (BrokerConfig) -> Unit,
    onDisconnect: () -> Unit,
    onStart: () -> Unit,
    onStop: (String?) -> Unit,
    onBeginEnrollment: () -> Unit,
    onPassSample: () -> Unit,
    onCancelEnrollment: () -> Unit,
    onSaveSampleColor: (String, Boolean) -> Unit,
    onToggleColor: (Int) -> Unit,
    onSetUserRole: (String, String) -> Unit,
    onSignOut: () -> Unit
) {
    var host by remember(initialBrokerConfig.host) { mutableStateOf(initialBrokerConfig.host) }
    var port by remember(initialBrokerConfig.port) { mutableStateOf(initialBrokerConfig.port.toString()) }
    var username by remember(initialBrokerConfig.username) { mutableStateOf(initialBrokerConfig.username) }
    var password by remember(initialBrokerConfig.password) { mutableStateOf(initialBrokerConfig.password) }
    var tls by remember(initialBrokerConfig.tls) { mutableStateOf(initialBrokerConfig.tls) }
    var showStopDialog by remember { mutableStateOf(false) }
    var stopReason by remember { mutableStateOf("") }
    var sampleName by remember(sampleToName?.id) { mutableStateOf("") }
    var sampleAllowed by remember { mutableStateOf(true) }
    var pendingSpeed by remember(beltSpeed) { mutableStateOf(beltSpeed.toFloat()) }
    var page by remember(user.role) {
        mutableStateOf(if (user.role == ROLE_OPERATOR) AppPage.INVENTORY else AppPage.HOME)
    }
    val connected = connection == "Conectado"
    val isAdmin = user.role == ROLE_ADMIN
    val canControl = user.role == ROLE_MAINTENANCE || isAdmin
    val homePage = if (user.role == ROLE_OPERATOR) AppPage.INVENTORY else AppPage.HOME
    BackHandler(enabled = page != homePage) {
        page = homePage
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0A0F1C)) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ConveyorLogo(Modifier.size(42.dp))
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        when (page) {
                            AppPage.HOME -> "Banda transportadora"
                            AppPage.INVENTORY -> "Inventario"
                            AppPage.SETTINGS -> "Configuración"
                            AppPage.ENERGY -> "Energía"
                            AppPage.HISTORY -> "Historial de conexión"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White
                    )
                    Text(
                        "${user.email} · ${roleLabel(user.role).uppercase()}",
                        color = Color(0xFF9AA7C2),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (page != AppPage.HOME && user.role != ROLE_OPERATOR) {
                    OutlinedButton(onClick = { page = AppPage.HOME }) { Text("Volver") }
                }
            }

            if (user.role != ROLE_OPERATOR) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(onClick = { page = AppPage.HOME }) { Text("Banda") }
                    OutlinedButton(onClick = { page = AppPage.INVENTORY }) { Text("Inventario") }
                    OutlinedButton(onClick = { page = AppPage.ENERGY }) { Text("Energía") }
                    OutlinedButton(onClick = { page = AppPage.HISTORY }) { Text("Historial") }
                    OutlinedButton(onClick = { page = AppPage.SETTINGS }) { Text("Ajustes") }
                }
            }

            if (!connected && page != AppPage.HOME && page != AppPage.INVENTORY) {
                ConnectionWarningCard(connection, lastDataReceivedAt, status)
            }

            when (page) {
                AppPage.HOME -> {
                    if (!connected) {
                        ConnectionWarningCard(connection, lastDataReceivedAt, status)
                    }
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF151E30))) {
                        Column(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Banda", style = MaterialTheme.typography.titleMedium, color = Color.White)
                                Text(
                                    "Velocidad: ${status.speed}",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = Color(0xFF9FCBFF)
                                )
                            }
                            ConveyorBeltAnimation(isBeltMoving(status.belt))
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                StatusValue("Estado", status.belt)
                                StatusValue("Sensor", status.sensor)
                                StatusValue("Servo", status.servo)
                            }
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                StatusValue("Aceptadas", status.accepted.toString(), Color(0xFF6FE28A))
                                StatusValue("Expulsadas", status.rejected.toString(), Color(0xFFFF6B6B))
                                StatusValue(
                                    "MQTT",
                                    connection,
                                    if (connected) Color(0xFF6FE28A) else Color(0xFFFFB84D)
                                )
                            }
                            if (status.receivedAt != null) {
                                Text(
                                    "Último estado: ${formatDateTime(status.receivedAt)}",
                                    color = Color(0xFF9AA7C2),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (pendingSyncCount > 0) {
                                Text(
                                    "Registros pendientes de sincronizar con Firebase: $pendingSyncCount",
                                    color = Color(0xFFFFD27C),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }

                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF122744))) {
                        Row(
                            Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            ConveyorLogo(Modifier.size(42.dp))
                            Column(Modifier.weight(1f)) {
                                val esp32Reported = status.receivedAt != null
                                Text(
                                    when {
                                        !connected -> "Broker MQTT desconectado"
                                        esp32Reported -> "Estado recibido del ESP32"
                                        else -> "Broker conectado; esperando estado ESP32"
                                    },
                                    color = Color.White,
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    if (esp32Reported) {
                                        "Último estado: ${formatDateTime(status.receivedAt!!)}"
                                    } else {
                                        "La conexión con el broker no confirma todavía al ESP32."
                                    },
                                    color = Color(0xFFB8C6DF),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Button(onClick = { page = AppPage.SETTINGS }) {
                                Text("Configurar")
                            }
                        }
                    }

                    if (isAdmin) UnknownColorPolicyCard(
                        allowUnknownColors,
                        onSetAllowUnknownColors
                    )
                    if (canControl) QueuedCommandsCard(
                        state = decisionDelivery,
                        automaticRejectionEnabled = !allowUnknownColors
                    )

                    if (canControl) Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF151E30))) {
                        Column(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("Controles", style = MaterialTheme.typography.titleMedium, color = Color.White)
                            Text(
                                if (isAdmin) {
                                    "La parada queda registrada automáticamente con tu cuenta."
                                } else {
                                    "Al detener, el motivo es obligatorio y queda asociado a tu cuenta en Firebase."
                                },
                                color = Color(0xFF9AA7C2)
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    enabled = connected && inventoryLoaded && learning == LearningStep.OFF,
                                    onClick = {
                                        if (isAdmin) onStop(null) else showStopDialog = true
                                    }
                                ) { Text("Detener banda") }
                                if (isAdmin) {
                                    Button(
                                        enabled = connected && inventoryLoaded && learning == LearningStep.OFF,
                                        onClick = onStart
                                    ) { Text("Iniciar banda") }
                                }
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = { page = AppPage.INVENTORY },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Inventario · ${colors.size} colores")
                    }

                    HistoryCard(boxHistory)
                    Text(eventMessage, color = Color(0xFF9FCBFF), style = MaterialTheme.typography.bodyMedium)
                }

                AppPage.INVENTORY -> {
                    if (!connected) ConnectionWarningCard(connection, lastDataReceivedAt, status)
                    if (pendingSyncCount > 0) {
                        Text(
                            "Registros pendientes de sincronizar con Firebase: $pendingSyncCount",
                            color = Color(0xFFFFD27C)
                        )
                    }
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF151E30))) {
                        Column(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                "Colores registrados · coincidencia RGB ±${RGB_TOLERANCE.toInt()}",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White
                            )
                            if (colors.isEmpty()) {
                                Text("Aún no hay colores en el inventario.", color = Color(0xFF9AA7C2))
                            }
                            colors.forEachIndexed { index, item ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Spacer(
                                        Modifier.size(26.dp)
                                            .background(rgbColor(item.rgb), RoundedCornerShape(6.dp))
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Text(item.name, color = Color.White)
                                        Text(
                                            "RGB ${item.rgb.red}, ${item.rgb.green}, ${item.rgb.blue}",
                                            color = Color(0xFF9AA7C2),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        Text(
                                            "${item.accepted} aceptadas · ${item.rejected} expulsadas",
                                            color = Color(0xFF9AA7C2),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    Text(
                                        if (item.allowed) "Permitido" else "Bloqueado",
                                        color = if (item.allowed) Color(0xFF6FE28A) else Color(0xFFFF6B6B)
                                    )
                                    if (isAdmin) {
                                        Switch(
                                            checked = item.allowed,
                                            onCheckedChange = { onToggleColor(index) }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (isAdmin) {
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF151E30))) {
                            Column(
                                Modifier.fillMaxWidth().padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("Agregar color con una muestra", style = MaterialTheme.typography.titleMedium, color = Color.White)
                                when (learning) {
                                    LearningStep.OFF -> OutlinedButton(
                                        enabled = inventoryLoaded && connected,
                                        onClick = onBeginEnrollment,
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text("Detener y capturar muestra") }
                                    LearningStep.READY -> {
                                        Text("Banda detenida. Prepara la caja de muestra.", color = Color(0xFFFFD27C))
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Button(enabled = connected, onClick = onPassSample) { Text("Pasar muestra") }
                                            OutlinedButton(onClick = onCancelEnrollment) { Text("Cancelar") }
                                        }
                                    }
                                    LearningStep.WAITING_FOR_SAMPLE -> {
                                        Text(
                                            "Esperando la lectura; la banda se detendrá al detectar la caja.",
                                            color = Color(0xFFFFD27C)
                                        )
                                        OutlinedButton(onClick = onCancelEnrollment) { Text("Cancelar alta") }
                                    }
                                    LearningStep.NAMING_COLOR -> {
                                        val sample = sampleToName
                                        if (sample != null) {
                                            Text(
                                                "Muestra: caja ${sample.id} · RGB ${sample.rgb.red}, ${sample.rgb.green}, ${sample.rgb.blue}",
                                                color = Color.White
                                            )
                                            OutlinedTextField(
                                                sampleName,
                                                { sampleName = it },
                                                Modifier.fillMaxWidth(),
                                                label = { Text("Nombre del color") },
                                                singleLine = true
                                            )
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Checkbox(
                                                    checked = sampleAllowed,
                                                    onCheckedChange = { sampleAllowed = it }
                                                )
                                                Text("Permitir este color", color = Color.White)
                                            }
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Button(
                                                    enabled = sampleName.isNotBlank(),
                                                    onClick = { onSaveSampleColor(sampleName.trim(), sampleAllowed) }
                                                ) { Text("Guardar color") }
                                                OutlinedButton(onClick = onCancelEnrollment) { Text("Cancelar") }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF151E30))) {
                        OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
                            Text("Cerrar sesión")
                        }
                    }

                    Text(eventMessage, color = Color(0xFF9FCBFF), style = MaterialTheme.typography.bodyMedium)
                }

                AppPage.SETTINGS -> {
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF151E30))) {
                        Column(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("Conexión MQTT local", style = MaterialTheme.typography.titleMedium, color = Color.White)
                            OutlinedTextField(
                                host,
                                { host = it },
                                Modifier.fillMaxWidth(),
                                label = { Text("IP local del broker") },
                                singleLine = true
                            )
                            OutlinedTextField(
                                port,
                                { port = it.filter(Char::isDigit).take(5) },
                                Modifier.fillMaxWidth(),
                                label = { Text("Puerto") },
                                singleLine = true
                            )
                            OutlinedTextField(
                                username,
                                { username = it },
                                Modifier.fillMaxWidth(),
                                label = { Text("Usuario del broker") },
                                singleLine = true
                            )
                            OutlinedTextField(
                                password,
                                { password = it },
                                Modifier.fillMaxWidth(),
                                label = { Text("Clave del broker") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation()
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(
                                    checked = tls,
                                    onCheckedChange = { enabled ->
                                        tls = enabled
                                        if (enabled && port == "1883") port = "8883"
                                        if (!enabled && port == "8883") port = "1883"
                                    }
                                )
                                Text("Usar TLS", color = Color.White)
                            }
                            Text(
                                "El teléfono, el ESP32 y el broker deben usar la misma Wi-Fi local. Usuario y clave se cifran con Android Keystore. TLS usa el certificado de confianza del teléfono.",
                                color = Color(0xFF9AA7C2),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    enabled = host.isNotBlank() && (port.toIntOrNull()?.let { it in 1..65535 } == true),
                                    onClick = {
                                        onConnect(
                                            BrokerConfig(host.trim(), port.toInt(), username.trim(), password, tls)
                                        )
                                    }
                                ) {
                                    Text("Guardar y conectar")
                                }
                                OutlinedButton(
                                    enabled = connection != "Sin conexión",
                                    onClick = onDisconnect
                                ) { Text("Desconectar") }
                            }
                        }
                    }

                    if (canControl) Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF151E30))) {
                        Column(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("Velocidad de la banda", style = MaterialTheme.typography.titleMedium, color = Color.White)
                            Text(
                                "${pendingSpeed.toInt()} pasos/s · se guarda y envía al ESP32",
                                color = Color(0xFF9AA7C2)
                            )
                            Slider(
                                value = pendingSpeed,
                                onValueChange = { pendingSpeed = it },
                                valueRange = 10f..100f,
                                steps = 89,
                                enabled = connected
                            )
                            Button(
                                onClick = { onApplySpeed(pendingSpeed.toInt()) },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = connected
                            ) { Text("Aplicar velocidad") }
                            Text(
                                "El valor solicitado va de 10 a 100 pasos/s; el ESP32 ajusta el intervalo del motor.",
                                color = Color(0xFF9AA7C2),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    if (isAdmin) UnknownColorPolicyCard(
                        allowUnknownColors,
                        onSetAllowUnknownColors
                    )
                    if (canControl) QueuedCommandsCard(
                        state = decisionDelivery,
                        automaticRejectionEnabled = !allowUnknownColors
                    )

                    if (isAdmin) {
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF151E30))) {
                            Column(
                                Modifier.fillMaxWidth().padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("Cuentas y roles", style = MaterialTheme.typography.titleMedium, color = Color.White)
                                Text("Operador: inventario · Mantenimiento: controles y velocidad · Administrador: colores y usuarios", color = Color(0xFF9AA7C2))
                                if (firebaseUsers.isEmpty()) {
                                    Text("No hay otras cuentas cargadas.", color = Color(0xFF9AA7C2))
                                }
                                firebaseUsers.forEach { profile ->
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(profile.email, color = Color.White)
                                        }
                                        OutlinedButton(
                                            enabled = profile.uid != user.uid,
                                            onClick = {
                                                val nextRole = when (profile.role) {
                                                    ROLE_OPERATOR, "usuario" -> ROLE_MAINTENANCE
                                                    ROLE_MAINTENANCE -> ROLE_ADMIN
                                                    else -> ROLE_OPERATOR
                                                }
                                                onSetUserRole(
                                                    profile.uid,
                                                    nextRole
                                                )
                                            }
                                        ) {
                                            val nextRole = when (profile.role) {
                                                ROLE_OPERATOR, "usuario" -> ROLE_MAINTENANCE
                                                ROLE_MAINTENANCE -> ROLE_ADMIN
                                                else -> ROLE_OPERATOR
                                            }
                                            Text("Cambiar a ${roleLabel(nextRole)}")
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF151E30))) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text("Sesión", style = MaterialTheme.typography.titleMedium, color = Color.White)
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = onSignOut) { Text("Cerrar sesión") }
                        }
                    }
                    Text(eventMessage, color = Color(0xFF9FCBFF), style = MaterialTheme.typography.bodyMedium)
                }

                AppPage.ENERGY -> {
                    EnergyPanel(
                        batteryStatus, systemPowerSave, energySavingEnabled, connection,
                        mqttStats, onSetEnergySaving
                    )
                    Text(eventMessage, color = Color(0xFF9FCBFF), style = MaterialTheme.typography.bodyMedium)
                }

                AppPage.HISTORY -> ConnectionHistoryCard(connectionHistory)
            }
        }
    }

    if (showStopDialog) {
        AlertDialog(
            onDismissRequest = { showStopDialog = false },
            title = { Text("Detener banda") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Escribe el motivo. Se guardará junto con tu cuenta y la hora en Firebase.")
                    OutlinedTextField(stopReason, { stopReason = it }, label = { Text("Motivo obligatorio") })
                }
            },
            confirmButton = {
                TextButton(
                    enabled = stopReason.isNotBlank(),
                    onClick = {
                        onStop(stopReason.trim())
                        stopReason = ""
                        showStopDialog = false
                    }
                ) { Text("Confirmar parada") }
            },
            dismissButton = { TextButton(onClick = { showStopDialog = false }) { Text("Cancelar") } }
        )
    }
}

@Composable
private fun ConveyorLogo(modifier: Modifier = Modifier) {
    Canvas(
        modifier.background(Color(0xFF0B1F3A), RoundedCornerShape(12.dp))
    ) {
        val beltTop = size.height * 0.62f
        drawRoundRect(
            color = Color(0xFF80BFFF),
            topLeft = Offset(size.width * 0.15f, beltTop),
            size = Size(size.width * 0.7f, size.height * 0.14f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height * 0.07f)
        )
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(size.width * 0.39f, size.height * 0.25f),
            size = Size(size.width * 0.24f, size.height * 0.3f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height * 0.04f)
        )
        drawLine(
            color = Color(0xFF0B1F3A),
            start = Offset(size.width * 0.51f, size.height * 0.27f),
            end = Offset(size.width * 0.51f, size.height * 0.53f),
            strokeWidth = size.width * 0.025f
        )
    }
}

@Composable
private fun ConnectionWarningCard(
    connection: String,
    lastDataReceivedAt: Long?,
    status: ConveyorStatus
) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF4A2D16))) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("Sin conexión con el broker", color = Color.White, style = MaterialTheme.typography.titleSmall)
            Text("Controles de banda deshabilitados. Se conservan en pantalla los últimos datos.", color = Color(0xFFFFD7A0))
            Text(
                "Estado: $connection · último dato: ${lastDataReceivedAt?.let(::formatDateTime) ?: "sin datos recibidos"}",
                color = Color(0xFFFFE5C2),
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                status.receivedAt?.let {
                    "Último estado conservado: ${status.belt} · ${status.speed} · ${formatDateTime(it)}"
                } ?: "Todavía no se recibió un estado válido.",
                color = Color(0xFFFFE5C2),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun QueuedCommandsCard(
    state: DecisionDeliveryState?,
    automaticRejectionEnabled: Boolean
) {
    if (state == null) {
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF151E30))) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Comandos en cola", color = Color.White, style = MaterialTheme.typography.titleSmall)
                Text("No hay comandos pendientes de Wi-Fi/MQTT.", color = Color(0xFFB8C6DF), style = MaterialTheme.typography.bodySmall)
                Text(
                    if (automaticRejectionEnabled) {
                        "Rechazo automático preparado en cola: al llegar una caja no permitida, se enviará al recuperar Wi-Fi/MQTT."
                    } else {
                        "Rechazo automático desactivado."
                    },
                    color = if (automaticRejectionEnabled) Color(0xFF6FE28A) else Color(0xFF9AA7C2),
                    style = MaterialTheme.typography.bodySmall
                )
                Text("Iniciar, detener y velocidad permanecen deshabilitados sin conexión.", color = Color(0xFF9AA7C2), style = MaterialTheme.typography.bodySmall)
            }
        }
        return
    }
    var now by remember(state.id, state.status, state.expiresAtElapsed) {
        mutableStateOf(SystemClock.elapsedRealtime())
    }
    if (state.status == DecisionDeliveryStatus.QUEUED_FOR_WIFI) {
        LaunchedEffect(state.id, state.expiresAtElapsed) {
            while (state.expiresAtElapsed != null && now < state.expiresAtElapsed) {
                delay(1_000L)
                now = SystemClock.elapsedRealtime()
            }
        }
    }
    val action = if (state.action == "expulsar") "Rechazo automático" else "Decisión automática"
    val remainingSeconds = state.expiresAtElapsed?.let {
        ((it - now).coerceAtLeast(0L) + 999L) / 1_000L
    }
    val (title, description, color) = when (state.status) {
        DecisionDeliveryStatus.QUEUED_FOR_WIFI -> Triple(
            "$action en cola · caja ${state.id}",
            "El dato se enviará cuando se conecte a Wi-Fi/MQTT. Tiempo disponible: ${remainingSeconds ?: 0} s; la banda permanece pausada.",
            Color(0xFFFFD27C)
        )
        DecisionDeliveryStatus.PUBLISHED_TO_BROKER -> Triple(
            "$action publicado · caja ${state.id}",
            "El broker confirmó la publicación. Esperando banda/resultado para confirmar que el ESP32 ejecutó la decisión.",
            Color(0xFF9FCBFF)
        )
        DecisionDeliveryStatus.CONFIRMED_BY_ESP32 -> Triple(
            "Interacción confirmada · caja ${state.id}",
            "El ESP32 publicó banda/resultado: la decisión llegó y la operación quedó confirmada.",
            Color(0xFF6FE28A)
        )
        DecisionDeliveryStatus.EXPIRED -> Triple(
            "$action vencido · caja ${state.id}",
            "No se envió dentro de 10 s. Se descartó para no actuar sobre una caja diferente.",
            Color(0xFFFF6B6B)
        )
    }
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF151E30))) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Comandos en cola", color = Color.White, style = MaterialTheme.typography.titleSmall)
            Text(title, color = color, style = MaterialTheme.typography.titleSmall)
            Text(description, color = Color(0xFFB8C6DF), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun EnergyPanel(
    battery: BatteryStatus,
    systemPowerSave: Boolean,
    energySavingEnabled: Boolean,
    connection: String,
    mqttStats: MqttTrafficStats,
    onSetEnergySaving: (Boolean) -> Unit
) {
    val activeEnergySaving = energySavingEnabled || systemPowerSave
    val connected = connection == "Conectado"
    var now by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(connected, activeEnergySaving, mqttStats.sampledAtElapsed) {
        while (connected) {
            delay(if (activeEnergySaving) 10_000L else 1_000L)
            now = SystemClock.elapsedRealtime()
        }
    }
    val connectedMillis = mqttStats.connectedMillis +
        if (connected) (now - mqttStats.sampledAtElapsed).coerceAtLeast(0L) else 0L
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF151E30))) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Energía", style = MaterialTheme.typography.titleMedium, color = Color.White)
            Text("Batería: ${battery.percentage}% · ${if (battery.charging) "cargando" else "sin carga"}", color = Color.White)
            Text("Ahorro del teléfono: ${if (systemPowerSave) "activo" else "inactivo"}", color = Color(0xFFB8C6DF))
            Text("Tiempo conectado al broker: ${formatDuration(connectedMillis)}", color = Color(0xFFB8C6DF))
            Text("Mensajes enviados: ${mqttStats.sent} · recibidos: ${mqttStats.received}", color = Color(0xFFB8C6DF))
            Text("Keep-alive: ${if (activeEnergySaving) 120 else 30} s · QoS de banda/estado: ${if (activeEnergySaving) 0 else 1}", color = Color(0xFFB8C6DF))
            Text("ESP32: ahorro Wi-Fi activo; sensor a 20 % y lectura cada 150 ms mientras la banda avanza. Las bobinas se liberan al detenerse o esperar una decisión.", color = Color(0xFFB8C6DF), style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = activeEnergySaving,
                    onCheckedChange = onSetEnergySaving,
                    enabled = !systemPowerSave
                )
                Column {
                    Text("Modo ahorro de energía", color = Color.White)
                    if (systemPowerSave) {
                        Text("Activado por el sistema; se mantendrá activo mientras dure el ahorro del teléfono.", color = Color(0xFFFFD27C), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Text(
                "El estado MQTT actualiza la interfaz con menor frecuencia cuando el ahorro está activo.",
                color = Color(0xFF9AA7C2),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun ConnectionHistoryCard(events: List<ConnectionHistoryItem>) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF151E30))) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Historial de conexión", style = MaterialTheme.typography.titleMedium, color = Color.White)
            if (events.isEmpty()) Text("Aún no hay cortes ni recuperaciones registrados.", color = Color(0xFF9AA7C2))
            events.take(30).forEach { event ->
                Text(
                    "${if (event.connected) "Conexión recuperada" else "Conexión perdida"} · ${formatDateTime(event.occurredAt)}",
                    color = if (event.connected) Color(0xFF6FE28A) else Color(0xFFFFB84D)
                )
            }
        }
    }
}

private fun formatDateTime(timestamp: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(timestamp))

private fun formatDuration(durationMillis: Long): String {
    val totalSeconds = durationMillis.coerceAtLeast(0L) / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d h %02d min".format(hours, minutes) else "%02d min %02d s".format(minutes, seconds)
}

private fun roleLabel(role: String): String = when (role) {
    ROLE_ADMIN -> "Administrador"
    ROLE_MAINTENANCE -> "Mantenimiento"
    ROLE_OPERATOR, "usuario" -> "Operador"
    else -> "Rol no reconocido"
}

@Composable
private fun UnknownColorPolicyCard(
    allowUnknownColors: Boolean,
    onSetAllowUnknownColors: (Boolean) -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF151E30))) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Rechazar automáticamente lo que no está en el inventario",
                color = Color.White,
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                if (allowUnknownColors) {
                    "Desactivado: un color sin coincidencia se acepta."
                } else {
                    "Activado: solo pasan colores permitidos del inventario."
                },
                color = Color(0xFF9AA7C2)
            )
            Button(
                onClick = { onSetAllowUnknownColors(!allowUnknownColors) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (allowUnknownColors) "Activar rechazo automático" else "Desactivar rechazo automático")
            }
        }
    }
}

@Composable
private fun HistoryCard(boxHistory: List<BoxHistoryEntry>) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF151E30))) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Últimas cajas", style = MaterialTheme.typography.titleMedium, color = Color.White)
            if (boxHistory.isEmpty()) {
                Text("Aún no hay resultados guardados en Firebase.", color = Color(0xFF9AA7C2))
            } else {
                boxHistory.forEach { entry ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Spacer(Modifier.size(24.dp).background(rgbColor(entry.rgb), RoundedCornerShape(6.dp)))
                        Column(Modifier.weight(1f)) {
                            Text("Caja #${entry.boxId} · ${entry.colorName}", color = Color.White)
                            val time = entry.timestamp?.let {
                                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))
                            } ?: "guardando hora"
                            Text(
                                "RGB ${entry.rgb.red}, ${entry.rgb.green}, ${entry.rgb.blue} · $time",
                                color = Color(0xFF9AA7C2),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Text(
                            entry.result.uppercase(),
                            color = if (entry.result == "aceptada") Color(0xFF6FE28A) else Color(0xFFFF6B6B)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConveyorBeltAnimation(moving: Boolean) {
    val animation = rememberInfiniteTransition(label = "belt-motion")
    val phase by animation.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing)),
        label = "belt-phase"
    )
    Canvas(
        Modifier.fillMaxWidth()
            .height(130.dp)
            .background(Color(0xFF0C1424), RoundedCornerShape(10.dp))
    ) {
        val beltLeft = size.width * 0.06f
        val beltTop = size.height * 0.55f
        val beltWidth = size.width * 0.88f
        val beltHeight = size.height * 0.24f
        drawRoundRect(
            color = Color(0xFF303B4E),
            topLeft = Offset(beltLeft, beltTop),
            size = Size(beltWidth, beltHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(18f)
        )
        drawLine(
            color = Color(0xFF748199),
            start = Offset(beltLeft + 8f, beltTop + 3f),
            end = Offset(beltLeft + beltWidth - 8f, beltTop + 3f),
            strokeWidth = 3f
        )
        val parcelWidth = size.width * 0.12f
        val parcelX = beltLeft + if (moving) {
            phase * (beltWidth - parcelWidth)
        } else {
            (beltWidth - parcelWidth) / 2f
        }
        val parcelTop = beltTop - size.height * 0.25f
        drawRoundRect(
            color = Color(0xFFF0A44B),
            topLeft = Offset(parcelX, parcelTop),
            size = Size(parcelWidth, size.height * 0.25f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f)
        )
        drawLine(
            color = Color(0xFFFFD18B),
            start = Offset(parcelX + parcelWidth / 2f, parcelTop + 4f),
            end = Offset(parcelX + parcelWidth / 2f, beltTop - 2f),
            strokeWidth = 2f
        )
    }
}

private fun isBeltMoving(value: String): Boolean {
    val state = value.lowercase()
    return listOf("marcha", "andando", "activa", "funcionando", "corriendo").any(state::contains)
}

@Composable
private fun StatusValue(label: String, value: String, valueColor: Color = Color.White) {
    Column {
        Text(label, color = Color(0xFF9AA7C2), style = MaterialTheme.typography.labelSmall)
        Text(value, color = valueColor, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun receiveColor(
    payload: String,
    colors: List<InventoryColor>,
    user: AppUser?,
    learning: androidx.compose.runtime.MutableState<LearningStep>,
    sampleToName: androidx.compose.runtime.MutableState<SensorReading?>,
    enrollmentStopWasRecorded: androidx.compose.runtime.MutableState<Boolean>,
    allowUnknownColors: Boolean,
    controller: MqttController?,
    firebase: FirebaseRepository,
    localData: LocalDataRepository,
    eventMessage: androidx.compose.runtime.MutableState<String>
) {
    try {
        val valid = MqttJsonValidator.color(payload)
        val uid = user?.uid ?: return
        val reading = SensorReading(valid.id, valid.rgb)
        localData.saveReading(payload, uid)
        if (user?.role == ROLE_ADMIN && learning.value == LearningStep.WAITING_FOR_SAMPLE) {
            controller?.publishCommand(JSONObject().put("accion", "aceptar").put("id", valid.id))
            controller?.publishCommand(JSONObject().put("accion", "detener"))
            if (enrollmentStopWasRecorded.value) {
                firebase.recordStop(user, "Parada automática para capturar una muestra de color") {
                    it.onFailure { error ->
                        eventMessage.value = "No se pudo guardar la parada de captura: ${error.message}"
                    }
                }
            }
            sampleToName.value = reading
            learning.value = LearningStep.NAMING_COLOR
            eventMessage.value = "Muestra capturada. La banda se detuvo; escribe el nombre para guardarla."
            return
        }
        val action = decisionFor(reading.rgb, colors, allowUnknownColors)
        controller?.publishCommand(JSONObject().put("accion", action).put("id", valid.id))
        eventMessage.value = "Caja ${reading.id}: RGB(${reading.rgb.red}, ${reading.rgb.green}, ${reading.rgb.blue}) → $action"
    } catch (error: Exception) {
        eventMessage.value = "Lectura inválida: ${error.message ?: "JSON incorrecto"}"
    }
}

private fun receiveStatus(
    payload: String,
    status: androidx.compose.runtime.MutableState<ConveyorStatus>,
    eventMessage: androidx.compose.runtime.MutableState<String>
) {
    try {
        val parsed = MqttJsonValidator.status(payload)
        status.value = ConveyorStatus(
            belt = parsed.belt,
            sensor = parsed.sensor,
            servo = parsed.servo.toString(),
            accepted = parsed.accepted,
            rejected = parsed.rejected,
            speed = "${parsed.speed} ${parsed.speedUnit}",
            receivedAt = System.currentTimeMillis()
        )
    } catch (error: Exception) {
        eventMessage.value = "Estado inválido: ${error.message ?: "JSON incorrecto"}"
    }
}

private fun receiveResult(
    payload: String,
    colors: MutableList<InventoryColor>,
    handledResults: MutableSet<Long>,
    processingResults: MutableSet<Long>,
    controller: MqttController?,
    session: AppUser?,
    localData: LocalDataRepository,
    boxHistory: androidx.compose.runtime.MutableState<List<BoxHistoryEntry>>,
    onHandledResult: (Set<Long>) -> Unit,
    eventMessage: androidx.compose.runtime.MutableState<String>
) {
    try {
        val parsed = MqttJsonValidator.result(payload)
        val id = parsed.id
        val rgb = parsed.rgb
        val result = parsed.result
        val uid = session?.uid ?: return
        if (id in handledResults) {
            controller?.publishCommand(JSONObject().put("accion", "resultado_ack").put("id", id))
            return
        }
        if (!processingResults.add(id)) return
        val index = nearestColorIndex(rgb, colors)
        val color = colors.getOrNull(index)
        localData.saveResult(payload, uid, color) { stored ->
            processingResults.remove(id)
            stored.onSuccess {
                if (color != null) {
                    colors[index] = if (result == "aceptada") {
                        color.copy(accepted = color.accepted + 1)
                    } else {
                        color.copy(rejected = color.rejected + 1)
                    }
                    localData.cacheInventory(colors)
                }
                handledResults.add(id)
                while (handledResults.size > 500) handledResults.remove(handledResults.minOrNull()!!)
                onHandledResult(handledResults)
                val displayedName = color?.name ?: "No inventariado"
                val history = BoxHistoryEntry(id.toString(), displayedName, rgb, result, System.currentTimeMillis())
                boxHistory.value = listOf(history) + boxHistory.value.filterNot { it.boxId == id.toString() }.take(99)
                eventMessage.value = "Caja $id $result · $displayedName · guardada localmente"
                controller?.publishCommand(
                    JSONObject().put("accion", "resultado_ack").put("id", id)
                )
            }.onFailure { error ->
                eventMessage.value =
                    "No se pudo guardar el resultado de la caja $id; el ESP32 lo reenviará: ${error.message ?: "error local"}"
            }
        }
    } catch (error: Exception) {
        eventMessage.value = "Resultado inválido: ${error.message ?: "JSON incorrecto"}"
    }
}

private fun parseRgb(json: JSONObject): Rgb {
    val rgb = Rgb(json.getInt("r"), json.getInt("g"), json.getInt("b"))
    if (rgb.red !in 0..255 || rgb.green !in 0..255 || rgb.blue !in 0..255) {
        throw JSONException("Los componentes RGB deben estar entre 0 y 255.")
    }
    return rgb
}

private fun rgbColor(rgb: Rgb) = Color(rgb.red / 255f, rgb.green / 255f, rgb.blue / 255f)

private fun distance(left: Rgb, right: Rgb): Double {
    val red = left.red - right.red
    val green = left.green - right.green
    val blue = left.blue - right.blue
    return kotlin.math.sqrt((red * red + green * green + blue * blue).toDouble())
}

internal fun nearestColorIndex(rgb: Rgb, colors: List<InventoryColor>, tolerance: Double = RGB_TOLERANCE): Int {
    var nearest = -1
    var nearestDistance = tolerance
    colors.forEachIndexed { index, color ->
        val currentDistance = distance(rgb, color.rgb)
        if (currentDistance < nearestDistance) {
            nearest = index
            nearestDistance = currentDistance
        }
    }
    return nearest
}

internal fun decisionFor(
    rgb: Rgb,
    colors: List<InventoryColor>,
    allowUnknown: Boolean = true
): String {
    val index = nearestColorIndex(rgb, colors)
    return when {
        index == -1 && !allowUnknown -> "expulsar"
        index == -1 -> "aceptar"
        colors[index].allowed -> "aceptar"
        else -> "expulsar"
    }
}
