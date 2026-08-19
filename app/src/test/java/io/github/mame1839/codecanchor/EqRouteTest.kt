package io.github.mame1839.codecanchor

import io.github.mame1839.codecanchor.core.EqDelivery
import io.github.mame1839.codecanchor.core.EqRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EqRouteTest {

    private val a = "AA:BB:CC:DD:EE:01"
    private val b = "AA:BB:CC:DD:EE:02"
    private val c = "AA:BB:CC:DD:EE:03"

    @Test
    fun theOnlyRegisteredOutputOwnsTheSlot() {
        assertEquals(a, EqRoute.owner(outputs = listOf(a), registered = listOf(a)))
    }

    @Test
    fun anUnregisteredOutputIsNotAnOwner() {
        assertNull(EqRoute.owner(outputs = listOf(c), registered = listOf(a, b)))
    }

    @Test
    fun aRegisteredButSilentDeviceIsNotAnOwner() {
        assertNull(EqRoute.owner(outputs = emptyList(), registered = listOf(a, b)))
    }

    @Test
    fun onlyRegisteredOutputsCount() {
        assertEquals(b, EqRoute.owner(outputs = listOf(b, c), registered = listOf(b)))
    }

    @Test
    fun twoRegisteredOutputsHaveNoOwner() {
        assertNull(EqRoute.owner(outputs = listOf(a, b), registered = listOf(a, b)))
    }

    @Test
    fun duplicatesAreOneDevice() {
        assertEquals(a, EqRoute.owner(outputs = listOf(a, a.lowercase()), registered = listOf(a)))
    }

    @Test
    fun theTwoListsAreMatchedAfterNormalising() {
        assertEquals(a, EqRoute.owner(outputs = listOf(a.lowercase()), registered = listOf(a)))
        assertEquals(a, EqRoute.owner(outputs = listOf(" $a "), registered = listOf(a.lowercase())))
        assertEquals(
            EqDelivery.LIVE,
            EqRoute.deliveryOf(a.lowercase(), outputs = listOf(a), registered = listOf(a)),
        )
    }

    @Test
    fun malformedAddressesAreDropped() {
        assertNull(EqRoute.owner(outputs = listOf(""), registered = listOf("")))
        assertNull(EqRoute.owner(outputs = listOf("not a mac"), registered = listOf("not a mac")))
        assertNull(EqRoute.owner(outputs = listOf("", ""), registered = listOf(a)))
    }

    @Test
    fun theOwnerSeesItsSettingsAsLive() {
        assertEquals(EqDelivery.LIVE, EqRoute.deliveryOf(a, listOf(a), listOf(a)))
    }

    @Test
    fun nothingPlayingIsIdle() {
        assertEquals(EqDelivery.IDLE, EqRoute.deliveryOf(a, emptyList(), listOf(a, b)))
        assertEquals(EqDelivery.IDLE, EqRoute.deliveryOf(a, listOf(c), listOf(a)))
    }

    @Test
    fun theOtherEarphonesAreCalledOut() {
        assertEquals(EqDelivery.OTHER, EqRoute.deliveryOf(a, listOf(b), listOf(a, b)))
    }

    @Test
    fun twoRegisteredOutputsAreAmbiguousForEveryone() {
        assertEquals(EqDelivery.AMBIGUOUS, EqRoute.deliveryOf(a, listOf(a, b), listOf(a, b)))
        assertEquals(EqDelivery.AMBIGUOUS, EqRoute.deliveryOf(b, listOf(a, b), listOf(a, b)))
        assertEquals(EqDelivery.AMBIGUOUS, EqRoute.deliveryOf(c, listOf(a, b), listOf(a, b)))
    }

    @Test
    fun theTwoAnswersAlwaysAgree() {
        val everything = listOf(emptyList(), listOf(a), listOf(b), listOf(a, b), listOf(a, b, c))
        for (outputs in everything) {
            for (registered in everything) {
                val owner = EqRoute.owner(outputs, registered)
                for (mac in listOf(a, b, c)) {
                    val delivery = EqRoute.deliveryOf(mac, outputs, registered)
                    val live = delivery == EqDelivery.LIVE
                    assertEquals(
                        "outputs=$outputs registered=$registered mac=$mac -> $delivery / owner=$owner",
                        owner == mac,
                        live,
                    )
                }
            }
        }
    }
}
