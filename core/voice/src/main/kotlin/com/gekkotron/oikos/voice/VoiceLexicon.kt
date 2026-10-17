package com.gekkotron.oikos.voice

/** Language-neutral vocabulary tables. All keys are accent-folded, lowercase. */
object VoiceLexicon {
    enum class Verb { ON, OFF, OPEN, CLOSE, STOP, STOP_OR_OFF, SET, ARM, DISARM, QUERY }

    data class Table(
        val verbs: Map<String, Verb>,
        val devices: Map<String, String>,
        val modifiers: Map<String, String>,
        val rooms: Map<String, String>,
        val noise: Set<String>,
        val allWords: Set<String>,
        val alarmHomeWords: Set<String>,
        val shortcutStopWords: Set<String>,
        val phrases: List<Pair<Regex, String>>,
        val attributes: Map<String, String>,
        val structuralQuery: List<Regex>,
    )

    private val FR = Table(
        verbs = buildMap {
            fun add(v: Verb, vararg ws: String) = ws.forEach { put(it, v) }
            add(Verb.ON, "allume", "allumer", "allumes", "allumez", "active", "activer", "actives", "activez", "marche", "demarre", "demarrer")
            add(Verb.OFF, "eteins", "eteint", "eteindre", "eteignez", "coupe", "couper", "coupes", "coupez", "desactive", "desactiver", "desactives", "desactivez")
            add(Verb.ARM, "arme", "armer", "armes", "armez")
            add(Verb.DISARM, "desarme", "desarmer", "desarmes", "desarmez")
            add(Verb.OPEN, "ouvre", "ouvrir", "ouvres", "ouvrez", "monte", "monter", "montes", "montez", "leve", "lever", "levez")
            add(Verb.CLOSE, "ferme", "fermer", "fermes", "fermez", "descends", "descend", "descendre", "descendez", "baisse", "baisser", "baisses", "baissez")
            add(Verb.STOP, "stop", "stoppe", "stopper", "stoppez")
            add(Verb.STOP_OR_OFF, "arrete", "arreter", "arretes", "arretez")
            add(Verb.SET, "mets", "met", "mettre", "mettez", "regle", "regler", "reglez", "passe", "passer")
            add(Verb.QUERY, "quelle", "quel", "quels", "quelles", "combien", "affiche", "affichez", "dis", "dites", "donne", "donnez", "lit", "lis", "lire", "montre", "montrez", "verifie", "verifier")
        },
        devices = buildMap {
            fun add(c: String, vararg ws: String) = ws.forEach { put(it, c) }
            add("cover", "volet", "volets", "store", "stores", "rideau", "rideaux")
            add("light", "lumiere", "lumieres", "lampe", "lampes", "ampoule", "ampoules", "plafonnier", "plafonniers", "eclairage", "luminaire", "guirlande", "guirlandes", "led", "leds")
            add("heater", "chauffage", "chauffages", "radiateur", "radiateurs")
            add("plug", "prise", "prises")
            add("siren", "sirene", "sirenes")
            add("fan", "ventilateur", "ventilateurs")
            add("tv", "tele", "television", "teles")
        },
        modifiers = buildMap {
            fun add(c: String, vararg ws: String) = ws.forEach { put(it, c) }
            add("ceiling", "plafond", "plafonnier", "plafonniers")
            add("tv", "tele", "television", "teles")
            add("north", "nord")
            add("south", "sud")
            add("east", "est")
            add("west", "ouest")
            add("christmas", "noel", "guirlande", "guirlandes")
            add("pool", "piscine")
            add("door", "porte", "portes")
        },
        rooms = buildMap {
            fun add(c: String, vararg ws: String) = ws.forEach { put(it, c) }
            add("living", "salon", "sejour")
            add("kitchen", "cuisine")
            add("office", "bureau")
            add("bathroom", "bain", "bains")
            add("bedroom", "chambre")
            add("garage", "garage")
            add("outdoor", "dehors", "exterieur", "jardin", "terrasse")
            add("stairs", "escalier")
            add("upstairs", "etage")
            add("entrance", "entree")
            add("hallway", "couloir")
            add("basement", "cave")
            add("laundry", "buanderie")
        },
        noise = setOf(
            "le", "la", "les", "un", "une", "du", "de", "des", "au", "aux", "dans", "sur", "en", "et", "ou",
            "il", "te", "tu", "je", "ce", "ca", "cela", "qu", "ne", "pas", "si", "chez", "mode",
            "peux", "peut", "pouvez", "veux", "voudrais", "veuillez", "svp", "stp", "plait", "merci",
            "bonjour", "salut", "moi", "nous", "maintenant", "bien", "donc", "ici", "pour", "cent", "pourcent",
            "pct", "maison", "moitie", "toute", "tout", "tous", "toutes", "ok",
        ),
        allWords = setOf("tout", "tous", "toute", "toutes"),
        alarmHomeWords = setOf("nuit", "partiel", "partielle", "presence", "maison"),
        shortcutStopWords = setOf(
            "le", "la", "les", "l", "du", "de", "des", "d", "un", "une", "au", "aux", "a", "dans", "sur", "en",
            "et", "est", "il", "elle", "on", "je", "j", "me", "m", "s", "y", "ai", "stp", "svp",
        ),
        phrases = listOf(
            Regex("""\bsalle de bains?\b""") to "bathroom",
            Regex("""\bchauffe eau\b""") to "waterheater",
        ),
        attributes = buildMap {
            fun add(c: String, vararg ws: String) = ws.forEach { put(it, c) }
            add("temperature", "temperature", "temperatures")
            add("humidity", "humidite", "humidites")
            add("battery", "batterie", "batteries")
            add("power", "puissance")
            add("energy", "consommation")
            add("voltage", "tension")
            add("current", "courant")
            add("illuminance", "luminosite", "lux")
            add("pressure", "pression")
            add("level", "niveau")
            add("state", "etat", "statut")
        },
        structuralQuery = listOf(
            Regex("""\bqu ?est ce que\b"""),
            Regex("""\best ce qu(?:e)?\b"""),
            Regex("""\bsont (?:ils|elles)\b"""),
            Regex("""\best (?:il|elle)\b"""),
        ),
    )

