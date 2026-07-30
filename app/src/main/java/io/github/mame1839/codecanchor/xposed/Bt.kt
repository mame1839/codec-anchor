package io.github.mame1839.codecanchor.xposed

import android.os.SystemClock
import de.robv.android.xposed.XposedHelpers
import io.github.mame1839.codecanchor.core.CodecInfo
import io.github.mame1839.codecanchor.core.CodecKeys
import java.lang.reflect.Array as JavaArray

internal object Bt {
    const val CLASS_CODEC_CONFIG = "android.bluetooth.BluetoothCodecConfig"
    const val CLASS_CODEC_STATUS = "android.bluetooth.BluetoothCodecStatus"

    lateinit var codecConfigClass: Class<*>
        private set
    private var builderClass: Class<*>? = null

    val codecNames = linkedMapOf<Int, String>()

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
                if (value in 0..9999 && !codecNames.containsKey(value)) {
                    codecNames[value] = CodecKeys.prettifyConstant(name)
                }
            }
        }
        CodecKeys.FALLBACK_CODEC_NAMES.forEach { (value, label) ->
            if (!codecNames.containsKey(value)) codecNames[value] = label
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
                    codecNames[codecType] = name
                    return name
                }
            }
        }
        codecNames[codecType]?.let { return it }
        runCatching {
            val name = XposedHelpers.callStaticMethod(codecConfigClass, "getCodecName", codecType) as? String
            if (!name.isNullOrBlank()) {
                codecNames[codecType] = name
                return name
            }
        }
        return "コーデック #$codecType"
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

// 自分が投げた設定かどうかを、オブジェクトの同一性と時間窓で判定する。スタックが別スレッドへ post した
// 先でも成立させるため ThreadLocal は使わない。
internal object Applying {
    @Volatile
    private var config: Any? = null

    @Volatile
    private var force = false

    @Volatile
    private var expiresAt = 0L

    fun begin(target: Any, forceSelectable: Boolean, windowMs: Long = 5_000) {
        config = target
        force = forceSelectable
        expiresAt = SystemClock.uptimeMillis() + windowMs
    }

    fun isOwn(candidate: Any?): Boolean =
        candidate != null && candidate === config && SystemClock.uptimeMillis() <= expiresAt

    fun allowsForce(candidate: Any?): Boolean = force && isOwn(candidate)
}
