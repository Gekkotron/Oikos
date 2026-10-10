package com.gekkotron.oikos.voice

import com.gekkotron.oikos.model.AlarmMode
import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.CodeFormat
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.Kind
import java.text.Normalizer

data class VoiceEntity(
    val uniqueId: String,
    val displayName: String,
    val kind: Kind,
    val areaId: String,
    val deviceClass: String?,
    val brightness: Boolean,
    val position: Boolean,
    val codeFormat: CodeFormat,
    val alarmModes: Set<AlarmMode>,
    val codeArmRequired: Boolean,
    val codeDisarmRequired: Boolean,
)

data class VoiceArea(
    val id: String,
    val displayName: String,
    val isPersonRoom: Boolean,
    val roomConcept: String?,
)

class VoiceCatalog(
    val lang: VoiceLang,
    val entities: List<VoiceEntity>,
    val areas: List<VoiceArea>,
) {
    private val byId: Map<String, VoiceEntity> = entities.associateBy { it.uniqueId }
    private val areaById: Map<String, VoiceArea> = areas.associateBy { it.id }

    fun entity(uniqueId: String): VoiceEntity? = byId[uniqueId]
    fun area(id: String): VoiceArea? = areaById[id]
}

object VoiceCatalogBuilder {
    private val SUPPORTED = setOf(Kind.LIGHT, Kind.SWITCH, Kind.COVER, Kind.ALARM_PANEL)
    private val DIACRITICS = Regex("\\p{Mn}+")
    private val LETTERS = Regex("[a-z]+")

    private fun fold(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(DIACRITICS, "")

    private fun tokens(s: String): List<String> =
        LETTERS.findAll(fold(s)).map { it.value }.filter { it.length >= 2 }.toList()

    fun build(lang: VoiceLang, entities: Collection<Entity>, areas: Collection<Area>): VoiceCatalog {
        val table = VoiceLexicon.of(lang)
        val voiceAreas = areas.map { a ->
            val concept = (tokens(a.id) + tokens(a.name)).firstNotNullOfOrNull { table.rooms[it] }
            VoiceArea(id = a.id, displayName = a.name, isPersonRoom = concept == null, roomConcept = concept)
        }
        val voiceEntities = entities.asSequence()
            .filter { it.kind in SUPPORTED }
            .filter { it.partOf == null }
            .filter { !it.ui.hidden }
            .map { e ->
                VoiceEntity(
                    uniqueId = e.uniqueId,
                    displayName = e.ui.name ?: e.name,
                    kind = e.kind,
                    areaId = e.area,
                    deviceClass = e.deviceClass,
                    brightness = e.capabilities.brightness,
                    position = e.capabilities.position,
                    codeFormat = e.capabilities.codeFormat,
                    alarmModes = e.capabilities.alarmModes,
                    codeArmRequired = e.capabilities.codeArmRequired,
                    codeDisarmRequired = e.capabilities.codeDisarmRequired,
                )
            }
            .toList()
        return VoiceCatalog(lang, voiceEntities, voiceAreas)
    }
}
