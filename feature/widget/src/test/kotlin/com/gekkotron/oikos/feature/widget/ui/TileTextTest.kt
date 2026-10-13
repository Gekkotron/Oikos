package com.gekkotron.oikos.feature.widget.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.feature.widget.model.TileValue
import com.gekkotron.oikos.model.AlarmState
import com.gekkotron.oikos.model.CoverState
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class TileTextTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun measure_uses_one_decimal_and_unit() = assertEquals("21.4 °C", TileText.of(context, TileValue.Measure(21.44, "°C"), Locale.US))

    @Test
    fun french_decimal_comma() = assertEquals("21,4 °C", TileText.of(context, TileValue.Measure(21.44, "°C"), Locale.FRANCE))

    @Test
    fun none_is_dash() = assertEquals("—", TileText.of(context, TileValue.None, Locale.US))

    @Test
    fun light_percent_when_on_with_brightness() = assertEquals("50 %", TileText.of(context, TileValue.Light(true, 50), Locale.US))

    @Test
    fun light_off_is_off() = assertEquals("Off", TileText.of(context, TileValue.Light(false, 50), Locale.US))

    @Test
    fun cover_partial() = assertEquals("Open 60 %", TileText.of(context, TileValue.Cover(CoverState.OPEN, 60), Locale.US))

    @Test
    fun cover_closed() = assertEquals("Closed", TileText.of(context, TileValue.Cover(CoverState.CLOSED, 0), Locale.US))

    @Test
    fun climate_shows_setpoint() = assertEquals("21°", TileText.of(context, TileValue.Climate("heat", 20.5, 21.0), Locale.US))

    @Test
    fun climate_without_setpoint_shows_current_temperature() =
        assertEquals("20.5°", TileText.of(context, TileValue.Climate("heat", 20.5, null), Locale.US))

    @Test
    fun door_contact_reads_open() = assertEquals("Open", TileText.of(context, TileValue.Contact("door", true), Locale.US))

    @Test
    fun motion_contact_reads_detected() = assertEquals("Detected", TileText.of(context, TileValue.Contact("motion", true), Locale.US))

    @Test
    fun alarm_uses_the_shared_label() = assertEquals("Disarmed", TileText.of(context, TileValue.Alarm(AlarmState.DISARMED), Locale.US))

    @Config(qualifiers = "fr")
    @Test
    fun french_on() = assertEquals("Allumé", TileText.of(context, TileValue.OnOff(true), Locale.FRANCE))
}
