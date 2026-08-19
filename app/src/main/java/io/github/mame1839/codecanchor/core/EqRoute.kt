package io.github.mame1839.codecanchor.core

enum class EqDelivery {
    LIVE,

    IDLE,

    OTHER,

    AMBIGUOUS,
}

object EqRoute {

    fun owner(outputs: Collection<String>, registered: Collection<String>): String? =
        candidates(outputs, registered).singleOrNull()

    fun deliveryOf(
        mac: String,
        outputs: Collection<String>,
        registered: Collection<String>,
    ): EqDelivery {
        val found = candidates(outputs, registered)
        return when {
            found.size > 1 -> EqDelivery.AMBIGUOUS
            found.isEmpty() -> EqDelivery.IDLE
            found.single() == EqDevices.normalizeMac(mac) -> EqDelivery.LIVE
            else -> EqDelivery.OTHER
        }
    }

    private fun candidates(outputs: Collection<String>, registered: Collection<String>): List<String> {
        val known = registered.mapNotNullTo(mutableSetOf(), EqDevices::normalizeMac)
        return outputs.mapNotNull(EqDevices::normalizeMac).distinct().filter { it in known }
    }
}
