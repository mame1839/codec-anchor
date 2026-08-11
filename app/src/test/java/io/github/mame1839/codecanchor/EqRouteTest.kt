package io.github.mame1839.codecanchor

import io.github.mame1839.codecanchor.core.EqDelivery
import io.github.mame1839.codecanchor.core.EqRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 共有メモリの枠の持ち主を決めるところ。**値を書く前に必ず通る唯一の門。**
 *
 * ここが緩むと、繋がっている別のイヤホンに他人の曲線が掛かる。終了コードもログも正常なので
 * **原因に辿り着く手掛かりが 1 つも残らない**種類の壊れ方になる。
 */
class EqRouteTest {

    private val a = "AA:BB:CC:DD:EE:01"
    private val b = "AA:BB:CC:DD:EE:02"
    private val c = "AA:BB:CC:DD:EE:03"

    // --- 持ち主の決め方 -----------------------------------------------------

    @Test
    fun theOnlyRegisteredOutputOwnsTheSlot() {
        assertEquals(a, EqRoute.owner(outputs = listOf(a), registered = listOf(a)))
    }

    /** 枠を持つのは登録済みの機器だけ。登録していない機器が鳴っていても持ち主にはならない。 */
    @Test
    fun anUnregisteredOutputIsNotAnOwner() {
        assertNull(EqRoute.owner(outputs = listOf(c), registered = listOf(a, b)))
    }

    /** 登録してあっても、鳴っていなければ枠は立たない。 */
    @Test
    fun aRegisteredButSilentDeviceIsNotAnOwner() {
        assertNull(EqRoute.owner(outputs = emptyList(), registered = listOf(a, b)))
    }

    /**
     * **2 台のうち登録が 1 台なら曖昧ではない。**枠が立つのは登録済みのほうだけなので、
     * ここを「出口が 2 つあるから断る」にすると、普通に使える場面で EQ が効かなくなる。
     */
    @Test
    fun onlyRegisteredOutputsCount() {
        assertEquals(b, EqRoute.owner(outputs = listOf(b, c), registered = listOf(b)))
    }

    /** 登録済みが 2 台とも鳴っていたら決められない。**推測で 1 つ選ばない。** */
    @Test
    fun twoRegisteredOutputsHaveNoOwner() {
        assertNull(EqRoute.owner(outputs = listOf(a, b), registered = listOf(a, b)))
    }

    /** 同じ機器が 2 回出てきても 1 台。重複で曖昧に倒れると、繋いだだけで EQ が止まる。 */
    @Test
    fun duplicatesAreOneDevice() {
        assertEquals(a, EqRoute.owner(outputs = listOf(a, a.lowercase()), registered = listOf(a)))
    }

    // --- ⚠️ 書式のずれ -------------------------------------------------------

    /**
     * **交差を取る 2 つは出どころが違う。**登録済みは `EqDevices.normalizeMac` を通った値、
     * 出口は `AudioDeviceInfo.getAddress()` の値。**書式がずれると交差が恒久的に空**になり、
     * 「EQ が永久に届かない」のに例外も終了コードも出ない。
     */
    @Test
    fun theTwoListsAreMatchedAfterNormalising() {
        assertEquals(a, EqRoute.owner(outputs = listOf(a.lowercase()), registered = listOf(a)))
        assertEquals(a, EqRoute.owner(outputs = listOf(" $a "), registered = listOf(a.lowercase())))
        assertEquals(
            EqDelivery.LIVE,
            EqRoute.deliveryOf(a.lowercase(), outputs = listOf(a), registered = listOf(a)),
        )
    }

    /** 形になっていない値は落とす。落とさないと「空文字どうしが一致」で持ち主が決まる。 */
    @Test
    fun malformedAddressesAreDropped() {
        assertNull(EqRoute.owner(outputs = listOf(""), registered = listOf("")))
        assertNull(EqRoute.owner(outputs = listOf("not a mac"), registered = listOf("not a mac")))
        // 権限が無いと address が空で返る。そのとき「出口が無い」= 書かない側に倒れること。
        assertNull(EqRoute.owner(outputs = listOf("", ""), registered = listOf(a)))
    }

    // --- 画面に出す状態 -----------------------------------------------------

    @Test
    fun theOwnerSeesItsSettingsAsLive() {
        assertEquals(EqDelivery.LIVE, EqRoute.deliveryOf(a, listOf(a), listOf(a)))
    }

    /** 何も鳴っていないのは正常。画面には出さない側の値。 */
    @Test
    fun nothingPlayingIsIdle() {
        assertEquals(EqDelivery.IDLE, EqRoute.deliveryOf(a, emptyList(), listOf(a, b)))
        assertEquals(EqDelivery.IDLE, EqRoute.deliveryOf(a, listOf(c), listOf(a)))
    }

    /**
     * **ここが黙ると、ユーザから見た症状が「値を変えたのに音が変わらない」そのものになる。**
     * 未登録の機器を開いているときは [EqDelivery.OTHER] ではなく、登録の理由が先に出る
     * (`EqAvailability.DEVICE_NOT_REGISTERED`) ので、ここでは登録済みの機器だけを見ればよい。
     */
    @Test
    fun theOtherEarphonesAreCalledOut() {
        assertEquals(EqDelivery.OTHER, EqRoute.deliveryOf(a, listOf(b), listOf(a, b)))
    }

    @Test
    fun twoRegisteredOutputsAreAmbiguousForEveryone() {
        assertEquals(EqDelivery.AMBIGUOUS, EqRoute.deliveryOf(a, listOf(a, b), listOf(a, b)))
        assertEquals(EqDelivery.AMBIGUOUS, EqRoute.deliveryOf(b, listOf(a, b), listOf(a, b)))
        // 開いているのが当事者でなくても、断っている事実は変わらない。
        assertEquals(EqDelivery.AMBIGUOUS, EqRoute.deliveryOf(c, listOf(a, b), listOf(a, b)))
    }

    /**
     * **[EqRoute.owner] と [EqRoute.deliveryOf] が食い違わないこと。**持ち主が決まっているのに
     * 画面が「届いていない」と言う (またはその逆) と、どちらが本当か確かめる手段が無くなる。
     */
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
