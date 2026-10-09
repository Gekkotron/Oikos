package com.gekkotron.oikos.feature.home.presentation

import com.gekkotron.oikos.model.StateValue
import kotlin.math.roundToInt

/** Starting values for a group sheet's sliders (room groups spec §4). */
object GroupMath {
    fun meanPosition(group: CardUi): Int = group.members
        .filter { it.capabilities.position }
        .mapNotNull { (it.state as? StateValue.Cover)?.position }
        .takeIf { it.isNotEmpty() }?.average()?.roundToInt() ?: 0

    fun meanBrightness(group: CardUi): Int = group.members
        .filter { it.capabilities.brightness }
        .mapNotNull { (it.state as? StateValue.Light)?.takeIf { l -> l.on }?.brightness }
        .takeIf { it.isNotEmpty() }?.average()?.roundToInt() ?: 255
}