    private val EN = Table(
        verbs = buildMap {
            fun add(v: Verb, vararg ws: String) = ws.forEach { put(it, v) }
            add(Verb.ON, "turn", "switch", "activate", "start")
            add(Verb.OFF, "cut", "deactivate", "shut")
            add(Verb.ARM, "arm")
            add(Verb.DISARM, "disarm")
            add(Verb.OPEN, "open", "raise", "lift")
            add(Verb.CLOSE, "close", "shut", "lower", "drop")
            add(Verb.STOP, "stop", "halt")
            add(Verb.STOP_OR_OFF, "end", "finish")
            add(Verb.SET, "set", "put")
            add(Verb.QUERY, "what", "how", "show", "tell", "read", "give", "check")
        },
        devices = buildMap {
            fun add(c: String, vararg ws: String) = ws.forEach { put(it, c) }
            add("cover", "shutter", "shutters", "blind", "blinds", "curtain", "curtains")
            add("light", "light", "lights", "led", "leds", "lamp", "lamps", "bulb", "bulbs", "chandelier")
            add("heater", "heater", "heaters", "heating", "radiator", "radiators")
            add("plug", "plug", "plugs")
            add("siren", "siren", "sirens")
            add("fan", "fan", "fans")
            add("tv", "tv")
        },
        modifiers = buildMap {
            fun add(c: String, vararg ws: String) = ws.forEach { put(it, c) }
            add("ceiling", "ceiling")
            add("tv", "tv")
            add("north", "north")
            add("south", "south")
            add("east", "east")
            add("west", "west")
            add("christmas", "christmas")
            add("pool", "pool")
            add("door", "door", "doors")
        },
        rooms = buildMap {
            fun add(c: String, vararg ws: String) = ws.forEach { put(it, c) }
            add("living", "living", "lounge")
            add("kitchen", "kitchen")
            add("office", "office", "desk")
            add("bathroom", "bathroom", "bath")
            add("bedroom", "bedroom")
            add("garage", "garage")
            add("outdoor", "outdoor", "outside", "garden")
            add("stairs", "stair", "stairs")
            add("upstairs", "upstairs")
            add("entrance", "entrance")
            add("hallway", "hallway", "corridor")
            add("basement", "basement", "cellar")
            add("laundry", "laundry")
        },
        noise = setOf(
            "the", "a", "an", "to", "in", "on", "at", "please", "now", "ok", "and", "or",
            "i", "you", "we", "my", "our", "me", "us", "percent", "pct", "half", "all",
        ),
        allWords = setOf("all"),
        alarmHomeWords = setOf("night", "partial", "home", "stay", "presence"),
        shortcutStopWords = setOf(
            "the", "a", "an", "to", "in", "on", "at", "and", "or", "of", "for", "with",
            "i", "you", "we", "me", "my", "our", "please",
        ),
        phrases = listOf(
            Regex("""\bliving room\b""") to "living",
            Regex("""\bwater heater\b""") to "waterheater",
        ),
        attributes = buildMap {
            fun add(c: String, vararg ws: String) = ws.forEach { put(it, c) }
            add("temperature", "temperature")
            add("humidity", "humidity")
            add("battery", "battery")
            add("power", "power")
            add("energy", "energy")
            add("voltage", "voltage")
            add("current", "current")
            add("illuminance", "illuminance", "lux")
            add("pressure", "pressure")
            add("level", "level")
            add("state", "state", "status")
        },
        structuralQuery = listOf(
            Regex("""\bis it\b"""),
            Regex("""\bare the\b"""),
            Regex("""\bare there\b"""),
            Regex("""\bwhat (?:is|are) the\b"""),
        ),
    )

    fun of(lang: VoiceLang): Table = when (lang) {
        VoiceLang.FR -> FR
        VoiceLang.EN -> EN
    }

    fun actionLabel(lang: VoiceLang, a: VoiceAction): String = when (lang) {
        VoiceLang.FR -> when (a) {
            VoiceAction.TURN_ON -> "Allumer"
            VoiceAction.TURN_OFF -> "Éteindre"
            VoiceAction.OPEN -> "Ouvrir"
            VoiceAction.CLOSE -> "Fermer"
            VoiceAction.STOP -> "Stop"
            VoiceAction.SET_POSITION -> "Position"
            VoiceAction.SET_BRIGHTNESS -> "Luminosité"
            VoiceAction.ARM_AWAY -> "Armer (absent)"
            VoiceAction.ARM_HOME -> "Armer (présent)"
            VoiceAction.DISARM -> "Désarmer"
            VoiceAction.QUERY_STATE -> "Consulter l'état"
        }
        VoiceLang.EN -> when (a) {
            VoiceAction.TURN_ON -> "Turn on"
            VoiceAction.TURN_OFF -> "Turn off"
            VoiceAction.OPEN -> "Open"
            VoiceAction.CLOSE -> "Close"
            VoiceAction.STOP -> "Stop"
            VoiceAction.SET_POSITION -> "Position"
            VoiceAction.SET_BRIGHTNESS -> "Brightness"
            VoiceAction.ARM_AWAY -> "Arm (away)"
            VoiceAction.ARM_HOME -> "Arm (home)"
            VoiceAction.DISARM -> "Disarm"
            VoiceAction.QUERY_STATE -> "Check status"
        }
    }
}
