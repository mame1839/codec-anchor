package io.github.mame1839.codecanchor.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * イヤホンごとの EQ スロット (切り替えられる作業台) の 1 枠。仕様は `llmdocs/eq-slot-design.md`。
 *
 * スロットは AppConfig / DeviceProfile に載せない (EqPresets / EqDeviceStore に続く 3 例目)。
 * フックが要るのは「いま鳴っている EQ」だけで、それは profile.eq が担う。DeviceProfile に足すと、
 * 古いフックが載ったままの Bluetooth プロセスが未知キーを落として hash が食い違う窓が開く。
 *
 * [id] と [name] を分けるのは改名を安全にするため。[name] が空 = 未命名で、既定名 (「カスタム n」等)
 * は表示側の文言なのでデータには焼かない (端末の言語を替えるとデータが嘘になる)。
 */
data class EqSlot(val id: String, val name: String, val eq: EqSettings) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("eq", eq.toJson())
    }

    companion object {
        fun fromJson(o: JSONObject): EqSlot? {
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
            // eq 欠けは既定値に化けさせず捨てる。空の EqSettings を持ったスロットに切り替えると
            // 黙って音が変わる。active が宙に浮いたら和解が profile.eq から立て直す。
            val eq = o.optJSONObject("eq") ?: return null
            return EqSlot(id = id, name = o.optString("name"), eq = EqSettings.fromJson(eq))
        }
    }
}

/** 1 台分。[active] は選択中スロットの id。フラット (読み取り専用・保存しない) は [EqSlotBook.FLAT_ID]。 */
data class DeviceSlots(
    val active: String = EqSlotBook.FLAT_ID,
    val slots: List<EqSlot> = emptyList(),
) {
    fun activeSlot(): EqSlot? = slots.firstOrNull { it.id == active }

    fun slot(id: String): EqSlot? = slots.firstOrNull { it.id == id }

    /**
     * [eq] を種にした新しいスロットを足して選択する。**新しい曲線の着地はここ 1 本**
     * (移行・プリセットの適用・AutoEQ の取り込み・「+」が全部通る)。
     *
     * id は数字の連番で、消しても振り直さない — 表示の既定名 (「カスタム n」) がこの番号なので、
     * 振り直すと**別のスロットの名前が勝手に変わる。**[EqSlotBook.FLAT_ID] は数字でないので衝突しない。
     */
    fun withNewSlot(eq: EqSettings, name: String = ""): DeviceSlots {
        val id = ((slots.mapNotNull { it.id.toIntOrNull() }.maxOrNull() ?: 0) + 1).toString()
        return copy(active = id, slots = slots + EqSlot(id = id, name = name, eq = eq))
    }

    fun renamed(id: String, name: String): DeviceSlots =
        copy(slots = slots.map { if (it.id == id) it.copy(name = name) else it })

    /** 消す。**選択中を消したらフラットへ戻す** — 別のスロットへ倒すと、選んでいない曲線が鳴り出す。 */
    fun without(id: String): DeviceSlots = copy(
        active = if (active == id) EqSlotBook.FLAT_ID else active,
        slots = slots.filterNot { it.id == id },
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("active", active)
        put("slots", JSONArray().also { a -> slots.forEach { a.put(it.toJson()) } })
    }

    companion object {
        fun fromJson(o: JSONObject): DeviceSlots = DeviceSlots(
            active = o.optString("active").ifEmpty { EqSlotBook.FLAT_ID },
            slots = o.optJSONArray("slots")?.let { a ->
                (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { EqSlot.fromJson(it) } }
            }.orEmpty().distinctBy { it.id },
        )
    }
}

data class EqSlotBook(val devices: Map<String, DeviceSlots> = emptyMap()) {

    fun without(mac: String): EqSlotBook = copy(devices = devices - mac.uppercase())

    fun of(mac: String): DeviceSlots = devices[mac.uppercase()] ?: DeviceSlots()

    /**
     * [mac] の台帳を差し替える。**呼ぶ側の順番に規則がある** — スロットの選択を動かす操作は
     * **ここを先に通してから `updateEq`** を呼ぶこと。逆順だと write-through ([reconciledWith])
     * が新しい曲線を「まだ選択中の古いスロット」へ写して上書きする。
     */
    fun mapDevice(mac: String, transform: (DeviceSlots) -> DeviceSlots): EqSlotBook {
        val key = mac.uppercase()
        return copy(devices = devices + (key to transform(devices[key] ?: DeviceSlots())))
    }

