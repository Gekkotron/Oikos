package com.gekkotron.oikos.feature.widget.model

import com.gekkotron.oikos.model.AlarmMode
import com.gekkotron.oikos.model.AlarmState
import com.gekkotron.oikos.model.CodeFormat
import com.gekkotron.oikos.model.CoverState
import com.gekkotron.oikos.model.Kind
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What a tile shows; rendered to text at draw time so the locale is never baked into storage. */
@Serializable
sealed interface TileValue {
    @Serializable @SerialName("none")
    data object None : TileValue

    @Serializable @SerialName("onoff")
    data class OnOff(val on: Boolean) : TileValue

    @Serializable @SerialName("light")
    data class Light(val on: Boolean, val percent: Int?) : TileValue

    @Serializable @SerialName("measure")
    data class Measure(val value: Double, val unit: String?) : TileValue

    @Serializable @SerialName("text")
    data class Text(val value: String) : TileValue

    @Serializable @SerialName("contact")
    data class Contact(val deviceClass: String?, val on: Boolean) : TileValue

    @Serializable @SerialName("cover")
    data class Cover(val state: CoverState, val position: Int?) : TileValue

    @Serializable @SerialName("climate")
    data class Climate(val mode: String?, val current: Double?, val target: Double?) : TileValue

    @Serializable @SerialName("alarm")
    data class Alarm(val state: AlarmState) : TileValue
}

enum class TileIcon { LIGHT, SWITCH, COVER, CLIMATE, TEMPERATURE, ALARM, BUTTON, SENSOR }

enum class TileStatus { OK, PENDING, FAILED, REMOVED }

/** One tile's display data (widgets spec §3.2). No secrets. */
@Serializable
data class TileSnapshot(
    val uniqueId: String,
    val name: String,
    val kind: Kind,
    val icon: TileIcon,
    val value: TileValue,
    val available: Boolean = true,
    val alarmModes: Set<AlarmMode> = emptySet(),
    val codeArmRequired: Boolean = false,
    val codeDisarmRequired: Boolean = false,
    val codeFormat: CodeFormat = CodeFormat.NUMBER,
    val hvacModes: List<String> = emptyList(),
    /** The last non-"off" climate mode seen, so "on" can restore it. */
    val lastOnMode: String? = null,
    val historyAvailable: Boolean = false,
    val status: TileStatus = TileStatus.OK,
    val pendingSince: Long? = null,
    /** Epoch ms of the last live (non-cached) value; null when only cached values were ever seen. */
    val updatedAt: Long? = null,
) {
    /** A pending tile whose worker died is shown as failed (spec §4). */
    fun effectiveStatus(now: Long): TileStatus =
        if (status == TileStatus.PENDING && pendingSince != null && now - pendingSince > PENDING_LIMIT_MS) TileStatus.FAILED else status

    fun isStale(now: Long): Boolean = updatedAt == null || now - updatedAt > STALE_MS

    companion object {
        const val PENDING_LIMIT_MS = 30_000L
        const val STALE_MS = 30 * 60_000L
    }
}

enum class LinkStatus { UNKNOWN, FRESH, OFFLINE, AUTH_FAILED, NOT_CONFIGURED }

@Serializable
data class WidgetSnapshot(
    val tiles: Map<String, TileSnapshot> = emptyMap(),
    val link: LinkStatus = LinkStatus.UNKNOWN,
    /** Epoch ms of the last successful refresh. */
    val lastFresh: Long? = null,
)

@Serializable
data class WidgetConfig(val uniqueIds: List<String> = emptyList(), val armMode: AlarmMode? = null) {
    init {
        require(uniqueIds.size <= MAX_ENTITIES) { "at most $MAX_ENTITIES entities" }
    }

    companion object {
        const val MAX_ENTITIES = 8
    }
}
