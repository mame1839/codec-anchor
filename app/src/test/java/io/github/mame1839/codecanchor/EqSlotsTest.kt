package io.github.mame1839.codecanchor

import io.github.mame1839.codecanchor.core.DeviceProfile
import io.github.mame1839.codecanchor.core.DeviceSlots
import io.github.mame1839.codecanchor.core.EqBand
import io.github.mame1839.codecanchor.core.EqMode
import io.github.mame1839.codecanchor.core.EqSettings
import io.github.mame1839.codecanchor.core.EqSlot
import io.github.mame1839.codecanchor.core.EqSlotBook
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * スロット台帳 (`llmdocs/eq-slot-design.md` §2) のモデル。
 *
 * 見張っている境界は 2 つ。**和解は必ず profile.eq → 選択中スロットの向きに写す** (逆に写すと
 * 起動時に鳴っている音が変わる) と、**パラメトリックは全ゲイン 0 でも中立にしない** (置いた
 * fc / Q は作業中の情報で、フラットに倒すと台帳から消える)。
 */
@RunWith(RobolectricTestRunner::class)
class EqSlotsTest {

    private val macA = "AA:BB:CC:DD:EE:01"
    private val macB = "AA:BB:CC:DD:EE:02"

    private val curveA = EqSettings(
        enabled = true,
        bands = listOf(EqBand(freqHz = 1_000, q100 = 141, gainDb10 = 30)),
    )
    private val curveB = EqSettings(
        enabled = true,
        mode = EqMode.PARAMETRIC,
        bands = listOf(EqBand(freqHz = 105, q100 = 71, gainDb10 = -25)),
        preampDb10 = -60,
    )

    // ---- 保存の往復 ----

    @Test
    fun bookRoundTrips() {
        val book = EqSlotBook(
            mapOf(
                // 移行済みの印だけが残る機器 (フラット選択・スロット無し)
                macA to DeviceSlots(),
                // 名前あり / 未命名 (空) の混在と、2 本目を選択中
                macB to DeviceSlots(
                    active = "2",
                    slots = listOf(EqSlot("1", "バスブースト", curveA), EqSlot("2", "", curveB)),
                ),
            ),
        )
        assertEquals(book, EqSlotBook.decode(book.encode()))
    }

    @Test
    fun brokenStorageFallsBackToEmpty() {
        assertEquals(EqSlotBook(), EqSlotBook.decode(null))
        assertEquals(EqSlotBook(), EqSlotBook.decode(""))
        assertEquals(EqSlotBook(), EqSlotBook.decode("{"))
        assertEquals(EqSlotBook(), EqSlotBook.decode("""{"other":1}"""))
    }

    // 将来の版が形を変えたら、古いアプリは読まずに空へ倒す。台帳は profile.eq から作り直せる
    // (音の実体はこのファイルに無い) ので、壊れた形を無理に読むより安全側。
    @Test
    fun unknownVersionIsRejected() {
        val book = EqSlotBook(mapOf(macA to DeviceSlots(active = "1", slots = listOf(EqSlot("1", "x", curveA)))))
        val next = JSONObject(book.encode()).put("v", 2).toString()
        assertEquals(EqSlotBook(), EqSlotBook.decode(next))
    }

    // id の無いスロットは選択できず、eq の無いスロットは切り替えた瞬間に音を黙って変える。
    // どちらも読み込みで捨て、宙に浮いた active は和解に立て直させる。
    @Test
    fun slotsMissingIdOrEqAreDropped() {
        val json = """
            {"v":1,"devices":{"$macA":{"active":"2","slots":[
                {"name":"id なし","eq":${curveA.toJson()}},
                {"id":"2","name":"eq なし"},
                {"id":"3","name":"生きている","eq":${curveB.toJson()}}
            ]}}}
        """.trimIndent()
        val slots = EqSlotBook.decode(json).devices[macA]!!.slots
        assertEquals(listOf("3"), slots.map { it.id })
    }

    // ---- 中立の判定 (移行がフラットへ倒してよい曲線) ----

    @Test
    fun untouchedSettingsAreNeutral() {
        assertTrue(EqSlotBook.isNeutral(EqSettings()))
    }

    // バンド数や有効オフは音を変えない。ゲイン 0 のグラフィックはバンドの位置も固定なので
    // 情報を失わない。プリアンプも 0 dB なら鳴りはフラットと同じ。
    @Test
    fun zeroGainGraphicIsNeutralRegardlessOfShape() {
        val zeroBands = listOf(EqBand(freqHz = 31, q100 = 50, gainDb10 = 0), EqBand(freqHz = 16_000, q100 = 50, gainDb10 = 0))
        assertTrue(EqSlotBook.isNeutral(EqSettings(enabled = true, bandCount = 31, bands = zeroBands)))
        assertTrue(EqSlotBook.isNeutral(EqSettings(enabled = false, bands = zeroBands)))
        assertTrue(EqSlotBook.isNeutral(EqSettings(preampDb10 = 0)))
    }

    @Test
    fun audibleSettingsAreNotNeutral() {
        // ゲインが立っている
        assertFalse(EqSlotBook.isNeutral(curveA))
        // プリアンプが 0 dB 以外
        assertFalse(EqSlotBook.isNeutral(EqSettings(preampDb10 = -30)))
    }

    // 仕様 §2 の名指しの罠: パラメトリックで fc / Q だけ置いた「作業中」の全ゲイン 0 を
    // フラット扱いすると、次の和解や切り替えでその配置が消える。必ずスロット化する。
    @Test
    fun zeroGainParametricIsNotNeutral() {
        val wip = EqSettings(
            enabled = true,
            mode = EqMode.PARAMETRIC,
            bands = listOf(EqBand(freqHz = 105, q100 = 71, gainDb10 = 0), EqBand(freqHz = 2_500, q100 = 300, gainDb10 = 0)),
        )
        assertFalse(EqSlotBook.isNeutral(wip))
    }

