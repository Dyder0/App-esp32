package com.example.iot

import org.json.JSONException
import org.json.JSONObject

internal data class ValidatedColorMessage(val id: Long, val rgb: Rgb, val name: String, val timestamp: Long)
internal data class ValidatedResultMessage(val id: Long, val rgb: Rgb, val result: String, val timestamp: Long)
internal data class ValidatedStatusMessage(
    val belt: String,
    val sensor: String,
    val servo: Int,
    val accepted: Int,
    val rejected: Int,
    val speed: Double,
    val speedUnit: String
)

internal object MqttJsonValidator {
    fun color(payload: String): ValidatedColorMessage {
        val json = JSONObject(payload)
        val id = json.requiredLong("id").also { require(it > 0) { "id debe ser positivo" } }
        val rgb = rgb(json)
        val name = json.requiredString("nombre")
        require(name in setOf("ROJO", "VERDE", "AZUL")) { "nombre no pertenece al protocolo" }
        val timestamp = json.requiredLong("ts").also { require(it >= 0) { "ts no puede ser negativo" } }
        return ValidatedColorMessage(id, rgb, name, timestamp)
    }

    fun status(payload: String): ValidatedStatusMessage {
        val json = JSONObject(payload)
        val belt = json.requiredString("banda")
        require(belt in setOf("En marcha", "Detenida", "Esperando decision")) {
            "banda contiene un estado no reconocido"
        }
        val sensor = json.requiredString("sensor")
        val servo = json.requiredLong("servo").also { require(it in 0..180) { "servo debe estar entre 0 y 180" } }
        val accepted = json.requiredLong("aceptadas").also {
            require(it in 0..Int.MAX_VALUE.toLong()) { "aceptadas debe ser un entero no negativo" }
        }
        val rejected = json.requiredLong("expulsadas").also {
            require(it in 0..Int.MAX_VALUE.toLong()) { "expulsadas debe ser un entero no negativo" }
        }
        val speed = json.requiredNumber("velocidad").toDouble()
        require(speed.isFinite() && speed in 10.0..100.0) {
            "velocidad debe estar entre 10 y 100 pasos/s"
        }
        val unit = json.requiredString("unidadVelocidad")
        require(unit == "pasos/s") { "unidadVelocidad debe ser pasos/s" }
        return ValidatedStatusMessage(belt, sensor, servo.toInt(), accepted.toInt(), rejected.toInt(), speed, unit)
    }

    fun result(payload: String): ValidatedResultMessage {
        val json = JSONObject(payload)
        val id = json.requiredLong("id").also { require(it > 0) { "id debe ser positivo" } }
        val rgb = rgb(json)
        val result = json.requiredString("resultado")
        require(result == "aceptada" || result == "expulsada") { "resultado no pertenece al protocolo" }
        val timestamp = json.requiredLong("ts").also { require(it >= 0) { "ts no puede ser negativo" } }
        return ValidatedResultMessage(id, rgb, result, timestamp)
    }

    private fun rgb(json: JSONObject): Rgb {
        val red = json.requiredLong("r")
        val green = json.requiredLong("g")
        val blue = json.requiredLong("b")
        require(red in 0..255 && green in 0..255 && blue in 0..255) {
            "r, g y b deben estar entre 0 y 255"
        }
        return Rgb(red.toInt(), green.toInt(), blue.toInt())
    }

    private fun JSONObject.requiredString(name: String): String {
        val value = opt(name)
        if (value !is String || value.isBlank()) throw JSONException("$name debe ser texto no vacío")
        return value
    }

    private fun JSONObject.requiredLong(name: String): Long {
        val value = opt(name)
        return when (value) {
            is Byte, is Short, is Int, is Long -> (value as Number).toLong()
            else -> throw JSONException("$name debe ser un entero JSON")
        }
    }

    private fun JSONObject.requiredNumber(name: String): Number {
        val value = opt(name)
        if (value !is Number) throw JSONException("$name debe ser numérico")
        return value
    }
}
