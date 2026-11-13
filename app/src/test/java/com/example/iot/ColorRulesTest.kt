package com.example.iot

import org.junit.Assert.assertEquals
import org.junit.Test

class ColorRulesTest {
    private val colors = listOf(
        InventoryColor("Rojo", Rgb(255, 0, 0), allowed = false),
        InventoryColor("Verde", Rgb(0, 255, 0), allowed = true)
    )

    @Test
    fun nearbyBlockedColorIsRejected() {
        assertEquals("expulsar", decisionFor(Rgb(250, 20, 10), colors))
    }

    @Test
    fun nearbyAllowedColorIsAccepted() {
        assertEquals("aceptar", decisionFor(Rgb(10, 248, 4), colors))
    }

    @Test
    fun unknownColorIsAccepted() {
        assertEquals("aceptar", decisionFor(Rgb(0, 0, 255), colors))
    }

    @Test
    fun unknownColorIsRejectedWhenOnlyRegisteredColorsAreAllowed() {
        assertEquals("expulsar", decisionFor(Rgb(0, 0, 255), colors, allowUnknown = false))
    }

    @Test
    fun distanceAtToleranceBoundaryDoesNotMatch() {
        assertEquals(-1, nearestColorIndex(Rgb(165, 0, 0), listOf(colors.first())))
    }
}
