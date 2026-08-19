package io.github.mame1839.codecanchor.xposed

import android.os.SystemClock
import de.robv.android.xposed.XposedHelpers
import io.github.mame1839.codecanchor.core.CodecInfo
import io.github.mame1839.codecanchor.core.CodecKeys
import java.util.concurrent.CopyOnWriteArrayList
import java.lang.reflect.Array as JavaArray

internal object Bt {
    const val CLASS_CODEC_CONFIG = "android.bluetooth.BluetoothCodecConfig"
    const val CLASS_CODEC_STATUS = "android.bluetooth.BluetoothCodecStatus"

    lateinit var codecConfigClass: Class<*>
        private set
    private var builderClass: Class<*>? = null

    private val names = linkedMapOf<Int, String>()
    private val nameLock = Any()

    val codecNames: Map<Int, String>
        get() = synchronized(nameLock) { LinkedHashMap(names) }

    private fun putName(codecType: Int, name: String) {
        synchronized(nameLock) { names[codecType] = name }
    }

    private fun nameOf(codecType: Int): String? = synchronized(nameLock) { names[codecType] }

    private fun hasName(codecType: Int): Boolean = synchronized(nameLock) { names.containsKey(codecType) }

    fun init(classLoader: ClassLoader) {
        codecConfigClass = XposedHelpers.findClass(CLASS_CODEC_CONFIG, classLoader)
        builderClass = XposedHelpers.findClassIfExists("$CLASS_CODEC_CONFIG\$Builder", classLoader)
        loadCodecNames()
    }

    private fun loadCodecNames() {
        codecConfigClass.declaredFields.forEach { field ->
            val name = field.name
            if (!name.startsWith("SOURCE_CODEC_TYPE_")) return@forEach
            if (name == "SOURCE_CODEC_TYPE_MAX" || name == "SOURCE_CODEC_TYPE_INVALID") return@forEach
            if (field.type != Int::class.javaPrimitiveType) return@forEach
            runCatching {
                field.isAccessible = true
                val value = field.getInt(null)
                if (value in 0..9999 && !hasName(value)) {
                    putName(value, CodecKeys.prettifyConstant(name))
                }
            }
        }
        CodecKeys.FALLBACK_CODEC_NAMES.forEach { (value, label) ->
            if (!hasName(value)) putName(value, label)
        }
        XLog.i("この端末のコーデック: $codecNames")
    }

    fun buildCodecConfig(
        codecType: Int,
        sampleRate: Int,
        bitsPerSample: Int,
        channelMode: Int,
        codecSpecific1: Long,
        codecSpecific2: Long,
        codecSpecific3: Long,
        codecSpecific4: Long,
        priority: Int = CodecKeys.CODEC_PRIORITY_HIGHEST,
    ): Any? {
        builderClass?.let { builder ->
            val built = runCatching {
                val instance = builder.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
                XposedHelpers.callMethod(instance, "setCodecType", codecType)
                XposedHelpers.callMethod(instance, "setCodecPriority", priority)
                XposedHelpers.callMethod(instance, "setSampleRate", sampleRate)
                XposedHelpers.callMethod(instance, "setBitsPerSample", bitsPerSample)
                XposedHelpers.callMethod(instance, "setChannelMode", channelMode)
                XposedHelpers.callMethod(instance, "setCodecSpecific1", codecSpecific1)
                XposedHelpers.callMethod(instance, "setCodecSpecific2", codecSpecific2)
                XposedHelpers.callMethod(instance, "setCodecSpecific3", codecSpecific3)
                XposedHelpers.callMethod(instance, "setCodecSpecific4", codecSpecific4)
                XposedHelpers.callMethod(instance, "build")
            }
            built.onFailure { XLog.d("Builder による生成に失敗したので旧コンストラクタを使う: ${it.message}") }
            built.getOrNull()?.let { return it }
        }
        return runCatching {
            XposedHelpers.newInstance(
                codecConfigClass,
                codecType, priority, sampleRate, bitsPerSample, channelMode,
                codecSpecific1, codecSpecific2, codecSpecific3, codecSpecific4,
            )
        }.onFailure { XLog.e("BluetoothCodecConfig を作れない", it) }.getOrNull()
    }

    fun codecInfo(config: Any?): CodecInfo? {
        if (config == null) return null
        return runCatching {
            val type = XposedHelpers.callMethod(config, "getCodecType") as Int
            CodecInfo(
                codecType = type,
                codecName = codecName(config, type),
                sampleRate = XposedHelpers.callMethod(config, "getSampleRate") as Int,
                bitsPerSample = XposedHelpers.callMethod(config, "getBitsPerSample") as Int,
                channelMode = XposedHelpers.callMethod(config, "getChannelMode") as Int,
                codecSpecific1 = XposedHelpers.callMethod(config, "getCodecSpecific1") as Long,
            )
        }.getOrNull()
    }

    fun codecName(config: Any?, codecType: Int): String {
        if (config != null) {
            runCatching {
                val extended = XposedHelpers.callMethod(config, "getExtendedCodecType")
                val name = extended?.let { XposedHelpers.callMethod(it, "getCodecName") as? String }
                if (!name.isNullOrBlank()) {
                    putName(codecType, name)
                    return name
                }
            }
        }
        nameOf(codecType)?.let { return it }
        runCatching {
            val name = XposedHelpers.callStaticMethod(codecConfigClass, "getCodecName", codecType) as? String
            if (!name.isNullOrBlank()) {
                putName(codecType, name)
                return name
            }
        }
        return ""
    }

    fun currentConfig(codecStatus: Any?): Any? =
        codecStatus?.let { runCatching { XposedHelpers.callMethod(it, "getCodecConfig") }.getOrNull() }

    fun selectableCapabilities(codecStatus: Any?): List<Any> =
        configList(codecStatus, "getCodecsSelectableCapabilities")

    fun localCapabilities(codecStatus: Any?): List<Any> =
        configList(codecStatus, "getCodecsLocalCapabilities")

    private fun configList(codecStatus: Any?, method: String): List<Any> =
        codecStatus?.let {
            runCatching { (XposedHelpers.callMethod(it, method) as? List<*>)?.filterNotNull() }.getOrNull()
        } ?: emptyList()

    fun codecConfigArray(vararg configs: Any): Any {
        val array = JavaArray.newInstance(codecConfigClass, configs.size)
        configs.forEachIndexed { index, config -> JavaArray.set(array, index, config) }
        return array
    }

    fun codecTypeOf(config: Any?): Int? =
        config?.let { runCatching { XposedHelpers.callMethod(it, "getCodecType") as Int }.getOrNull() }
}

internal object Applying {
    private class Entry(val config: Any, val force: Boolean, val expiresAt: Long)

    private val entries = CopyOnWriteArrayList<Entry>()

    fun begin(target: Any, forceSelectable: Boolean, windowMs: Long = 5_000) {
        val now = SystemClock.uptimeMillis()
        entries.removeAll(entries.filter { now > it.expiresAt })
        entries.add(Entry(target, forceSelectable, now + windowMs))
    }

    fun isOwn(candidate: Any?): Boolean = find(candidate) != null

    fun allowsForce(candidate: Any?): Boolean = find(candidate)?.force == true

    private fun find(candidate: Any?): Entry? {
        if (candidate == null) return null
        val now = SystemClock.uptimeMillis()
        return entries.firstOrNull { it.config === candidate && now <= it.expiresAt }
    }
}
