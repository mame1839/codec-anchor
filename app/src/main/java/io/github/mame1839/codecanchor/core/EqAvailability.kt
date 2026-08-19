package io.github.mame1839.codecanchor.core

import android.media.audiofx.AudioEffect
import java.util.UUID

enum class EqAvailability {
    OK,

    EFFECT_NOT_REGISTERED,

    OFFLOAD_ENABLED,

    DEVICE_NOT_REGISTERED,

    HOOK_TOO_OLD,

    ;

    val allowsEditing: Boolean
        get() = this == OK || this == DEVICE_NOT_REGISTERED
}

object EqSupport {
    val IMPL_UUID: UUID = UUID.fromString("7a1c9f60-4a2e-4f6b-9d21-0a5c1b3e77d1")

    const val SCHEMA = 3

    fun effectRegistered(): Boolean = runCatching {
        AudioEffect.queryEffects()?.any { it.uuid == IMPL_UUID } == true
    }.getOrDefault(false)
}