    // ---- 和解 (移行・write-through・立て直しが同じ規則を通る) ----

    @Test
    fun migrationSendsNeutralDevicesToFlat() {
        val book = EqSlotBook().reconciledWith(macA, EqSettings())
        // エントリは書く (移行済みの印)。スロットは作らない。
        assertEquals(DeviceSlots(), book.devices[macA])
    }

    @Test
    fun migrationSeedsACustomSlotForAudibleSettings() {
        val entry = EqSlotBook().reconciledWith(macA, curveB).devices[macA]!!
        val slot = entry.activeSlot()!!
        assertEquals(curveB, slot.eq)
        // 既定名は表示側 (段 2) の文言なので、データは未命名 (空) のまま。
        assertEquals("", slot.name)
        assertEquals(1, entry.slots.size)
    }

    @Test
    fun reconcileCopiesProfileEqIntoTheActiveSlot() {
        val before = EqSlotBook(
            mapOf(
                macA to DeviceSlots(
                    active = "2",
                    slots = listOf(EqSlot("1", "残す", curveA), EqSlot("2", "選択中", curveB)),
                ),
            ),
        )
        val entry = before.reconciledWith(macA, curveA).devices[macA]!!
        // 向きは profile.eq → スロット。名前と id、選択外のスロットは保つ。
        assertEquals(EqSlot("2", "選択中", curveA), entry.activeSlot())
        assertEquals(EqSlot("1", "残す", curveA), entry.slots[0])
        assertEquals(2, entry.slots.size)
    }

    @Test
    fun reconcileLeavesAMatchingBookUnchanged() {
        val book = EqSlotBook(mapOf(macA to DeviceSlots(active = "1", slots = listOf(EqSlot("1", "x", curveA)))))
        assertEquals(book, book.reconciledWith(macA, curveA))
    }

    // 段 1 の既存 UI はフラット選択中でも編集できる。その編集は既存スロットを汚さず
    // 新しいスロットに着地する (「外から来る曲線は必ず新しいスロットへ」と同じ側)。
    @Test
    fun editingWhileFlatLandsInANewSlot() {
        val before = EqSlotBook(
            mapOf(macA to DeviceSlots(active = EqSlotBook.FLAT_ID, slots = listOf(EqSlot("1", "残す", curveA)))),
        )
        val entry = before.reconciledWith(macA, curveB).devices[macA]!!
        assertEquals(EqSlot("1", "残す", curveA), entry.slots[0])
        assertEquals(curveB, entry.activeSlot()!!.eq)
        assertEquals(2, entry.slots.size)
    }

    // 壊れた保存 (active の指す先が無い) は中立ならフラットへ、鳴っているなら新スロットへ。
    // どちらでも既存スロットと profile.eq (音) は失わない。
    @Test
    fun danglingActiveIsRebuilt() {
        val orphaned = EqSlotBook(
            mapOf(macA to DeviceSlots(active = "9", slots = listOf(EqSlot("1", "残す", curveA)))),
        )
        val flat = orphaned.reconciledWith(macA, EqSettings()).devices[macA]!!
        assertEquals(EqSlotBook.FLAT_ID, flat.active)
        assertEquals(1, flat.slots.size)

        val seeded = orphaned.reconciledWith(macA, curveB).devices[macA]!!
        assertEquals(curveB, seeded.activeSlot()!!.eq)
        assertEquals(2, seeded.slots.size)
    }

    // 種付けの id は既存と衝突しない (消した番号を再利用して古い active が別物を指すのも防ぐ)。
    @Test
    fun seededIdsDoNotCollide() {
        val before = EqSlotBook(
            mapOf(
                macA to DeviceSlots(
                    active = EqSlotBook.FLAT_ID,
                    slots = listOf(EqSlot("1", "", curveA), EqSlot("3", "", curveA)),
                ),
            ),
        )
        val entry = before.reconciledWith(macA, curveB).devices[macA]!!
        assertEquals("4", entry.active)
        assertEquals(3, entry.slots.size)
    }

    @Test
    fun startupReconcileMigratesEveryProfileAndDropsOrphans() {
        val before = EqSlotBook(
            mapOf(
                macA to DeviceSlots(active = "1", slots = listOf(EqSlot("1", "ずれている", curveA))),
                // プロファイルの無い機器 (removeProfile の取り逃し) は消える
                macB to DeviceSlots(active = "1", slots = listOf(EqSlot("1", "孤児", curveA))),
            ),
        )
        val profiles = mapOf(
            macA to DeviceProfile(mac = macA, eq = curveB),
            // 台帳の無い機器は移行される
            "AA:BB:CC:DD:EE:03" to DeviceProfile(mac = "AA:BB:CC:DD:EE:03", eq = curveB),
        )
        val after = before.reconciled(profiles)
        assertEquals(setOf(macA, "AA:BB:CC:DD:EE:03"), after.devices.keys)
        // 食い違いは profile.eq (鳴っている側) が勝つ
        assertEquals(curveB, after.devices[macA]!!.activeSlot()!!.eq)
        assertEquals("ずれている", after.devices[macA]!!.activeSlot()!!.name)
        assertEquals(curveB, after.devices["AA:BB:CC:DD:EE:03"]!!.activeSlot()!!.eq)
        assertNull(after.devices[macB])
    }
}
