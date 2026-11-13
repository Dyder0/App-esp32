package com.example.iot

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONException
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.UUID

private const val COLOR_TOPIC = "banda/sensor/color"
private const val STATUS_TOPIC = "banda/estado"
private const val RESULT_TOPIC = "banda/resultado"
private const val COMMAND_TOPIC = "banda/comandos"
private const val ROLE_ADMIN = "admin"
private const val ROLE_USER = "usuario"
private const val RGB_TOLERANCE = 90.0

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
    val speed: String = "Sin datos"
)

private data class SensorReading(val id: Long, val rgb: Rgb)

private enum class LearningStep { OFF, READY, WAITING_FOR_SAMPLE, NAMING_COLOR }
private enum class AppPage { HOME, INVENTORY, SETTINGS }

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
        val firebase = FirebaseRepository(applicationContext)
        setContent {
            ConveyorApp(
                firebase = firebase,
                initialHost = preferences.getString("broker_host", "192.168.1.100") ?: "192.168.1.100",
                initialPort = preferences.getString("broker_port", "1883") ?: "1883",
                initialMqttUser = preferences.getString("mqtt_user", "") ?: "",
                initialBeltSpeed = preferences.getInt("belt_speed", 60),
                initialAllowUnknownColors = preferences.getBoolean("allow_unknown_colors", false),
                saveBroker = { host, port, username ->
                    preferences.edit()
                        .putString("broker_host", host)
                        .putString("broker_port", port)
                        .putString("mqtt_user", username)
                        .apply()
                },
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
    initialHost: String,
    initialPort: String,
    initialMqttUser: String,
    initialBeltSpeed: Int,
    initialAllowUnknownColors: Boolean,
    saveBroker: (String, String, String) -> Unit,
    saveAllowUnknownColors: (Boolean) -> Unit,
    saveBeltSpeed: (Int) -> Unit
) {
    val colors = remember { mutableStateListOf<InventoryColor>().apply { addAll(defaultColors) } }
    val connection = remember { mutableStateOf("Desconectado") }
    val status = remember { mutableStateOf(ConveyorStatus()) }
    val eventMessage = remember { mutableStateOf("Ingresa con tu cuenta para comenzar.") }
    val session = remember { mutableStateOf<AppUser?>(null) }
    val inventoryLoaded = remember { mutableStateOf(false) }
    val boxHistory = remember { mutableStateOf<List<BoxHistoryEntry>>(emptyList()) }
    val allowUnknownColors = remember { mutableStateOf(initialAllowUnknownColors) }
    val beltSpeed = remember { mutableStateOf(initialBeltSpeed.coerceIn(10, 100)) }
    val handledResults = remember { mutableSetOf<Long>() }
    val learning = remember { mutableStateOf(LearningStep.OFF) }
    val sampleToName = remember { mutableStateOf<SensorReading?>(null) }
    val enrollmentStopWasRecorded = remember { mutableStateOf(false) }
    val mqttHolder = remember { mutableStateOf<MqttController?>(null) }
    val mqtt = remember {
        MqttController(
            onConnection = { value -> connection.value = value },
            onEvent = { value -> eventMessage.value = value },
            onMessage = { topic, payload ->
                when (topic) {
                    COLOR_TOPIC -> receiveColor(
                        payload, colors, session.value, learning, sampleToName,
                        enrollmentStopWasRecorded, allowUnknownColors.value,
                        mqtt = mqttHolder.value, firebase,
                        eventMessage
                    )
                    STATUS_TOPIC -> receiveStatus(payload, status, eventMessage)
                    RESULT_TOPIC -> receiveResult(payload, colors, handledResults, firebase, session.value, eventMessage)
                }
            }
        )
    }
    mqttHolder.value = mqtt
    var firebaseUsers by remember { mutableStateOf<List<FirebaseUserProfile>>(emptyList()) }
    val seedingStarted = remember { mutableStateOf(false) }

    DisposableEffect(session.value) {
        val currentUser = session.value
        if (currentUser == null) {
            inventoryLoaded.value = false
            onDispose { }
        } else {
            val colorListener = firebase.listenColors(
                onColors = { loaded ->
                    colors.clear()
                    colors.addAll(loaded)
                    inventoryLoaded.value = loaded.isNotEmpty()
                    if (loaded.isEmpty() && currentUser.role == ROLE_ADMIN && !seedingStarted.value) {
                        seedingStarted.value = true
                        firebase.seedDefaultColors(defaultColors) { error ->
                            eventMessage.value = "No se pudo inicializar el inventario: ${error.message}"
                        }
                    }
                },
                onError = { error -> eventMessage.value = "Error al leer inventario: ${error.message}" }
            )
            val resultListener = firebase.listenResults(
                onResults = { boxHistory.value = it },
                onError = { error -> eventMessage.value = "Error al cargar historial: ${error.message}" }
            )
            val userListener = if (currentUser.role == ROLE_ADMIN) {
                firebase.listenUsers(
                    onUsers = { firebaseUsers = it },
                    onError = { eventMessage.value = "Error al leer usuarios: ${it.message}" }
                )
            } else null
            onDispose {
                colorListener?.remove()
                resultListener?.remove()
                userListener?.remove()
                firebaseUsers = emptyList()
                boxHistory.value = emptyList()
            }
        }
    }
    DisposableEffect(mqtt) {
        onDispose { mqtt.disconnect() }
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        if (!firebase.isConfigured) {
            FirebaseSetupRequired()
        } else if (session.value == null) {
            LoginScreen(
                eventMessage = eventMessage.value,
                onSignIn = { email, password ->
                    eventMessage.value = "Iniciando sesión..."
                    firebase.signIn(email, password) { result ->
                        result.onSuccess {
                            session.value = it
                            eventMessage.value = "Sesión iniciada como ${if (it.role == ROLE_ADMIN) "administrador" else "usuario"}."
                        }.onFailure {
                            eventMessage.value = "No se pudo iniciar sesión: ${it.message}"
                        }
                    }
                },
                onRegister = { email, password ->
                    eventMessage.value = "Creando cuenta de usuario..."
                    firebase.register(email, password) { result ->
                        result.onSuccess {
                            session.value = it
                            eventMessage.value = "Cuenta de usuario creada. Un administrador puede cambiar tu rol."
                        }.onFailure {
                            eventMessage.value = "No se pudo crear la cuenta: ${it.message}"
                        }
                    }
                }
            )
        } else {
            val currentUser = session.value!!
            ConveyorScreen(
                initialHost = initialHost,
                initialPort = initialPort,
                initialMqttUser = initialMqttUser,
                beltSpeed = beltSpeed.value,
                allowUnknownColors = allowUnknownColors.value,
                connection = connection.value,
                status = status.value,
                colors = colors,
                boxHistory = boxHistory.value,
                inventoryLoaded = inventoryLoaded.value,
                eventMessage = eventMessage.value,
                user = currentUser,
                learning = learning.value,
                sampleToName = sampleToName.value,
                firebaseUsers = firebaseUsers,
                onSetAllowUnknownColors = {
                    allowUnknownColors.value = it
                    saveAllowUnknownColors(it)
                },
                onApplySpeed = { speed ->
                    beltSpeed.value = speed
                    saveBeltSpeed(speed)
                    mqtt.publishCommand(
                        JSONObject().put("accion", "velocidad").put("valor", speed)
                    )
                    eventMessage.value = "Velocidad solicitada: $speed%."
                },
                onConnect = { host, port, username, password ->
                    saveBroker(host, port, username)
                    handledResults.clear()
                    mqtt.connect(host, port.toInt(), username, password)
                },
                onDisconnect = { mqtt.disconnect() },
                onStart = { mqtt.publishCommand(JSONObject().put("accion", "iniciar")) },
                onStop = { reason ->
                    mqtt.publishCommand(JSONObject().put("accion", "detener"))
                    val stopReason = reason ?: "Parada solicitada por administrador"
                    eventMessage.value = "Parada enviada. Guardando el registro..."
                    firebase.recordStop(currentUser, stopReason) { result ->
                        result.onSuccess { eventMessage.value = "Parada enviada y motivo registrado." }
                            .onFailure { eventMessage.value = "La banda recibió la parada, pero Firebase no guardó el motivo: ${it.message}" }
                    }
                },
                onBeginEnrollment = {
                    mqtt.publishCommand(JSONObject().put("accion", "detener"))
                    firebase.recordStop(currentUser, "Preparación para dar de alta un color") { result ->
                        result.onFailure { eventMessage.value = "No se pudo registrar la parada de preparación: ${it.message}" }
                    }
                    enrollmentStopWasRecorded.value = true
                    learning.value = LearningStep.READY
                    eventMessage.value = "Banda detenida. Prepara la caja de muestra y pulsa «Pasar caja de muestra»."
                },
                onPassSample = {
                    learning.value = LearningStep.WAITING_FOR_SAMPLE
                    mqtt.publishCommand(JSONObject().put("accion", "iniciar"))
                    eventMessage.value = "Pasa una caja de muestra. Al leerla, la app volverá a detener la banda."
                },
                onCancelEnrollment = {
                    if (learning.value != LearningStep.OFF) {
                        mqtt.publishCommand(JSONObject().put("accion", "iniciar"))
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
                        firebase.saveColor(color) { result ->
                            result.onSuccess {
                                if (colors.none { it.id == color.id }) colors.add(color)
                                sampleToName.value = null
                                learning.value = LearningStep.OFF
                                eventMessage.value = "Color ${color.name} guardado en el inventario."
                            }.onFailure {
                                eventMessage.value = "No se pudo guardar el color: ${it.message}"
                            }
                        }
                    }
                },
                onToggleColor = { index ->
                    val color = colors[index]
                    val changed = color.copy(allowed = !color.allowed)
                    colors[index] = changed
                    firebase.setColorAllowed(changed, changed.allowed) {
                        val currentIndex = colors.indexOfFirst { it.id == color.id }
                        if (currentIndex >= 0) colors[currentIndex] = color
                        eventMessage.value = "No se pudo actualizar ${changed.name}: ${it.message}"
                    }
                },
                onSetUserRole = { uid, role ->
                    firebase.setUserRole(uid, role) {
                        eventMessage.value = "No se pudo cambiar el rol: ${it.message}"
                    }
                },
                onSignOut = {
                    mqtt.disconnect()
                    firebase.signOut()
                    session.value = null
                    handledResults.clear()
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
    onSignIn: (String, String) -> Unit,
    onRegister: (String, String) -> Unit
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
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
            Text("Acceso con Firebase · administrador o usuario", color = Color(0xFF9AA7C2))
            Text(
                "Primero inicia sesión con Firebase. Después, en la pantalla principal, pulsa «Conectar a Mosquitto» para configurar MQTT.",
                color = Color(0xFF9AA7C2)
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text("Correo") }, singleLine = true)
            OutlinedTextField(
                password, { password = it }, Modifier.fillMaxWidth(),
                label = { Text("Contraseña") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { onSignIn(email.trim(), password) },
                enabled = email.contains("@") && password.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Iniciar sesión") }
            OutlinedButton(
                onClick = { onRegister(email.trim(), password) },
                enabled = email.contains("@") && password.length >= 6,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Crear cuenta de usuario") }
            Spacer(Modifier.height(10.dp))
            Text("La primera cuenta también nace como usuario. El tutorial explica cómo nombrar al primer administrador sin poner una contraseña secreta en el código.", color = Color(0xFF9AA7C2))
            Text(eventMessage, Modifier.padding(top = 12.dp), color = Color(0xFF9FCBFF))
        }
    }
}

@Composable
private fun ConveyorScreen(
    initialHost: String,
    initialPort: String,
    initialMqttUser: String,
    beltSpeed: Int,
    allowUnknownColors: Boolean,
    connection: String,
    status: ConveyorStatus,
    colors: List<InventoryColor>,
    boxHistory: List<BoxHistoryEntry>,
    inventoryLoaded: Boolean,
    eventMessage: String,
    user: AppUser,
    learning: LearningStep,
    sampleToName: SensorReading?,
    firebaseUsers: List<FirebaseUserProfile>,
    onSetAllowUnknownColors: (Boolean) -> Unit,
    onApplySpeed: (Int) -> Unit,
    onConnect: (String, String, String, String) -> Unit,
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
    var host by remember { mutableStateOf(initialHost) }
    var port by remember { mutableStateOf(initialPort) }
    var mqttUsername by remember { mutableStateOf(initialMqttUser) }
    var mqttPassword by remember { mutableStateOf("") }
    var showStopDialog by remember { mutableStateOf(false) }
    var stopReason by remember { mutableStateOf("") }
    var sampleName by remember(sampleToName?.id) { mutableStateOf("") }
    var sampleAllowed by remember { mutableStateOf(true) }
    var pendingSpeed by remember(beltSpeed) { mutableStateOf(beltSpeed.toFloat()) }
    var page by remember { mutableStateOf(AppPage.HOME) }
    val connected = connection == "Conectado"
    val isAdmin = user.role == ROLE_ADMIN
    BackHandler(enabled = page != AppPage.HOME) {
        page = AppPage.HOME
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
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White
                    )
                    Text(
                        "${user.email} · ${if (isAdmin) "ADMINISTRADOR" else "USUARIO"}",
                        color = Color(0xFF9AA7C2),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (page != AppPage.HOME) {
                    OutlinedButton(onClick = { page = AppPage.HOME }) { Text("Volver") }
                }
            }

            when (page) {
                AppPage.HOME -> {
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
                                Text(
                                    if (connected) "MQTT conectado" else "MQTT desconectado",
                                    color = Color.White,
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    "Broker, velocidad y cuentas",
                                    color = Color(0xFFB8C6DF),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Button(onClick = { page = AppPage.SETTINGS }) {
                                Text("Configurar")
                            }
                        }
                    }

                    UnknownColorPolicyCard(allowUnknownColors, onSetAllowUnknownColors)

                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF151E30))) {
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
                                    enabled = inventoryLoaded && connected && learning == LearningStep.OFF,
                                    onClick = {
                                        if (isAdmin) onStop(null) else showStopDialog = true
                                    }
                                ) { Text("Detener banda") }
                                Button(
                                    enabled = inventoryLoaded && connected && learning == LearningStep.OFF,
                                    onClick = onStart
                                ) { Text("Iniciar banda") }
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

                    Text(eventMessage, color = Color(0xFF9FCBFF), style = MaterialTheme.typography.bodyMedium)
                }

                AppPage.SETTINGS -> {
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF151E30))) {
                        Column(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("Conexión MQTT (TCP)", style = MaterialTheme.typography.titleMedium, color = Color.White)
                            OutlinedTextField(
                                host,
                                { host = it },
                                Modifier.fillMaxWidth(),
                                label = { Text("IP o nombre del broker") },
                                singleLine = true
                            )
                            OutlinedTextField(
                                port,
                                { port = it.filter(Char::isDigit).take(5) },
                                Modifier.fillMaxWidth(),
                                label = { Text("Puerto TCP") },
                                singleLine = true
                            )
                            OutlinedTextField(
                                mqttUsername,
                                { mqttUsername = it.trim() },
                                Modifier.fillMaxWidth(),
                                label = { Text("Usuario MQTT") },
                                singleLine = true
                            )
                            OutlinedTextField(
                                mqttPassword,
                                { mqttPassword = it },
                                Modifier.fillMaxWidth(),
                                label = { Text("Contraseña MQTT") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation()
                            )
                            Text(
                                "Topics: banda/sensor/color, banda/estado, banda/resultado y banda/comandos",
                                color = Color(0xFF9AA7C2),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text("La contraseña no se guarda en el teléfono.", color = Color(0xFF9AA7C2), style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    enabled = inventoryLoaded && !connected &&
                                        connection != "Conectando..." && connection != "Reconectando..." &&
                                        host.isNotBlank() && (port.toIntOrNull() ?: 0) in 1..65535 &&
                                        mqttUsername.isNotBlank() && mqttPassword.isNotBlank(),
                                    onClick = { onConnect(host.trim(), port, mqttUsername, mqttPassword) }
                                ) {
                                    Text(
                                        connection.takeIf {
                                            it == "Conectando..." || it == "Reconectando..."
                                        } ?: "Conectar"
                                    )
                                }
                                OutlinedButton(
                                    enabled = connection != "Desconectado",
                                    onClick = onDisconnect
                                ) { Text("Desconectar") }
                            }
                            if (!inventoryLoaded) {
                                Text(
                                    "Cargando inventario de Firebase; MQTT se habilitará al terminar.",
                                    color = Color(0xFFFFD27C)
                                )
                            }
                        }
                    }

                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF151E30))) {
                        Column(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("Velocidad de la banda", style = MaterialTheme.typography.titleMedium, color = Color.White)
                            Text(
                                "${pendingSpeed.toInt()}% · se envía al ESP32 como comando MQTT",
                                color = Color(0xFF9AA7C2)
                            )
                            Slider(
                                value = pendingSpeed,
                                onValueChange = { pendingSpeed = it },
                                valueRange = 10f..100f,
                                steps = 8
                            )
                            Button(
                                enabled = connected,
                                onClick = { onApplySpeed(pendingSpeed.toInt()) },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Aplicar velocidad") }
                            Text(
                                "El firmware debe atender {\"accion\":\"velocidad\",\"valor\":N} (porcentaje).",
                                color = Color(0xFF9AA7C2),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    UnknownColorPolicyCard(allowUnknownColors, onSetAllowUnknownColors)

                    if (isAdmin) {
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF151E30))) {
                            Column(
                                Modifier.fillMaxWidth().padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("Cuentas y roles", style = MaterialTheme.typography.titleMedium, color = Color.White)
                                if (firebaseUsers.isEmpty()) {
                                    Text("No hay otras cuentas cargadas.", color = Color(0xFF9AA7C2))
                                }
                                firebaseUsers.forEach { profile ->
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(profile.email, color = Color.White)
                                            Text(profile.uid, color = Color(0xFF9AA7C2), style = MaterialTheme.typography.bodySmall)
                                        }
                                        OutlinedButton(
                                            enabled = profile.uid != user.uid,
                                            onClick = {
                                                onSetUserRole(
                                                    profile.uid,
                                                    if (profile.role == ROLE_ADMIN) ROLE_USER else ROLE_ADMIN
                                                )
                                            }
                                        ) {
                                            Text(if (profile.role == ROLE_ADMIN) "Hacer usuario" else "Hacer admin")
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
                Text(if (allowUnknownColors) "Activar rechazo automático" else "Rechazo automático activado")
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
    mqtt: MqttController?,
    firebase: FirebaseRepository,
    eventMessage: androidx.compose.runtime.MutableState<String>
) {
    try {
        val json = JSONObject(payload)
        val id = json.opt("id") as? Number ?: throw JSONException("La lectura no incluye un id numérico.")
        val reading = SensorReading(id.toLong(), parseRgb(json))
        if (user?.role == ROLE_ADMIN && learning.value == LearningStep.WAITING_FOR_SAMPLE) {
            mqtt?.publishCommand(JSONObject().put("accion", "aceptar").put("id", id))
            mqtt?.publishCommand(JSONObject().put("accion", "detener"))
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
        mqtt?.publishCommand(JSONObject().put("accion", action).put("id", id))
        eventMessage.value = "Caja ${reading.id}: RGB(${reading.rgb.red}, ${reading.rgb.green}, ${reading.rgb.blue}) → $action"
    } catch (error: JSONException) {
        eventMessage.value = "Lectura inválida: ${error.message ?: "JSON incorrecto"}"
    }
}

private fun receiveStatus(
    payload: String,
    status: androidx.compose.runtime.MutableState<ConveyorStatus>,
    eventMessage: androidx.compose.runtime.MutableState<String>
) {
    try {
        val json = JSONObject(payload)
        val speedValue = when (val value = json.opt("velocidad")) {
            is Number -> value.toString()
            is String -> value.takeIf(String::isNotBlank)
            else -> null
        }
        val speedUnit = json.optString("unidadVelocidad")
            .takeUnless { it.isBlank() || it == "null" }
        status.value = ConveyorStatus(
            belt = json.optString("banda", "Sin datos"),
            sensor = json.optString("sensor", "Sin datos"),
            servo = json.optString("servo", "Sin datos"),
            accepted = json.optInt("aceptadas", status.value.accepted),
            rejected = json.optInt("expulsadas", status.value.rejected),
            speed = speedValue?.let { listOfNotNull(it, speedUnit).joinToString(" ") } ?: "Sin datos"
        )
    } catch (error: JSONException) {
        eventMessage.value = "Estado inválido: ${error.message ?: "JSON incorrecto"}"
    }
}

private fun receiveResult(
    payload: String,
    colors: MutableList<InventoryColor>,
    handledResults: MutableSet<Long>,
    firebase: FirebaseRepository,
    session: AppUser?,
    eventMessage: androidx.compose.runtime.MutableState<String>
) {
    try {
        val json = JSONObject(payload)
        val id = json.opt("id") as? Number ?: throw JSONException("El resultado no incluye un id numérico.")
        val rgb = parseRgb(json)
        val result = json.optString("resultado")
        if (result != "aceptada" && result != "expulsada") {
            throw JSONException("Resultado desconocido: $result")
        }
        if (!handledResults.add(id.toLong())) return

        val index = nearestColorIndex(rgb, colors)
        val uid = session?.uid ?: return
        if (index == -1) {
            firebase.recordUnregisteredResult(rgb, result, uid, id.toLong()) { error ->
                eventMessage.value = "La caja no está inventariada y Firebase no guardó su historial: ${error.message}"
            }
            eventMessage.value = "Caja $id $result · color no inventariado"
            return
        }
        val color = colors[index]
        val updated = if (result == "aceptada") {
            color.copy(accepted = color.accepted + 1)
        } else {
            color.copy(rejected = color.rejected + 1)
        }
        colors[index] = updated
        firebase.recordResult(color, result, uid, id.toLong()) {
            eventMessage.value = "La caja quedó contada en pantalla, pero Firebase no guardó el resultado: ${it.message}"
        }
        eventMessage.value = "Caja $id $result · ${color.name}"
    } catch (error: JSONException) {
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

private class MqttController(
    private val onConnection: (String) -> Unit,
    private val onEvent: (String) -> Unit,
    private val onMessage: (String, String) -> Unit
) {
    private var client: MqttAsyncClient? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun connect(host: String, port: Int, username: String, password: String) {
        disconnect()
        postConnection("Conectando...")
        val uri = "tcp://$host:$port"
        try {
            val mqtt = MqttAsyncClient(uri, "android-banda-${UUID.randomUUID()}", MemoryPersistence())
            client = mqtt
            mqtt.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    if (client !== mqtt) return
                    postConnection("Conectado")
                    postEvent(if (reconnect) "Conexión MQTT restablecida." else "Conectado a $serverURI")
                    listOf(COLOR_TOPIC, STATUS_TOPIC, RESULT_TOPIC).forEach { topic ->
                        try {
                            mqtt.subscribe(topic, 0, null, object : IMqttActionListener {
                                override fun onSuccess(asyncActionToken: IMqttToken?) = Unit
                                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                                    if (client === mqtt) postEvent("No se pudo suscribir a $topic: ${exception?.message ?: "error MQTT"}")
                                }
                            })
                        } catch (error: MqttException) {
                            if (client === mqtt) postEvent("No se pudo suscribir a $topic: ${error.message ?: "error MQTT"}")
                        }
                    }
                }

                override fun connectionLost(cause: Throwable?) {
                    if (client === mqtt) {
                        postConnection("Reconectando...")
                        postEvent("Conexión MQTT perdida; intentando reconectar automáticamente.")
                    }
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    if (client === mqtt && topic != null && message != null) {
                        val payload = String(message.payload, Charsets.UTF_8)
                        mainHandler.post { if (client === mqtt) onMessage(topic, payload) }
                    }
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
            })
            val options = MqttConnectOptions().apply {
                userName = username
                this.password = password.toCharArray()
                isAutomaticReconnect = true
                isCleanSession = true
                connectionTimeout = 10
                keepAliveInterval = 30
            }
            mqtt.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    if (client === mqtt) postConnection("Conectado")
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    if (client === mqtt) {
                        postConnection("Desconectado")
                        postEvent("Error al conectar con $uri: ${exception?.message ?: "error MQTT"}")
                    }
                }
            })
        } catch (error: MqttException) {
            client = null
            postConnection("Desconectado")
            postEvent("Error MQTT: ${error.message ?: "no se pudo iniciar la conexión"}")
        } catch (error: IllegalArgumentException) {
            client = null
            postConnection("Desconectado")
            postEvent("Dirección del broker inválida: ${error.message ?: host}")
        }
    }

    fun publishCommand(command: JSONObject) {
        val mqtt = client
        if (mqtt == null || !mqtt.isConnected) {
            postEvent("No se pudo publicar: MQTT no está conectado.")
            return
        }
        try {
            mqtt.publish(COMMAND_TOPIC, MqttMessage(command.toString().toByteArray(Charsets.UTF_8)))
        } catch (error: MqttException) {
            postEvent("Error al publicar en $COMMAND_TOPIC: ${error.message ?: "error MQTT"}")
        }
    }

    fun disconnect() {
        val mqtt = client ?: return
        client = null
        postConnection("Desconectado")
        try {
            if (mqtt.isConnected) {
                mqtt.disconnect(null, object : IMqttActionListener {
                    override fun onSuccess(asyncActionToken: IMqttToken?) = closeClient(mqtt)
                    override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                        closeClient(mqtt)
                        postEvent("Error al desconectar MQTT: ${exception?.message ?: "error MQTT"}")
                    }
                })
            } else closeClient(mqtt)
        } catch (error: MqttException) {
            postEvent("Error al cerrar MQTT: ${error.message ?: "error MQTT"}")
        }
    }

    private fun closeClient(mqtt: MqttAsyncClient) {
        try {
            mqtt.close()
        } catch (error: MqttException) {
            postEvent("Error al cerrar MQTT: ${error.message ?: "error MQTT"}")
        }
    }

    private fun postConnection(value: String) {
        mainHandler.post { onConnection(value) }
    }

    private fun postEvent(value: String) {
        mainHandler.post { onEvent(value) }
    }
}

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
