package com.gekkotron.oikos.voice

import com.gekkotron.oikos.model.AlarmState
import com.gekkotron.oikos.model.CoverState
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.StateValue
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnswerFormatterTest {
    private val now = Instant.parse("2026-10-17T12:00:00Z")
    private val fr = VoiceLang.FR
    private val en = VoiceLang.EN

    private fun fmt(
        lang: VoiceLang,
        entities: List<com.gekkotron.oikos.model.Entity>,
        states: List<EntityState>,
        hint: QueryHint? = null,
    ): Answer {
        val catalog = VoiceCatalogBuilder.build(lang, entities, areas)
        return AnswerFormatter.format(
            lang,
            VoiceIntent(VoiceAction.QUERY_STATE, entities.map { it.uniqueId }, hint = hint),
            states.associateBy { it.uniqueId },
            catalog,
            now,
        )
    }

    private fun one(lang: VoiceLang, e: com.gekkotron.oikos.model.Entity, v: StateValue, hint: QueryHint? = null) =
        fmt(lang, listOf(e), listOf(st(e.uniqueId, v)), hint)

    // Single-entity sensors
    @Test fun `FR temperature sensor renders il fait`() =
        assertEquals("Il fait 22° dans le salon", one(fr, entity("t", "Temp", Kind.SENSOR, "salon", "temperature", unit = "°C"), num(21.6)).spoken)

    @Test fun `EN temperature sensor renders it's`() =
        assertEquals("It's 22° in the living", one(en, entity("t", "Temp", Kind.SENSOR, "living", "temperature", unit = "°C"), num(22.2)).spoken)

    @Test fun `FR humidity sensor includes percent`() =
        assertEquals("L'humidité du salon est de 45 %", one(fr, entity("h", "Hum", Kind.SENSOR, "salon", "humidity", unit = "%"), num(45.0)).spoken)

    @Test fun `EN humidity sensor includes percent`() =
        assertEquals("Humidity in the living is 45 %", one(en, entity("h", "Hum", Kind.SENSOR, "living", "humidity", unit = "%"), num(45.0)).spoken)

    @Test fun `FR battery sensor leads with device name`() =
        assertEquals("Batterie de Détecteur porte : 80 %", one(fr, entity("b", "Détecteur porte", Kind.SENSOR, "salon", "battery", unit = "%"), num(80.0)).spoken)

    @Test fun `FR power sensor falls back to name value unit`() =
        assertEquals("Frigo : 42 W", one(fr, entity("p", "Frigo", Kind.SENSOR, "cuisine", "power", unit = "W"), num(42.0)).spoken)

    // Boolean kinds
    @Test fun `FR light on renders allumee`() =
        assertEquals("La lumière du salon est allumée", one(fr, entity("l", "Lampe", Kind.LIGHT, "salon"), StateValue.OnOff(true)).spoken)

    @Test fun `FR light off renders eteinte`() =
        assertEquals("La lumière du salon est éteinte", one(fr, entity("l", "Lampe", Kind.LIGHT, "salon"), StateValue.OnOff(false)).spoken)

    @Test fun `FR light on with brightness appends luminosite`() =
        assertEquals(
            "La lumière du salon est allumée, luminosité 60 %",
            one(fr, entity("l", "Lampe", Kind.LIGHT, "salon", brightness = true), StateValue.Light(true, brightness = 153)).spoken,
        )

    @Test fun `EN light on with brightness`() =
        assertEquals(
            "The living light is on, brightness 60 %",
            one(en, entity("l", "Lamp", Kind.LIGHT, "living", brightness = true), StateValue.Light(true, brightness = 153)).spoken,
        )

    @Test fun `FR switch renders name and state`() =
        assertEquals("Prise TV : allumé", one(fr, entity("s", "Prise TV", Kind.SWITCH, "salon"), StateValue.OnOff(true)).spoken)

    @Test fun `FR cover open renders ouvert`() =
        assertEquals("Le volet du salon est ouvert", one(fr, entity("c", "Volet", Kind.COVER, "salon"), StateValue.Cover(CoverState.OPEN, 100)).spoken)

    @Test fun `FR cover with position 42 renders partiellement ouvert`() =
        assertEquals(
            "Le volet du salon est partiellement ouvert (42 %)",
            one(fr, entity("c", "Volet", Kind.COVER, "salon", position = true), StateValue.Cover(CoverState.OPEN, 42)).spoken,
        )

    @Test fun `FR cover moving states`() {
        val e = entity("c", "Volet", Kind.COVER, "salon")
        assertEquals("Le volet du salon est en cours d'ouverture", one(fr, e, StateValue.Cover(CoverState.OPENING)).spoken)
        assertEquals("Le volet du salon est en cours de fermeture", one(fr, e, StateValue.Cover(CoverState.CLOSING)).spoken)
        assertEquals("Le volet du salon est arrêté", one(fr, e, StateValue.Cover(CoverState.STOPPED)).spoken)
    }

    @Test fun `EN cover partially open`() =
        assertEquals(
            "The living shutter is partially open (42 %)",
            one(en, entity("c", "Shutter", Kind.COVER, "living"), StateValue.Cover(CoverState.OPEN, 42)).spoken,
        )

    @Test fun `FR binary_sensor door closed renders fermee`() =
        assertEquals("La porte du garage est fermée", one(fr, entity("d", "Porte", Kind.BINARY_SENSOR, "garage", "door"), StateValue.OnOff(false)).spoken)

    @Test fun `FR plain binary_sensor renders actif`() =
        assertEquals("Mouvement : actif", one(fr, entity("m", "Mouvement", Kind.BINARY_SENSOR, "garage"), StateValue.OnOff(true)).spoken)

    @Test fun `FR alarm armed_away renders localized state`() =
        assertEquals("L'alarme est armée (absence)", one(fr, entity("a", "Alarme", Kind.ALARM_PANEL, "garage"), StateValue.Alarm(AlarmState.ARMED_AWAY)).spoken)

    @Test fun `EN alarm disarmed`() =
        assertEquals("The alarm is disarmed", one(en, entity("a", "Alarm", Kind.ALARM_PANEL, "garage"), StateValue.Alarm(AlarmState.DISARMED)).spoken)

    // Climate / water heater / vacuum / number
    @Test fun `FR climate renders current target and mode`() =
        assertEquals(
            "Salon : 21° actuel, consigne 19°, mode chauffage",
            one(fr, entity("cl", "Clim", Kind.CLIMATE, "salon"), StateValue.Climate("heat", 19.0, 21.0)).spoken,
        )

    @Test fun `climate skips missing fields`() =
        assertEquals("Living: 21° now", one(en, entity("cl", "Clim", Kind.CLIMATE, "living"), StateValue.Climate(null, null, 21.0)).spoken)

    @Test fun `FR water_heater renders like climate`() =
        assertEquals(
            "Cuisine : 55° actuel, consigne 60°",
            one(fr, entity("w", "Chauffe-eau", Kind.WATER_HEATER, "cuisine"), StateValue.Climate(null, 60.0, 55.0)).spoken,
        )

    @Test fun `FR vacuum renders state and battery`() =
        assertEquals(
            "Roborock : à la base, batterie 100 %",
            one(fr, entity("v", "Roborock", Kind.VACUUM, "salon"), StateValue.Vacuum("docked", 100, null)).spoken,
        )

    @Test fun `FR number renders name value unit`() =
        assertEquals("Seuil : 30 min", one(fr, entity("n", "Seuil", Kind.NUMBER, "salon", unit = "min"), num(30.0)).spoken)

    // Group aggregation (boolean)
    @Test fun `FR all shutters closed with hint renders Oui tous`() =
        assertEquals("Oui, tous les volets sont fermés", shutters(CoverState.CLOSED, CoverState.CLOSED, CoverState.CLOSED, hint = QueryHint.EXPECT_CLOSED).spoken)

    @Test fun `FR all shutters closed without hint has no Oui lead`() =
        assertEquals("Tous les volets sont fermés", shutters(CoverState.CLOSED, CoverState.CLOSED).spoken)

    @Test fun `FR none closed with hint renders Non aucun`() =
        assertEquals("Non, aucun volet n'est fermé", shutters(CoverState.OPEN, CoverState.OPEN, hint = QueryHint.EXPECT_CLOSED).spoken)

    @Test fun `FR mixed shutters renders k sur n`() =
        assertEquals(
            "Non, 2 volets sur 5 sont fermés, 3 ouverts",
            shutters(CoverState.CLOSED, CoverState.CLOSED, CoverState.OPEN, CoverState.OPEN, CoverState.OPEN, hint = QueryHint.EXPECT_CLOSED).spoken,
        )

    @Test fun `FR mixed shutters without hint leads with open count`() =
        assertEquals("1 volet sur 2 est ouvert, 1 fermé", shutters(CoverState.CLOSED, CoverState.OPEN).spoken)

    @Test fun `FR hint EXPECT_OPEN leads with Oui when all open`() =
        assertEquals("Oui, tous les volets sont ouverts", shutters(CoverState.OPEN, CoverState.OPEN, hint = QueryHint.EXPECT_OPEN).spoken)

    @Test fun `EN all shutters closed with hint`() {
        val es = listOf(entity("c1", "S1", Kind.COVER, "living"), entity("c2", "S2", Kind.COVER, "living"))
        val a = fmt(en, es, es.map { st(it.uniqueId, StateValue.Cover(CoverState.CLOSED)) }, QueryHint.EXPECT_CLOSED)
        assertEquals("Yes, all the shutters are closed", a.spoken)
    }

    @Test fun `EN none of the shutters`() {
        val es = listOf(entity("c1", "S1", Kind.COVER, "living"), entity("c2", "S2", Kind.COVER, "living"))
        val a = fmt(en, es, es.map { st(it.uniqueId, StateValue.Cover(CoverState.OPEN)) }, QueryHint.EXPECT_CLOSED)
        assertEquals("No, none of the shutters are closed", a.spoken)
    }

    @Test fun `FR lights group uses feminine agreement`() {
        val es = listOf(entity("l1", "A", Kind.LIGHT, "salon"), entity("l2", "B", Kind.LIGHT, "salon"))
        val a = fmt(fr, es, es.map { st(it.uniqueId, StateValue.OnOff(true)) }, QueryHint.EXPECT_ON)
        assertEquals("Oui, toutes les lumières sont allumées", a.spoken)
    }

    // Group aggregation (sensor)
    @Test fun `FR 3 temperatures in salon averages with en moyenne`() {
        val es = temps("Capteur A", "Capteur B", "Capteur C")
        val a = fmt(fr, es, listOf(st("t0", num(21.0)), st("t1", num(22.0)), st("t2", num(23.0))))
        assertEquals("Il fait 22° en moyenne dans le salon", a.spoken)
    }

    @Test fun `FR 3 temperatures with one named Temperature wins as ambient and skips average`() {
        val es = temps("Capteur A", "Température", "Capteur C")
        val a = fmt(fr, es, listOf(st("t0", num(21.0)), st("t1", num(24.0)), st("t2", num(30.0))))
        assertEquals("Il fait 24° dans le salon", a.spoken)
        assertFalse(a.spoken.contains("moyenne"))
    }

    @Test fun `ambient also matches room plus class name`() {
        val es = temps("Capteur A", "Salon temperature")
        val a = fmt(fr, es, listOf(st("t0", num(10.0)), st("t1", num(20.0))))
        assertEquals("Il fait 20° dans le salon", a.spoken)
    }

    @Test fun `EN humidity average`() {
        val es = listOf(
            entity("h0", "A", Kind.SENSOR, "living", "humidity", unit = "%"),
            entity("h1", "B", Kind.SENSOR, "living", "humidity", unit = "%"),
        )
        val a = fmt(en, es, listOf(st("h0", num(40.0)), st("h1", num(50.0))))
        assertEquals("Average humidity in the living is 45 %", a.spoken)
    }

    @Test fun `FR temperatures across 3 rooms render room value with unit once`() {
        val es = listOf(
            entity("t0", "A", Kind.SENSOR, "salon", "temperature", unit = "°C"),
            entity("t1", "B", Kind.SENSOR, "cuisine", "temperature", unit = "°C"),
            entity("t2", "C", Kind.SENSOR, "bureau", "temperature", unit = "°C"),
        )
        val a = fmt(fr, es, listOf(st("t0", num(22.0)), st("t1", num(23.0)), st("t2", num(21.0))))
        assertEquals("Salon : 22°, Cuisine : 23, Bureau : 21", a.spoken)
    }

    @Test fun `across rooms each room collapses by the single-room rule`() {
        val es = listOf(
            entity("t0", "A", Kind.SENSOR, "salon", "temperature"),
            entity("t1", "B", Kind.SENSOR, "salon", "temperature"),
            entity("t2", "C", Kind.SENSOR, "cuisine", "temperature"),
        )
        val a = fmt(fr, es, listOf(st("t0", num(20.0)), st("t1", num(22.0)), st("t2", num(25.0))))
        assertEquals("Salon : 21°, Cuisine : 25", a.spoken)
    }

    @Test fun `FR discrete power sensors list per entity with no average`() {
        val es = listOf(
            entity("p0", "Capteur frigo", Kind.SENSOR, "cuisine", "power", unit = "W"),
            entity("p1", "Capteur TV", Kind.SENSOR, "salon", "power", unit = "W"),
        )
        val a = fmt(fr, es, listOf(st("p0", num(42.0)), st("p1", num(85.0))))
        assertEquals("Capteur frigo : 42 W, Capteur TV : 85 W", a.spoken)
    }

    @Test fun `mixed kinds fall back to single sentences joined by semicolon`() {
        val es = listOf(entity("l", "Lampe", Kind.LIGHT, "salon"), entity("c", "Volet", Kind.COVER, "salon"))
        val a = fmt(fr, es, listOf(st("l", StateValue.OnOff(true)), st("c", StateValue.Cover(CoverState.CLOSED))))
        assertEquals("La lumière du salon est allumée; Le volet du salon est fermé", a.spoken)
    }

    // Fallbacks (review focus 1 and 2)
    @Test fun `an Unknown or Unsupported state renders as the no-reading fallback`() {
        val e = entity("t", "Sonde", Kind.SENSOR, "salon", "temperature")
        val unknown = one(fr, e, StateValue.Unknown)
        assertEquals("Je n'ai pas la valeur de Sonde", unknown.spoken)
        assertFalse(unknown.speakable)
        val unsupported = one(en, e, StateValue.Unsupported)
        assertEquals("I don't have a reading for Sonde", unsupported.spoken)
        assertFalse(unsupported.speakable)
    }

    @Test fun `an unavailable entity renders as the no-reading fallback`() {
        val e = entity("t", "Sonde", Kind.SENSOR, "salon", "temperature")
        val a = fmt(fr, listOf(e), listOf(st("t", num(20.0), available = false)))
        assertEquals("Je n'ai pas la valeur de Sonde", a.spoken)
        assertFalse(a.speakable)
    }

    @Test fun `a missing state also renders the fallback and lists at most three names`() {
        val es = (1..5).map { entity("t$it", "S$it", Kind.SENSOR, "salon", "temperature") }
        val a = fmt(fr, es, emptyList())
        assertEquals("Je n'ai pas la valeur de S1, S2, S3 et 2 autres", a.spoken)
    }

    @Test fun `partial unavailable skips entities and appends the no-data tail`() {
        val es = listOf(entity("c1", "S1", Kind.COVER, "salon"), entity("c2", "S2", Kind.COVER, "salon"), entity("c3", "S3", Kind.COVER, "salon"))
        val a = fmt(
            fr, es,
            listOf(st("c1", StateValue.Cover(CoverState.CLOSED)), st("c2", StateValue.Cover(CoverState.CLOSED)), st("c3", StateValue.Unknown)),
            QueryHint.EXPECT_CLOSED,
        )
        assertEquals("Oui, tous les volets sont fermés (1 entité sans donnée)", a.spoken)
        assertTrue(a.speakable)
    }

    @Test fun `EN partial unavailable tail`() {
        val es = listOf(entity("c1", "S1", Kind.COVER, "living"), entity("c2", "S2", Kind.COVER, "living"), entity("c3", "S3", Kind.COVER, "living"), entity("c4", "S4", Kind.COVER, "living"))
        val a = fmt(
            en, es,
            listOf(st("c1", StateValue.Cover(CoverState.CLOSED)), st("c2", StateValue.Unknown), st("c3", StateValue.Unknown), st("c4", StateValue.Unknown)),
        )
        assertEquals("The living shutter is closed (3 entities with no data)", a.spoken)
    }

    @Test fun `a stale state appends the updated-X-ago tail once`() {
        val es = listOf(entity("t0", "A", Kind.SENSOR, "salon", "temperature"), entity("t1", "B", Kind.SENSOR, "salon", "temperature"))
        val a = fmt(
            fr, es,
            listOf(
                st("t0", num(20.0), stale = true, lastUpdate = now.minus(Duration.ofHours(3)).minusSeconds(60)),
                st("t1", num(22.0), stale = true, lastUpdate = now.minus(Duration.ofHours(5))),
            ),
        )
        assertEquals("Il fait 21° en moyenne dans le salon (mise à jour il y a 3h)", a.spoken)
        assertEquals(1, Regex("mise à jour").findAll(a.spoken).count())
        assertEquals(listOf("t0", "t1"), a.staleIds)
        assertTrue(a.speakable)
    }

    @Test fun `EN stale tail`() {
        val e = entity("t", "Temp", Kind.SENSOR, "living", "temperature")
        val a = fmt(en, listOf(e), listOf(st("t", num(20.0), stale = true, lastUpdate = now.minus(Duration.ofHours(2)))))
        assertEquals("It's 20° in the living (updated 2h ago)", a.spoken)
    }

    @Test fun `stale under an hour and unknown update time`() {
        val e = entity("t", "Temp", kind = Kind.SENSOR, area = "salon", deviceClass = "temperature")
        val recent = fmt(fr, listOf(e), listOf(st("t", num(20.0), stale = true, lastUpdate = now.minusSeconds(600))))
        assertEquals("Il fait 20° dans le salon (mise à jour il y a moins d'une heure)", recent.spoken)
        val none = fmt(en, listOf(e), listOf(st("t", num(20.0), stale = true)))
        assertEquals("It's 20° in the salon (last update unknown)", none.spoken)
    }

    @Test fun `non stale answer has no staleness tail and no stale ids`() {
        val a = one(fr, entity("t", "Temp", Kind.SENSOR, "salon", "temperature"), num(20.0))
        assertTrue(a.staleIds.isEmpty())
        assertFalse(a.spoken.contains("mise à jour"))
        assertEquals(a.spoken, a.display)
    }

    // FR gendered room articles (regression: feminine rooms used to say "dans le cuisine")
    @Test fun `FR temperature in cuisine renders dans la cuisine`() =
        assertEquals(
            "Il fait 22° dans la cuisine",
            one(fr, entity("t", "Temp", Kind.SENSOR, "cuisine", "temperature", unit = "°C"), num(22.0)).spoken,
        )

    @Test fun `FR temperature in chambre renders dans la chambre`() =
        assertEquals(
            "Il fait 20° dans la chambre",
            one(fr, entity("t", "Temp", Kind.SENSOR, "chambre", "temperature", unit = "°C"), num(20.0)).spoken,
        )

    @Test fun `FR temperature in entree renders dans l'entree`() =
        assertEquals(
            "Il fait 19° dans l'entrée",
            one(fr, entity("t", "Temp", Kind.SENSOR, "entree", "temperature", unit = "°C"), num(19.0)).spoken,
        )

    @Test fun `FR humidity in cuisine renders de la cuisine`() =
        assertEquals(
            "L'humidité de la cuisine est de 55 %",
            one(fr, entity("h", "Hum", Kind.SENSOR, "cuisine", "humidity", unit = "%"), num(55.0)).spoken,
        )

    @Test fun `FR shutter in chambre uses le volet plus de la chambre`() =
        assertEquals(
            "Le volet de la chambre est ouvert",
            one(fr, entity("c", "Volet", Kind.COVER, "chambre"), StateValue.Cover(CoverState.OPEN, 100)).spoken,
        )

    // Fixtures
    private val areas = listOf(
        area("salon", "Salon"), area("living", "Living"), area("cuisine", "Cuisine"),
        area("bureau", "Bureau"), area("garage", "Garage"),
        area("chambre", "Chambre"), area("entree", "Entrée"),
    )

    private fun num(v: Double) = StateValue.Numeric(v)

    private fun st(
        id: String,
        v: StateValue,
        available: Boolean = true,
        stale: Boolean = false,
        lastUpdate: Instant? = null,
    ) = EntityState(uniqueId = id, value = v, available = available, stale = stale, lastUpdate = lastUpdate)

    private fun temps(vararg names: String) =
        names.mapIndexed { i, n -> entity("t$i", n, Kind.SENSOR, "salon", "temperature", unit = "°C") }

    private fun shutters(vararg states: CoverState, hint: QueryHint? = null): Answer {
        val es = states.indices.map { entity("c$it", "Volet $it", Kind.COVER, "salon") }
        return fmt(fr, es, states.mapIndexed { i, s -> st("c$i", StateValue.Cover(s)) }, hint)
    }
}