    /**
     * [mac] の台帳を「いま鳴っている音」([eq] = profile.eq) と辻褄の合う形にする。
     * updateEq の write-through と起動時の和解が両方ここを通る — 写す向きの規則を 2 箇所に持たない。
     *
     * - 選択中がカスタム: 中身へ [eq] を写す。**逆向き (スロット → profile.eq) は作らない** —
     *   起動時に鳴っている音が変わってしまう
     * - 選択中がフラット (エントリの無い機器も同じ): [eq] が中立ならそのまま。中立でなければ
     *   新しいスロットへ [eq] を種付けして選択する (既存スロットは保持)。初回はこれが移行になる
     * - active の指す id が無い (壊れた保存): 上と同じ規則で立て直す
     *
     * ⚠️ スロット切り替え (段 2) は **active を動かしてから updateEq を呼ぶ**こと。updateEq が
     * 先だと、write-through が新しい曲線を「まだ選択中の古いスロット」へ写して上書きする。
     */
    fun reconciledWith(mac: String, eq: EqSettings): EqSlotBook {
        val key = mac.uppercase()
        val entry = devices[key] ?: DeviceSlots()
        val next = reconcile(entry, eq)
        if (next == entry && key in devices) return this
        return copy(devices = devices + (key to next))
    }

    /**
     * 起動時 (と設定の復元直後) の全機器ぶんの和解。プロファイルの無い MAC の台帳も消す
     * (removeProfile を取り逃した孤児の防止)。
     *
     * **写すだけ。**profile.eq には触らず updateEq / push も通らないので、これが音を変えることはない。
     */
    fun reconciled(profiles: Map<String, DeviceProfile>): EqSlotBook {
        var book = copy(devices = devices.filterKeys { it in profiles })
        profiles.forEach { (mac, p) -> book = book.reconciledWith(mac, p.eq) }
        return book
    }

    private fun reconcile(entry: DeviceSlots, eq: EqSettings): DeviceSlots {
        val active = entry.activeSlot()
        return when {
            active != null ->
                if (active.eq == eq) entry
                else entry.copy(slots = entry.slots.map { if (it.id == entry.active) it.copy(eq = eq) else it })
            isNeutral(eq) -> if (entry.active == FLAT_ID) entry else entry.copy(active = FLAT_ID)
            else -> entry.withNewSlot(eq)
        }
    }

    fun encode(): String = JSONObject().apply {
        put("v", VERSION)
        put("devices", JSONObject().also { obj ->
            devices.forEach { (mac, d) -> obj.put(mac, d.toJson()) }
        })
    }.toString()

    companion object {
        /**
         * フラット = 暗黙のスロット。実体を保存しないので id だけを予約する
         * ([DeviceSlots.withNewSlot] が振る id は数字なので衝突しない)。
         */
        const val FLAT_ID = "flat"

        private const val VERSION = 1

        /**
         * 音を変えない曲線か (移行で active=flat にしてよいか)。
         *
         * GRAPHIC・全バンド 0・プリアンプ 0 dB (自動は全 0 なら 0 を返す: [EqSolver.autoPreampDb10])。
         * enabled は見ない — オンオフは主電源で、曲線の中立とは別の層。
         * パラメトリックは全ゲイン 0 でも中立にしない — 置いた fc / Q が作業中の情報で、
         * フラット (実体を保存しない) に倒すと台帳から消える。
         */
        fun isNeutral(eq: EqSettings): Boolean =
            eq.mode == EqMode.GRAPHIC &&
                eq.bands.all { it.gainDb10 == 0 } &&
                (eq.preampAuto || eq.preampDb10 == 0)

        fun decode(json: String?): EqSlotBook {
            if (json.isNullOrBlank()) return EqSlotBook()
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return EqSlotBook()
            if (o.optInt("v", 0) != VERSION) return EqSlotBook()
            val devicesJson = o.optJSONObject("devices") ?: return EqSlotBook()
            val devices = mutableMapOf<String, DeviceSlots>()
            devicesJson.keys().forEach { mac ->
                devicesJson.optJSONObject(mac)?.let { devices[mac.uppercase()] = DeviceSlots.fromJson(it) }
            }
            return EqSlotBook(devices)
        }
    }
}
