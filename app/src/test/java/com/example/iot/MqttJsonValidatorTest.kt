package com.example.iot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MqttJsonValidatorTest {
    @Test
    fun acceptsWaitingStateAndSpeedWithinTheProtocolRange() {
        val parsed = MqttJsonValidator.status(
            """{"banda":"Esperando decision","sensor":"Conectado","servo":90,"aceptadas":2,"expulsadas":1,"velocidad":83.3333,"unidadVelocidad":"pasos/s"}"""
        )

        assertEquals("Esperando decision", parsed.belt)
        assertEquals(83.3333, parsed.speed, 0.0001)
    }

    @Test
    fun rejectsSpeedAboveProtocolRange() {
        assertThrows(IllegalArgumentException::class.java) {
            MqttJsonValidator.status(
                """{"banda":"Detenida","sensor":"Conectado","servo":90,"aceptadas":2,"expulsadas":1,"velocidad":101,"unidadVelocidad":"pasos/s"}"""
            )
        }
    }
}
