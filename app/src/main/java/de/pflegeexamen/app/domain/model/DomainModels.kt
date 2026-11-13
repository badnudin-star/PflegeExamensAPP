package de.pflegeexamen.app.domain.model

import kotlinx.serialization.Serializable

enum class SourceType(val displayLabel: String) {
    PDF("PDF-Dokument"),
    DOCX("Word (.docx)"),
    TXT("Textdatei (.txt)"),
    MD("Markdown (.md)"),
    IMAGE_OCR("Foto-Scan (OCR)"),
    MANUAL_NOTE("Eigene Notiz")
}

enum class DetailDepth(val displayLabel: String, val maxPointsPerSection: Int, val maxTotalPassages: Int) {
    COMPACT("Kompakt (Kernfakten)", 4, 8),
    STANDARD("Standard (Ausgewogen)", 7, 14),
    EXAM_DEEP("Examenstief (Ausführlich)", 12, 24)
}

enum class PriorityLevel(val displayLabel: String) {
    HIGH("Hoch (Prüfungsschwerpunkt)"),
    MEDIUM("Mittel"),
    LOW("Basis")
}

enum class FlashcardType(val displayLabel: String) {
    DEFINITION("Definition"),
    CAUSAL("Ursache / Begründung"),
    LIST("Aufzählung / Schritte"),
    CLOZE("Lückentext"),
    CAVE("Cave / Merksatz")
}

enum class MasteryStatus(val displayLabel: String) {
    OPEN("Offen"),
    REVIEWING("Wiederholen"),
    MASTERED("Sicher beherrscht")
}

enum class OperatorStructureType(val displayLabel: String, val shortHint: String) {
    FACTS_LIST("Fakten, Symptome & Aufzählung", "Für Nennen, Beschriften, Analysieren (Tabelle)"),
    DEFINITION("Begriffserklärung & Beschreibung", "Für Definieren, Beschreiben (statisch)"),
    CAUSAL_REASONING("Ursache, Wirkung & Begründung", "Für Erklären, Begründen, Erläutern, Entwickeln"),
    PROCESS_PLANNING("Pflegeprozess, Ablauf & Maßnahme", "Für Beschreiben (Ablauf), Formulieren (Pflegeplanung/Maßnahme)"),
    COMPARISON("Vergleich, Pro & Kontra", "Für Diskutieren (Erörtern), Vergleichen"),
    EVALUATION("Analyse, Stellungnahme & Urteil", "Für Analysieren (Fließtext), Stellung nehmen, Beurteilen")
}

enum class CaseRelationRule(val displayLabel: String, val promptInstruction: String) {
    NO_CASE_RELATION(
        "Kein Fallbezug (allgemeingültig)",
        "Bei diesem Operator darf KEIN Fallbezug hergestellt werden – Aufgabe und Musterlösung müssen allgemeingültig formuliert sein."
    ),
    OPTIONAL_CASE_RELATION(
        "Fallbezug optional",
        "Ein Fallbezug ist möglich, falls sich dies sinnvoll aus den Quellen oder der Fragestellung ergibt."
    ),
    REQUIRED_CASE_RELATION(
        "Fallbezug Pflicht",
        "Bei diesem Operator MUSS zwingend ein konkreter Bezug zur beschriebenen Fallsituation/Person hergestellt werden."
    )
}

enum class SelfEvaluation(val displayLabel: String) {
    UNRATED("Noch nicht bewertet"),
    COMPLETE("Vollständig getroffen"),
    PARTIAL("Teilweise getroffen"),
    NEEDS_REVIEW("Nochmals üben")
}

enum class SentenceRole {
    DEFINITION,
    CAUSE_RISK,
    SYMPTOM_ASSESSMENT,
    INTERVENTION_MEASURE,
    REASON_GOAL,
    CAVE_WARNING,
    LEGAL_ETHICS,
    CASE_CONTEXT,
    LIST_ITEM,
    GENERAL
}

enum class AiProviderMode(
    val displayLabel: String,
    val badgeLabel: String,
    val description: String,
    val defaultModel: String
) {
    ON_DEVICE(
        displayLabel = "Smartphone-KI (Gemini Nano, auf dem Gerät)",
        badgeLabel = "Smartphone-KI",
        description = "Nutzt die eingebaute KI deines Smartphones (Android AICore / Gemini Nano) – komplett offline, ohne API-Key und ohne Internet. Lernzettel, Karteikarten, Fallbeispiele und Fragen werden direkt auf dem Handy erzeugt.",
        defaultModel = "gemini-nano-on-device"
    ),
    OFFLINE_ONLY(
        displayLabel = "100 % Offline (ohne KI)",
        badgeLabel = "Offline",
        description = "Läuft komplett lokal auf dem Gerät ohne Internetzugang und ohne API-Key.",
        defaultModel = ""
    ),
    GROQ_FREE(
        displayLabel = "Groq Free Tier (0 €, eigener Key)",
        badgeLabel = "Groq KI",
        description = "Kostenloser Free-Tier ohne Kreditkarte (z. B. Llama 3.3 70B / Qwen3). Extrem schnell, streng quellengebunden.",
        defaultModel = "llama-3.3-70b-versatile"
    ),
    GEMINI_FREE(
        displayLabel = "Google Gemini Free Tier (0 €, eigener Key)",
        badgeLabel = "Gemini KI",
        description = "Kostenloser API-Key über Google AI Studio ohne Kreditkarte (Flash / Flash-Lite).",
        defaultModel = "gemini-2.5-flash"
    ),
    OLLAMA_LOCAL(
        displayLabel = "Lokales Ollama im Heim-WLAN (0 €)",
        badgeLabel = "Ollama LAN",
        description = "Spricht im eigenen WLAN mit deinem kostenlosen lokalen Modell auf PC oder Laptop.",
        defaultModel = "llama3.1:8b"
    )
}

data class AiSettings(
    val mode: AiProviderMode = AiProviderMode.ON_DEVICE,
    val groqApiKey: String = "",
    val groqModel: String = AiProviderMode.GROQ_FREE.defaultModel,
    val geminiApiKey: String = "",
    val geminiModel: String = AiProviderMode.GEMINI_FREE.defaultModel,
    val ollamaBaseUrl: String = "http://192.168.178.20:11434",
    val ollamaModel: String = AiProviderMode.OLLAMA_LOCAL.defaultModel
) {
    fun isOnDevice(): Boolean = mode == AiProviderMode.ON_DEVICE

    fun isReadyForRemoteCall(): Boolean = when (mode) {
        AiProviderMode.ON_DEVICE -> false
        AiProviderMode.OFFLINE_ONLY -> false
        AiProviderMode.GROQ_FREE -> groqApiKey.isNotBlank()
        AiProviderMode.GEMINI_FREE -> geminiApiKey.isNotBlank()
        AiProviderMode.OLLAMA_LOCAL -> ollamaBaseUrl.isNotBlank() && ollamaModel.isNotBlank()
    }

    fun activeModelName(): String = when (mode) {
        AiProviderMode.ON_DEVICE -> "Smartphone-KI (Gemini Nano)"
        AiProviderMode.OFFLINE_ONLY -> "Lokale Engine"
        AiProviderMode.GROQ_FREE -> groqModel.ifBlank { mode.defaultModel }
        AiProviderMode.GEMINI_FREE -> geminiModel.ifBlank { mode.defaultModel }
        AiProviderMode.OLLAMA_LOCAL -> ollamaModel.ifBlank { mode.defaultModel }
    }
}

enum class ExamRank(val title: String, val minXp: Int, val nextMinXp: Int?) {
    ORIENTATION("Orientierungseinsatz", 0, 150),
    DUTY("Pflichteinsatz", 150, 400),
    SPECIALIZATION("Vertiefungseinsatz", 400, 900),
    CANDIDATE("Examenskandidat/in", 900, 1800),
    EXAM_READY("Prüfungsreif", 1800, null);

    companion object {
        fun forXp(xp: Int): ExamRank =
            entries.lastOrNull { xp >= it.minXp } ?: ORIENTATION
    }
}

object ParameterSuggestions {
    val suggestedFocusParameters = listOf(
        "Anatomie & Physiologie",
        "Definition & Grundbegriffe",
        "Pathophysiologie & Ursachen",
        "Symptome & Beobachtung",
        "Pflegemaßnahmen",
        "Begründungen & Ziele",
        "Prophylaxen",
        "Werte, Skalen & Stadien",
        "Merksätze & Cave",
        "Beratung & Anleitung",
        "Recht & Ethik"
    )
}

data class DefaultCategoryTemplate(
    val name: String,
    val shortCode: String,
    val colorHex: Long,
    val description: String
)

data class DefaultOperatorTemplate(
    val name: String,
    val requirementLevel: String,
    val description: String,
    val exampleQuestion: String,
    val expectationHorizon: String,
    val commentNotes: String,
    val gradingRubric: String,
    val caseRelationRule: CaseRelationRule,
    val questionPattern: String,
    val structureType: OperatorStructureType,
    val defaultPoints: Int
)

data class DefaultPreparationTemplate(
    val title: String,
    val examDayTag: String,
    val competenceFocusCodes: String,
    val categoryShortCode: String,
    val keywordsCsv: String,
    val isClinicalPicture: Boolean,
    val detailDepth: DetailDepth,
    val priority: PriorityLevel,
    val customPromptNote: String = ""
) {
    fun focusParameters(): List<String> = if (isClinicalPicture) {
        listOf(
            "Anatomie & Physiologie",
            "Definition & Grundbegriffe",
            "Pathophysiologie & Ursachen",
            "Symptome & Beobachtung",
            "Pflegemaßnahmen",
            "Begründungen & Ziele",
            "Prophylaxen",
            "Merksätze & Cave"
        )
    } else {
        listOf(
            "Definition & Grundbegriffe",
            "Pflegemaßnahmen",
            "Begründungen & Ziele",
            "Beratung & Anleitung",
            "Recht & Ethik",
            "Merksätze & Cave"
        )
    }
}

/**
 * Offizielle Prüfungskataloge:
 * - Vorbereitungsliste Frühjahr 2027 (Landesarbeitsgruppe schriftliches Examen, Tag 1–3 inkl. Anatomie/Physiologie-Regel)
 * - Operatorenliste der Landes-AG Schriftliches Examen auf Grundlage von Jann Wegner (Überarbeitung Dezember 2025)
 * Die Themen sind fest; Operatoren und Quellen werden über das HTML-Studio verwaltet.
 */
object DefaultCatalogs {

    val defaultCategories: List<DefaultCategoryTemplate> = listOf(
        DefaultCategoryTemplate(
            name = "Prüfungstag 1 · I.1, I.5, I.6, II.1, IV",
            shortCode = "Tag 1",
            colorHex = 0xFF0E6B6EL,
            description = "Pflegeprozesse & Lebensgestaltung, Entwicklung, Kommunikation, Angehörige, SGB V/XI, Recht & Qualität"
        ),
        DefaultCategoryTemplate(
            name = "Prüfungstag 2 · I.2, II.2, IV.1",
            shortCode = "Tag 2",
            colorHex = 0xFF1565C0L,
            description = "Gesundheitsförderung, Prävention, Beratung/Schulung, Expertenstandards, Pflegeforschung & Modelle"
        ),
        DefaultCategoryTemplate(
            name = "Prüfungstag 3 · I.3, I.4, II.3, III.2",
            shortCode = "Tag 3",
            colorHex = 0xFF6A1B9AL,
            description = "Kritische & lebensbedrohliche Lebenssituationen, Notfall, Ethik & ärztliche Anordnungen"
        ),
        DefaultCategoryTemplate(
            name = "Übergreifend / Eigene Quellen",
            shortCode = "Allgemein",
            colorHex = 0xFF455A64L,
            description = "Schulskripte, Gesetzestexte, übergreifende Standards & eigene Ergänzungen"
        )
    )

    val defaultPreparations: List<DefaultPreparationTemplate> = listOf(
        // ================= TAG 1: I.1, I.5, I.6, II.1, IV =================
        DefaultPreparationTemplate(
            title = "(Altersgerechte) Kommunikation",
            examDayTag = "Tag 1",
            competenceFocusCodes = "I.1, I.5, I.6, II.1, IV",
            categoryShortCode = "Tag 1",
            keywordsCsv = "Kommunikation, Gesprächsführung, altersgerecht, Kinder, ältere Menschen, Demenz, Rogers, Schulz von Thun, Validation",
            isClinicalPicture = false,
            detailDepth = DetailDepth.STANDARD,
            priority = PriorityLevel.HIGH
        ),
        DefaultPreparationTemplate(
            title = "Atopische Dermatitis (Neurodermitis)",
            examDayTag = "Tag 1",
            competenceFocusCodes = "I.1, I.5, I.6, II.1, IV",
            categoryShortCode = "Tag 1",
            keywordsCsv = "Atopische Dermatitis, Neurodermitis, Haut, Epidermis, Dermis, Subkutis, Anatomie, Physiologie, Juckreiz, Hautbarriere, Basispflege",
            isClinicalPicture = true,
            detailDepth = DetailDepth.EXAM_DEEP,
            priority = PriorityLevel.HIGH,
            customPromptNote = "Krankheitsbild: Anatomie und Physiologie der Haut unbedingt einbeziehen."
        ),
        DefaultPreparationTemplate(
            title = "Entwicklung (nach Erikson)",
            examDayTag = "Tag 1",
            competenceFocusCodes = "I.1, I.5, I.6, II.1, IV",
            categoryShortCode = "Tag 1",
            keywordsCsv = "Erikson, Entwicklungsaufgaben, psychosoziale Entwicklung, Lebensspanne, Urvertrauen, Autonomie, Identität, Integrität",
            isClinicalPicture = false,
            detailDepth = DetailDepth.STANDARD,
            priority = PriorityLevel.HIGH
        ),
        DefaultPreparationTemplate(
            title = "Herzinsuffizienz",
            examDayTag = "Tag 1",
            competenceFocusCodes = "I.1, I.5, I.6, II.1, IV",
            categoryShortCode = "Tag 1",
            keywordsCsv = "Herzinsuffizienz, Herz, Herzkreislauf, Anatomie, Physiologie, Linksherzinsuffizienz, Rechtsherzinsuffizienz, NYHA, Ödeme, Dyspnoe, Bilanzierung",
            isClinicalPicture = true,
            detailDepth = DetailDepth.EXAM_DEEP,
            priority = PriorityLevel.HIGH,
            customPromptNote = "Krankheitsbild: Anatomie und Physiologie des Herz-Kreislauf-Systems unbedingt einbeziehen."
        ),
        DefaultPreparationTemplate(
            title = "Konflikteskalation (Glasl)",
            examDayTag = "Tag 1",
            competenceFocusCodes = "I.1, I.5, I.6, II.1, IV",
            categoryShortCode = "Tag 1",
            keywordsCsv = "Konflikteskalation, Friedrich Glasl, 9 Stufen, Win-Win, Win-Lose, Lose-Lose, Deeskalation, Konfliktlösung",
            isClinicalPicture = false,
            detailDepth = DetailDepth.STANDARD,
            priority = PriorityLevel.MEDIUM
        ),
        DefaultPreparationTemplate(
            title = "Laienpflege / Professionelle Pflege",
            examDayTag = "Tag 1",
            competenceFocusCodes = "I.1, I.5, I.6, II.1, IV",
            categoryShortCode = "Tag 1",
            keywordsCsv = "Laienpflege, professionelle Pflege, Pflegefachperson, Angehörige, Abgrenzung, Zusammenarbeit, Pflegeverständnis",
            isClinicalPicture = false,
            detailDepth = DetailDepth.STANDARD,
            priority = PriorityLevel.MEDIUM
        ),
        DefaultPreparationTemplate(
            title = "Leben im (Familien-) Alltag",
            examDayTag = "Tag 1",
            competenceFocusCodes = "I.1, I.5, I.6, II.1, IV",
            categoryShortCode = "Tag 1",
            keywordsCsv = "Familienalltag, Lebensgestaltung, Alltagskompetenz, Häuslichkeit, Ressourcen, Teilhabe, Autonomie",
            isClinicalPicture = false,
            detailDepth = DetailDepth.STANDARD,
            priority = PriorityLevel.MEDIUM
        ),
        DefaultPreparationTemplate(
            title = "NANDA Pflegediagnosen (allgemein)",
            examDayTag = "Tag 1",
            competenceFocusCodes = "I.1, I.5, I.6, II.1, IV",
            categoryShortCode = "Tag 1",
            keywordsCsv = "NANDA, Pflegediagnosen, PESR, Problem, Etiology, Symptom, Ressource, Risikopflegediagnose, Gesundheitsförderungsdiagnose",
            isClinicalPicture = false,
            detailDepth = DetailDepth.EXAM_DEEP,
            priority = PriorityLevel.HIGH
        ),
        DefaultPreparationTemplate(
            title = "SGB V und SGB XI",
            examDayTag = "Tag 1",
            competenceFocusCodes = "I.1, I.5, I.6, II.1, IV",
            categoryShortCode = "Tag 1",
            keywordsCsv = "SGB V, SGB XI, Krankenversicherung, Pflegeversicherung, Behandlungspflege, Grundpflege, Pflegegrade, Leistungen, NBA",
            isClinicalPicture = false,
            detailDepth = DetailDepth.EXAM_DEEP,
            priority = PriorityLevel.HIGH
        ),
        DefaultPreparationTemplate(
            title = "Soziale Rolle",
            examDayTag = "Tag 1",
            competenceFocusCodes = "I.1, I.5, I.6, II.1, IV",
            categoryShortCode = "Tag 1",
            keywordsCsv = "Soziale Rolle, Rollenwechsel, Rollenkonflikt, Identität, Patientenrolle, Angehörige, soziale Teilhabe",
            isClinicalPicture = false,
            detailDepth = DetailDepth.STANDARD,
            priority = PriorityLevel.MEDIUM
        ),
        DefaultPreparationTemplate(
            title = "S3-Leitlinie",
            examDayTag = "Tag 1",
            competenceFocusCodes = "I.1, I.5, I.6, II.1, IV",
            categoryShortCode = "Tag 1",
            keywordsCsv = "S3-Leitlinie, AWMF, Evidenzbasierung, Empfehlungsgrade, Leitlinienentwicklung, Abgrenzung Expertenstandard",
            isClinicalPicture = false,
            detailDepth = DetailDepth.STANDARD,
            priority = PriorityLevel.MEDIUM
        ),
        DefaultPreparationTemplate(
            title = "Pflegebedürftigkeit",
            examDayTag = "Tag 1",
            competenceFocusCodes = "I.1, I.5, I.6, II.1, IV",
            categoryShortCode = "Tag 1",
            keywordsCsv = "Pflegebedürftigkeit, § 14 SGB XI, Pflegegrade 1-5, Begutachtungsinstrument, NBA, Selbstständigkeit, Module",
            isClinicalPicture = false,
            detailDepth = DetailDepth.EXAM_DEEP,
            priority = PriorityLevel.HIGH
        ),
        DefaultPreparationTemplate(
            title = "Pflegende Angehörige / Belastung",
            examDayTag = "Tag 1",
            competenceFocusCodes = "I.1, I.5, I.6, II.1, IV",
            categoryShortCode = "Tag 1",
            keywordsCsv = "Pflegende Angehörige, Überlastung, Entlastung, Verhinderungspflege, Kurzzeitpflege, Pflegestützpunkt, Rollenüberlastung",
            isClinicalPicture = false,
            detailDepth = DetailDepth.STANDARD,
            priority = PriorityLevel.HIGH
        ),
        DefaultPreparationTemplate(
            title = "Qualitätssicherung",
            examDayTag = "Tag 1",
            competenceFocusCodes = "I.1, I.5, I.6, II.1, IV",
            categoryShortCode = "Tag 1",
            keywordsCsv = "Qualitätssicherung, Strukturqualität, Prozessqualität, Ergebnisqualität, Donabedian, PDCA, MD, Pflegevisite",
            isClinicalPicture = false,
            detailDepth = DetailDepth.STANDARD,
            priority = PriorityLevel.MEDIUM
        ),

        // ================= TAG 2: I.2, II.2, IV.1 =================
        DefaultPreparationTemplate(
            title = "Beratung (auch kollegial)",
            examDayTag = "Tag 2",
            competenceFocusCodes = "I.2, II.2, IV.1",
            categoryShortCode = "Tag 2",
            keywordsCsv = "Beratung, kollegiale Beratung, Anleitung, Schulung, Information, Beratungskonzept, 6 Phasen",
            isClinicalPicture = false,
            detailDepth = DetailDepth.EXAM_DEEP,
            priority = PriorityLevel.HIGH
        ),
        DefaultPreparationTemplate(
            title = "Corbin-Strauss",
            examDayTag = "Tag 2",
            competenceFocusCodes = "I.2, II.2, IV.1",
            categoryShortCode = "Tag 2",
            keywordsCsv = "Corbin, Strauss, Verlaufskurvenmodell, Trajectory, chronische Krankheit, Krankheitsverlauf, Phasen",
            isClinicalPicture = false,
            detailDepth = DetailDepth.EXAM_DEEP,
            priority = PriorityLevel.HIGH
        ),
        DefaultPreparationTemplate(
            title = "Ernährung Expertenstandard",
            examDayTag = "Tag 2",
            competenceFocusCodes = "I.2, II.2, IV.1",
            categoryShortCode = "Tag 2",
            keywordsCsv = "Expertenstandard Ernährungsmanagement, DNQP, Mangelernährung, Screening, Assessment, PEMU, BMI, Flüssigkeitsbedarf",
            isClinicalPicture = false,
            detailDepth = DetailDepth.EXAM_DEEP,
            priority = PriorityLevel.HIGH
        ),
        DefaultPreparationTemplate(
            title = "Expertenstandards allgemein",
            examDayTag = "Tag 2",
            competenceFocusCodes = "I.2, II.2, IV.1",
            categoryShortCode = "Tag 2",
            keywordsCsv = "Expertenstandard, DNQP, Strukturkriterien, Prozesskriterien, Ergebniskriterien, Implementierung, Pflegequalität",
            isClinicalPicture = false,
            detailDepth = DetailDepth.STANDARD,
            priority = PriorityLevel.HIGH
        ),
        DefaultPreparationTemplate(
            title = "Gesundheitsförderung",
            examDayTag = "Tag 2",
            competenceFocusCodes = "I.2, II.2, IV.1",
            categoryShortCode = "Tag 2",
            keywordsCsv = "Gesundheitsförderung, Salutogenese, Antonovsky, Kohärenzgefühl, Ottawa-Charta, Empowerment, Ressourcen",
            isClinicalPicture = false,
            detailDepth = DetailDepth.STANDARD,
            priority = PriorityLevel.HIGH
        ),
        DefaultPreparationTemplate(
            title = "Lymphödem",
            examDayTag = "Tag 2",
            competenceFocusCodes = "I.2, II.2, IV.1",
            categoryShortCode = "Tag 2",
            keywordsCsv = "Lymphödem, Lymphsystem, Lymphgefäße, Lymphknoten, Anatomie, Physiologie, KPE, Kompression, Lymphdrainage, Stemmer-Zeichen",
            isClinicalPicture = true,
            detailDepth = DetailDepth.EXAM_DEEP,
            priority = PriorityLevel.HIGH,
            customPromptNote = "Krankheitsbild: Anatomie und Physiologie des Lymphsystems unbedingt einbeziehen."
        ),
        DefaultPreparationTemplate(
            title = "Mamma CA",
            examDayTag = "Tag 2",
            competenceFocusCodes = "I.2, II.2, IV.1",
            categoryShortCode = "Tag 2",
            keywordsCsv = "Mamma CA, Mammakarzinom, Brustkrebs, Brustdrüse, Anatomie, Physiologie, Vorsorge, Mammographie, Operation, Nachsorge, Körperbild",
            isClinicalPicture = true,
            detailDepth = DetailDepth.EXAM_DEEP,
            priority = PriorityLevel.HIGH,
            customPromptNote = "Krankheitsbild: Anatomie und Physiologie der Brustdrüse & Lymphabflusswege einbeziehen."
        ),
        DefaultPreparationTemplate(
            title = "(Pflege bei) Chemotherapie",
            examDayTag = "Tag 2",
            competenceFocusCodes = "I.2, II.2, IV.1",
            categoryShortCode = "Tag 2",
            keywordsCsv = "Chemotherapie, Zytostatika, Zellzyklus, Knochenmark, Physiologie, Mukositis, Übelkeit, Infektionsrisiko, Paravasat, Alopezie",
            isClinicalPicture = true,
            detailDepth = DetailDepth.EXAM_DEEP,
            priority = PriorityLevel.HIGH,
            customPromptNote = "Krankheitsbild/Therapie: Physiologische Wirkung auf sich schnell teilende Zellen (Blutbildung, Schleimhaut) einbeziehen."
        ),
        DefaultPreparationTemplate(
            title = "Pflegeforschung",
            examDayTag = "Tag 2",
            competenceFocusCodes = "I.2, II.2, IV.1",
            categoryShortCode = "Tag 2",
            keywordsCsv = "Pflegeforschung, Evidence-based Nursing, EBN, PICO, qualitative Forschung, quantitative Forschung, Gütekriterien, Forschungsprozess",
            isClinicalPicture = false,
            detailDepth = DetailDepth.STANDARD,
            priority = PriorityLevel.MEDIUM
        ),
        DefaultPreparationTemplate(
            title = "Prävention",
            examDayTag = "Tag 2",
            competenceFocusCodes = "I.2, II.2, IV.1",
            categoryShortCode = "Tag 2",
            keywordsCsv = "Prävention, Primärprävention, Sekundärprävention, Tertiärprävention, Quartärprävention, Verhaltensprävention, Verhältnisprävention",
            isClinicalPicture = false,
            detailDepth = DetailDepth.STANDARD,
            priority = PriorityLevel.HIGH
        ),
        DefaultPreparationTemplate(
            title = "Rheumatoide Arthritis",
            examDayTag = "Tag 2",
            competenceFocusCodes = "I.2, II.2, IV.1",
            categoryShortCode = "Tag 2",
            keywordsCsv = "Rheumatoide Arthritis, Gelenke, Synovialmembran, Knorpel, Anatomie, Physiologie, Morgensteifigkeit, Schub, Gelenkschutz, Schmerz",
            isClinicalPicture = true,
            detailDepth = DetailDepth.EXAM_DEEP,
            priority = PriorityLevel.HIGH,
            customPromptNote = "Krankheitsbild: Anatomie und Physiologie des Gelenkaufbaus und der Entzündungskaskade einbeziehen."
        ),
        DefaultPreparationTemplate(
            title = "Sturz (-prophylaxe)",
            examDayTag = "Tag 2",
            competenceFocusCodes = "I.2, II.2, IV.1",
            categoryShortCode = "Tag 2",
            keywordsCsv = "Sturzprophylaxe, Sturz, Expertenstandard, intrinsische Risikofaktoren, extrinsische Risikofaktoren, Hilfsmittel, Mobilisation, Umgebung",
            isClinicalPicture = false,
            detailDepth = DetailDepth.EXAM_DEEP,
            priority = PriorityLevel.HIGH
        ),
        DefaultPreparationTemplate(
            title = "Vorsorge",
            examDayTag = "Tag 2",
            competenceFocusCodes = "I.2, II.2, IV.1",
            categoryShortCode = "Tag 2",
            keywordsCsv = "Vorsorge, Früherkennung, Krebsvorsorge, Gesundheitsuntersuchung, Impfung, U-Untersuchungen, Screening",
            isClinicalPicture = false,
            detailDepth = DetailDepth.STANDARD,
            priority = PriorityLevel.MEDIUM
        ),

        // ================= TAG 3: I.3, I.4, II.3, III.2 =================
        DefaultPreparationTemplate(
            title = "Ethische Prinzipien / Dilemma",
            examDayTag = "Tag 3",
            competenceFocusCodes = "I.3, I.4, II.3, III.2",
            categoryShortCode = "Tag 3",
            keywordsCsv = "Ethische Prinzipien, Beauchamp Childress, Autonomie, Nicht-Schaden, Fürsorge, Gerechtigkeit, ethisches Dilemma, Entscheidungsfindung",
            isClinicalPicture = false,
            detailDepth = DetailDepth.EXAM_DEEP,
            priority = PriorityLevel.HIGH
        ),
        DefaultPreparationTemplate(
            title = "Fallbesprechung",
            examDayTag = "Tag 3",
            competenceFocusCodes = "I.3, I.4, II.3, III.2",
            categoryShortCode = "Tag 3",
            keywordsCsv = "Fallbesprechung, ethische Fallbesprechung, Nimwegener Modell, Verstehen, Interaktion, Moderation, multiprofessionell",
            isClinicalPicture = false,
            detailDepth = DetailDepth.STANDARD,
            priority = PriorityLevel.MEDIUM
        ),
        DefaultPreparationTemplate(
            title = "Hypoxischer Hirnschaden",
            examDayTag = "Tag 3",
            competenceFocusCodes = "I.3, I.4, II.3, III.2",
            categoryShortCode = "Tag 3",
            keywordsCsv = "Hypoxischer Hirnschaden, Gehirn, Neuronen, Sauerstoffversorgung, Anatomie, Physiologie, Hypoxie, Wachkoma, Apallisches Syndrom, Basale Stimulation",
            isClinicalPicture = true,
            detailDepth = DetailDepth.EXAM_DEEP,
            priority = PriorityLevel.HIGH,
            customPromptNote = "Krankheitsbild: Anatomie und Physiologie des Gehirns sowie zerebraler Sauerstoffmangel einbeziehen."
        ),
        DefaultPreparationTemplate(
            title = "Handeln im Notfall",
            examDayTag = "Tag 3",
            competenceFocusCodes = "I.3, I.4, II.3, III.2",
            categoryShortCode = "Tag 3",
            keywordsCsv = "Notfall, Notfallmanagement, ABCDE-Schema, Vitalzeichen, Physiologie, Bewusstlosigkeit, Schock, Notruf, Erstmaßnahmen",
            isClinicalPicture = true,
            detailDepth = DetailDepth.EXAM_DEEP,
            priority = PriorityLevel.HIGH,
            customPromptNote = "Klinisch: Physiologie von Atmung, Kreislauf und Bewusstsein (ABCDE) einbeziehen."
        ),
        DefaultPreparationTemplate(
            title = "Lungenembolie",
            examDayTag = "Tag 3",
            competenceFocusCodes = "I.3, I.4, II.3, III.2",
            categoryShortCode = "Tag 3",
            keywordsCsv = "Lungenembolie, Lunge, Lungenkreislauf, Pulmonalarterie, Anatomie, Physiologie, Dyspnoe, Thoraxschmerz, Tachykardie, Oberkörperhochlagerung",
            isClinicalPicture = true,
            detailDepth = DetailDepth.EXAM_DEEP,
            priority = PriorityLevel.HIGH,
            customPromptNote = "Krankheitsbild: Anatomie und Physiologie von Lunge und kleinem Kreislauf unbedingt einbeziehen."
        ),
        DefaultPreparationTemplate(
            title = "Nosokomiale Infektion",
            examDayTag = "Tag 3",
            competenceFocusCodes = "I.3, I.4, II.3, III.2",
            categoryShortCode = "Tag 3",
            keywordsCsv = "Nosokomiale Infektion, Hygiene, KRINKO, Händedesinfektion, Immunsystem, Infektionskette, Physiologie, MRSA, Katheterinfektion, Isolierung",
            isClinicalPicture = true,
            detailDepth = DetailDepth.EXAM_DEEP,
            priority = PriorityLevel.HIGH,
            customPromptNote = "Klinisch: Physiologie der Infektabwehr & Eintrittspforten einbeziehen."
        ),
        DefaultPreparationTemplate(
            title = "Palliativpflege",
            examDayTag = "Tag 3",
            competenceFocusCodes = "I.3, I.4, II.3, III.2",
            categoryShortCode = "Tag 3",
            keywordsCsv = "Palliativpflege, Palliative Care, Symptomkontrolle, Schmerz, Luftnot, Sterbephase, Mundpflege, Angehörige, Hospiz",
            isClinicalPicture = false,
            detailDepth = DetailDepth.EXAM_DEEP,
            priority = PriorityLevel.HIGH
        ),
        DefaultPreparationTemplate(
            title = "Patientenverfügung",
            examDayTag = "Tag 3",
            competenceFocusCodes = "I.3, I.4, II.3, III.2",
            categoryShortCode = "Tag 3",
            keywordsCsv = "Patientenverfügung, Vorsorgevollmacht, Betreuung, mutmaßlicher Wille, BGB, Verbindlichkeit, Therapiebegrenzung",
            isClinicalPicture = false,
            detailDepth = DetailDepth.STANDARD,
            priority = PriorityLevel.HIGH
        ),
        DefaultPreparationTemplate(
            title = "Pflege bei Fieber",
            examDayTag = "Tag 3",
            competenceFocusCodes = "I.3, I.4, II.3, III.2",
            categoryShortCode = "Tag 3",
            keywordsCsv = "Fieber, Thermoregulation, Hypothalamus, Physiologie, Fieberanstieg, Fieberhöhe, Fieberabfall, Wadenwickel, Flüssigkeitszufuhr",
            isClinicalPicture = true,
            detailDepth = DetailDepth.EXAM_DEEP,
            priority = PriorityLevel.HIGH,
            customPromptNote = "Klinisch: Physiologie der Thermoregulation und Fieberphasen unbedingt einbeziehen."
        ),
        DefaultPreparationTemplate(
            title = "Pflegeethik",
            examDayTag = "Tag 3",
            competenceFocusCodes = "I.3, I.4, II.3, III.2",
            categoryShortCode = "Tag 3",
            keywordsCsv = "Pflegeethik, ICN-Ethikkodex, Menschenwürde, Ethikkomitee, Verantwortung, moralischer Stress, Schweigepflicht",
            isClinicalPicture = false,
            detailDepth = DetailDepth.STANDARD,
            priority = PriorityLevel.HIGH
        ),
        DefaultPreparationTemplate(
            title = "Reanimation (auch Kinder)",
            examDayTag = "Tag 3",
            competenceFocusCodes = "I.3, I.4, II.3, III.2",
            categoryShortCode = "Tag 3",
            keywordsCsv = "Reanimation, BLS, ERC-Leitlinien, Herzdruckmassage, Beatmung, Kinderreanimation, Säugling, Anatomie, Physiologie, AED",
            isClinicalPicture = true,
            detailDepth = DetailDepth.EXAM_DEEP,
            priority = PriorityLevel.HIGH,
            customPromptNote = "Klinisch: Anatomische/physiologische Unterschiede zwischen Erwachsenen und Kindern berücksichtigen."
        ),
        DefaultPreparationTemplate(
            title = "Schenkelhalsfraktur",
            examDayTag = "Tag 3",
            competenceFocusCodes = "I.3, I.4, II.3, III.2",
            categoryShortCode = "Tag 3",
            keywordsCsv = "Schenkelhalsfraktur, Femur, Hüftgelenk, Knochen, Anatomie, Physiologie, Außenrotation, Beinverkürzung, TEP, Mobilisation, Luxationsprophylaxe",
            isClinicalPicture = true,
            detailDepth = DetailDepth.EXAM_DEEP,
            priority = PriorityLevel.HIGH,
            customPromptNote = "Krankheitsbild: Anatomie und Physiologie von Hüftgelenk und Femur-Durchblutung einbeziehen."
        ),
        DefaultPreparationTemplate(
            title = "Thrombose",
            examDayTag = "Tag 3",
            competenceFocusCodes = "I.3, I.4, II.3, III.2",
            categoryShortCode = "Tag 3",
            keywordsCsv = "Thrombose, TVT, Venen, Venenklappen, Blutgerinnung, Virchow-Trias, Anatomie, Physiologie, Thromboseprophylaxe, MTS, Mobilisation",
            isClinicalPicture = true,
            detailDepth = DetailDepth.EXAM_DEEP,
            priority = PriorityLevel.HIGH,
            customPromptNote = "Krankheitsbild: Anatomie der Venen/Venenklappen und Physiologie der Gerinnung (Virchow-Trias) einbeziehen."
        ),
        DefaultPreparationTemplate(
            title = "Umgang mit Sondenkost",
            examDayTag = "Tag 3",
            competenceFocusCodes = "I.3, I.4, II.3, III.2",
            categoryShortCode = "Tag 3",
            keywordsCsv = "Sondenkost, PEG, Magensonde, Verdauungstrakt, Anatomie, Physiologie, enterale Ernährung, Hygiene, Medikamentengabe, Aspiration",
            isClinicalPicture = true,
            detailDepth = DetailDepth.EXAM_DEEP,
            priority = PriorityLevel.HIGH,
            customPromptNote = "Klinisch: Anatomie und Physiologie des oberen Gastrointestinaltrakts einbeziehen."
        ),
        DefaultPreparationTemplate(
            title = "Wundmanagement",
            examDayTag = "Tag 3",
            competenceFocusCodes = "I.3, I.4, II.3, III.2",
            categoryShortCode = "Tag 3",
            keywordsCsv = "Wundmanagement, Haut, Wundheilungsphasen, Anatomie, Physiologie, chronische Wunde, Expertenstandard, Asepsis, Verbandwechsel, Exsudat",
            isClinicalPicture = true,
            detailDepth = DetailDepth.EXAM_DEEP,
            priority = PriorityLevel.HIGH,
            customPromptNote = "Klinisch: Anatomie der Haut und Physiologie der Wundheilungsphasen einbeziehen."
        )
    )

    /**
     * Komplette Operatorenliste der Landes-AG Schriftliches Examen auf Grundlage von Jann Wegner
     * (Überarbeitung Dezember 2025) – inklusive Taxonomie (WV/ASB), Beispielfrage, Erwartungshorizont,
     * Kommentar (K) und Bepunktung.
     */
    val defaultOperators: List<DefaultOperatorTemplate> = listOf(
        // -------- Teil A: Wissen und Verstehen (WV) --------
        DefaultOperatorTemplate(
            name = "Nennen",
            requirementLevel = "Taxonomie WV",
            description = "Fakten, Begriffe oder Merkmale gezielt und ohne weitere Ausformulierung aufzählen.",
            exampleQuestion = "Nennen Sie 3 Zeichen einer Infektion.",
            expectationHorizon = "Rubor, Dolor, Tumor (Stichpunkte genügen; geforderte Anzahl exakt beachten).",
            commentNotes = "K1: Es gibt mehr als 3 Entzündungszeichen. Sie müssen aber nur 3 nennen.\nK2: Sie können deutsche Begriffe verwenden oder griechische/lateinische.\nK3: Sind 3 Nennungen gefordert, aber 5 werden genannt, zählen nur die ersten 3 Nennungen.",
            gradingRubric = "Pro richtige Nennung einen Punkt.",
            caseRelationRule = CaseRelationRule.OPTIONAL_CASE_RELATION,
            questionPattern = "Nennen Sie 3 {aspekt} zu {thema}.",
            structureType = OperatorStructureType.FACTS_LIST,
            defaultPoints = 3
        ),
        DefaultOperatorTemplate(
            name = "Beschreiben (statisch)",
            requirementLevel = "Taxonomie WV",
            description = "Etwas Feststehendes (Statisches), z. B. ein Bild oder einen Befund, objektiv in ganzen Sätzen darstellen.",
            exampleQuestion = "Beschreiben Sie die Wunde (auf dem Bild)!",
            expectationHorizon = "Die Wunde am rechten Unterschenkel ist schätzungsweise 10 cm lang und etwa 5 cm breit. Die Wundumgebung ist gerötet und geschwollen. Der Wundrand ist wulstig. (Hinweis: Ab diesem Operator müssen ganze Sätze formuliert werden!)",
            commentNotes = "K1: Sie beschreiben in einer Klausur nach einem Foto. Das ist kein Wundassessment, keine Analyse!\nHinweis: Ab diesem Operator müssen ganze Sätze formuliert werden!",
            gradingRubric = "Pro Beschreibung 2 Punkte.",
            caseRelationRule = CaseRelationRule.OPTIONAL_CASE_RELATION,
            questionPattern = "Beschreiben Sie (in ganzen Sätzen) {aspekt} bei {thema}.",
            structureType = OperatorStructureType.DEFINITION,
            defaultPoints = 2
        ),
        DefaultOperatorTemplate(
            name = "Beschreiben (dynamisch / Vorgang)",
            requirementLevel = "Taxonomie WV",
            description = "Etwas Dynamisches (Bewegliches) oder einen (wiederkehrenden) Vorgang in chronologisch richtiger Reihenfolge und ganzen Sätzen darstellen.",
            exampleQuestion = "Beschreiben Sie den korrekten Ablauf einer Blutzuckermessung.",
            expectationHorizon = "Vorbereitung (Utensilien, Patienteninformation, Händedesinfektion, unsterile Handschuhe) -> Durchführung (Teststreifen, Händewaschen/Finger stechen, ggf. 2. Tropfen verwenden, Messung) -> Nachbereitung (Dokumentation der Werte).",
            commentNotes = "K1: Der Ablauf folgt einer festen Grundstruktur.\nK2: Im Lehrbuch/Unterrichtsmaterial kann ein etwas anderer Ablauf stehen – orientieren Sie sich immer an Ihrem schulischen Material!",
            gradingRubric = "Im Beispiel 6 Punkte: 2 Punkte Vorbereitung, 2 Punkte Durchführung, 2 Punkte Nachbereitung (= pro Beschreibung 2 Punkte). Vollständige Punktzahl nur bei korrekter Reihenfolge.",
            caseRelationRule = CaseRelationRule.OPTIONAL_CASE_RELATION,
            questionPattern = "Beschreiben Sie den korrekten Ablauf bei {thema} in richtiger Reihenfolge.",
            structureType = OperatorStructureType.PROCESS_PLANNING,
            defaultPoints = 6
        ),
        DefaultOperatorTemplate(
            name = "Definieren",
            requirementLevel = "Taxonomie WV",
            description = "Einen Fachbegriff oder ein Krankheitsbild allgemeingültig, präzise und ohne Fallbezug bestimmen.",
            exampleQuestion = "Definieren Sie Diabetes mellitus.",
            expectationHorizon = "Diabetes mellitus (Zuckerkrankheit) ist ein Überbegriff für verschiedene Erkrankungen des Stoffwechsels. Allen gemeinsam (Typ 1 und Typ 2) ist, dass sie zu erhöhten Blutzuckerwerten führen, weil ein Insulinmangel und/oder eine verminderte Insulinwirkung vorliegt.",
            commentNotes = "K1: Bei dieser Frage kann niemals ein Fallbezug hergestellt werden, denn eine Definition ist immer allgemeingültig!",
            gradingRubric = "Pro Definition 2 Punkte.",
            caseRelationRule = CaseRelationRule.NO_CASE_RELATION,
            questionPattern = "Definieren Sie {thema} allgemeingültig.",
            structureType = OperatorStructureType.DEFINITION,
            defaultPoints = 2
        ),
        DefaultOperatorTemplate(
            name = "Erklären",
            requirementLevel = "Taxonomie WV",
            description = "Wirkungszusammenhänge, physiologische Abläufe oder Gesetzmäßigkeiten verständlich und allgemeingültig (ohne Fallbezug) darlegen.",
            exampleQuestion = "Erklären Sie den Einfluss der Einnahme eines oralen Glukokortikoids auf den Blutzuckerwert.",
            expectationHorizon = "Oral verabreichte Glukokortikoide erhöhen den Blutzucker, weil sie die Insulinsekretion der Bauchspeicheldrüse bremsen und die Glukoseaufnahme in die Skelettmuskulatur verringern.",
            commentNotes = "K1: Gut erklärt ist, was andere Menschen gut verstehen können.\nK2: Bei dieser Frage kann niemals ein Fallbezug hergestellt werden.",
            gradingRubric = "Pro Erklärung 2 Punkte.",
            caseRelationRule = CaseRelationRule.NO_CASE_RELATION,
            questionPattern = "Erklären Sie die Zusammenhänge bei {thema} ({aspekt}).",
            structureType = OperatorStructureType.CAUSAL_REASONING,
            defaultPoints = 2
        ),
        DefaultOperatorTemplate(
            name = "Begründen",
            requirementLevel = "Taxonomie WV",
            description = "Eine Aussage oder Regel allgemeingültig (ohne Fallbezug) mithilfe fachlicher Ursache-Wirkungs-Zusammenhänge stützen.",
            exampleQuestion = "Begründen Sie, warum verschiedene orale Medikamente nicht alle auf einmal verabreicht werden sollen.",
            expectationHorizon = "Verschiedene orale Medikamente müssen getrennt voneinander verabreicht werden (z. B. vor oder nach dem Essen), da sonst Gefahr von Interaktionen besteht und die Wirksamkeit verändert wird.",
            commentNotes = "K1: Bei dieser Frage kann niemals ein Fallbezug hergestellt werden.",
            gradingRubric = "Pro Begründung 2 Punkte.",
            caseRelationRule = CaseRelationRule.NO_CASE_RELATION,
            questionPattern = "Begründen Sie fachlich die Bedeutung oder Regel zu {aspekt} bei {thema}.",
            structureType = OperatorStructureType.CAUSAL_REASONING,
            defaultPoints = 2
        ),
        DefaultOperatorTemplate(
            name = "Erläutern",
            requirementLevel = "Taxonomie WV",
            description = "Einen allgemeingültigen Sachverhalt erklären/begründen und zusätzlich durch ein Beispiel oder weiterführende Informationen aufwerten.",
            exampleQuestion = "Erläutern Sie die Bedeutung der Wundversorgung nach Expertenstandard für Patienten mit chronischen Wunden.",
            expectationHorizon = "Fachliche Erklärung/Begründung (warum es bedeutsam ist) + konkretes veranschaulichendes Beispiel (ohne zwingenden Fallbezug).",
            commentNotes = "K1: Die Erläuterung beinhaltet häufig auch eine Erklärung oder Begründung. Sie wird aber mit einem Beispiel oder zusätzlichen Informationen aufgewertet.\nK2: Bei dieser Frage muss kein Fallbezug hergestellt werden, sie ist allgemein gehalten.",
            gradingRubric = "Pro Erläuterung 3 Punkte (2 Punkte für die Erklärung und ein Punkt für das Beispiel).",
            caseRelationRule = CaseRelationRule.NO_CASE_RELATION,
            questionPattern = "Erläutern Sie die Bedeutung von {aspekt} bei {thema} anhand eines Beispiels.",
            structureType = OperatorStructureType.CAUSAL_REASONING,
            defaultPoints = 3
        ),
        DefaultOperatorTemplate(
            name = "Beschriften Sie",
            requirementLevel = "Taxonomie WV",
            description = "Anatomische Darstellungen oder Zeichnungen korrekt mit Fach- oder deutschen Bezeichnungen beschriften.",
            exampleQuestion = "Beschriften Sie die anatomische Darstellung/die Zeichnung.",
            expectationHorizon = "Anatomische Struktur (z. B. Verdauungstrakt, Herz, Haut, Gelenk) anhand der Platzhalter korrekt mit deutschen oder griechisch/lateinischen Bezeichnungen zuordnen.",
            commentNotes = "K1: Die Auszubildenden können auf Deutsch oder Griechisch/Latein beschriften. Es sei denn, die Aufgabe fordert explizit die griechischen/lateinischen Namen.",
            gradingRubric = "Pro Beschriftung einen Punkt.",
            caseRelationRule = CaseRelationRule.NO_CASE_RELATION,
            questionPattern = "Beschriften/Benennen Sie die zentralen anatomischen Strukturen zu {thema}.",
            structureType = OperatorStructureType.FACTS_LIST,
            defaultPoints = 4
        ),

        // -------- Teil B: Analyse, Synthese, Bewertung (ASB) --------
        DefaultOperatorTemplate(
            name = "Erläutern (mit Fallbezug)",
            requirementLevel = "Taxonomie ASB",
            description = "Einen Zusammenhang, Vergleich oder eine Notwendigkeit konkret auf die vorliegende Fallsituation bezogen schlüssig und vollständig erläutern.",
            exampleQuestion = "Erläutern Sie die Notwendigkeit sturzprophylaktischer Maßnahmen bei Frau Treig.",
            expectationHorizon = "Fallbezogene Erläuterung: Warum sind bestimmte Maßnahmen/Zusammenhänge genau bei dieser Person notwendig? (Richtigkeit, Vollständigkeit, Schlüssigkeit und Aufbau/Struktur).",
            commentNotes = "K1: Ein Fallbezug ist generell möglich, dann Taxonomie ASB.\nK2: ASB wenn Zusammenhänge hergestellt, verglichen oder bewertet werden müssen.",
            gradingRubric = "Pro Erläuterung mit Fallbezug 4 Punkte (da ASB -> Richtigkeit, Vollständigkeit, Schlüssigkeit und Aufbau/Struktur).",
            caseRelationRule = CaseRelationRule.REQUIRED_CASE_RELATION,
            questionPattern = "Erläutern Sie mit Bezug auf den Fall die Notwendigkeit von {aspekt} bei {thema}.",
            structureType = OperatorStructureType.CAUSAL_REASONING,
            defaultPoints = 4
        ),
        DefaultOperatorTemplate(
            name = "Analysieren",
            requirementLevel = "Taxonomie ASB",
            description = "Bestimmte Faktoren (z. B. Risikofaktoren) gezielt aus der Fallsituation identifizieren und tabellarisch/strukturiert mit Textbeleg darstellen.",
            exampleQuestion = "Analysieren Sie 2 Sturzrisikofaktoren für Herrn Hallmackenreuter (aus der Fallsituation).",
            expectationHorizon = "Konkrete benannte Befunde direkt aus der Fallsituation mit Zeilen-/Quellenbezug (im Layout kenntlich gemacht, z. B. tabellarisch).",
            commentNotes = "K1: Die Risikofaktoren werden direkt in der Fallsituation identifiziert.\nK2: Wird im Layout kenntlich gemacht durch eine Tabelle.",
            gradingRubric = "Pro Analyse 2 Punkte (z. B. für 2 Faktoren 4 Punkte).",
            caseRelationRule = CaseRelationRule.REQUIRED_CASE_RELATION,
            questionPattern = "Analysieren Sie 2 zentrale Risikofaktoren/Aspekte für die Person im Fall zu {thema}.",
            structureType = OperatorStructureType.FACTS_LIST,
            defaultPoints = 4
        ),
        DefaultOperatorTemplate(
            name = "Analysieren (im Fließtext)",
            requirementLevel = "Taxonomie ASB",
            description = "Den Gesamtzustand oder eine komplexe Lage im Fließtext (Einleitungssatz, Hauptteil strikt aus Beobachtung/Fall, Schlusssatz) analysieren.",
            exampleQuestion = "Analysieren Sie den Gesundheitszustand von Herrn Friedrich.",
            expectationHorizon = "Einleitungssatz („Im Folgenden analysiere ich...“) -> Hauptteil im Fließtext mit konkreten Fallangaben/Messwerten (kein bloßes Nacherzählen!) -> Schlusssatz („Der Gesundheitszustand ist aktuell reduziert/stabil...“).",
            commentNotes = "K1: Analyse mithilfe der Fallsituation.\nK2: Die Antwort wird im Fließtext geschrieben.\nK3: Sie schreiben nur auf, was Sie gelesen/gesehen/gehört/gemessen und eingeschätzt haben. Das ist mehr als eine Beschreibung!",
            gradingRubric = "Insgesamt 6 Punkte (1 Punkt Einleitung, 4 Punkte [da ASB -> Richtigkeit, Vollständigkeit, Schlüssigkeit und Aufbau/Struktur] Analyse, 1 Punkt Schluss).",
            caseRelationRule = CaseRelationRule.REQUIRED_CASE_RELATION,
            questionPattern = "Analysieren Sie im Fließtext die aktuelle Situation/Gesundheitslage im Fall zu {thema}.",
            structureType = OperatorStructureType.EVALUATION,
            defaultPoints = 6
        ),
        DefaultOperatorTemplate(
            name = "Stellung nehmen",
            requirementLevel = "Taxonomie ASB",
            description = "Zu einer Aussage oder Quelle in Einleitung, fachlich begründetem Hauptteil und Schlusssatz mit eigener, fachlich begründeter Meinung Position beziehen.",
            exampleQuestion = "Nehmen Sie Stellung zur Aussage von Dr. Bissig („Irgendwann werden alle alten Leute dement!“).",
            expectationHorizon = "Einleitungssatz („Im Folgenden nehme ich Stellung zu...“) -> Hauptteil mit fachlicher Einordnung/Evidenz -> Schlusssatz inklusive begründeter eigener Meinung (z. B. „nicht / nur zum Teil richtig“).",
            commentNotes = "K1: Ein Fallbezug kann hergestellt werden (ergibt sich aus der Fragestellung).\nK2: Antworten im Erwartungshorizont sind beispielhaft.\nK3: Hier schreiben Auszubildende ihre eigene Meinung und begründen diese fachlich.",
            gradingRubric = "Insgesamt 6 Punkte (1 Punkt Einleitung, 4 Punkte [da ASB -> Richtigkeit, Vollständigkeit, Schlüssigkeit und Aufbau/Struktur] Analyse, 1 Punkt Schluss inklusive eigene Meinung).",
            caseRelationRule = CaseRelationRule.OPTIONAL_CASE_RELATION,
            questionPattern = "Nehmen Sie fachlich begründet Stellung zu folgender Aussage rund um {thema}.",
            structureType = OperatorStructureType.EVALUATION,
            defaultPoints = 6
        ),
        DefaultOperatorTemplate(
            name = "Diskutieren (Erörtern)",
            requirementLevel = "Taxonomie ASB",
            description = "Eine Fragestellung strukturiert abwägen: Einleitungssatz, mindestens je ein Pro- und ein Kontra-Argument sowie ein begründetes Fazit.",
            exampleQuestion = "Diskutieren Sie, ob eine Impfpflicht gegen das Coronavirus für Pflegepersonal eingeführt werden soll.",
            expectationHorizon = "Einleitungssatz -> Einerseits Pro-Argument(e) -> Andererseits Kontra-Argument(e) -> Schlusssatz mit Fazit/Schlussfolgerung (auch begründete Enthaltung möglich).",
            commentNotes = "K1: Mindestens ein Pro-Argument und ein Kontra-Argument darlegen.\nK2: Schlussfolgerung darf auch sein, sich keiner Position anzuschließen, falls noch zu wenig Wissen vorliegt.\nK3: Fallbezug ist je nach Frage möglich, aber nicht immer zwingend.",
            gradingRubric = "Insgesamt 6 Punkte (1 Punkt Einleitung, 2 Punkte Pro-Argument, 2 Punkte Kontra-Argument [da ASB -> Richtigkeit, Vollständigkeit, Schlüssigkeit und Aufbau/Struktur], 1 Punkt Schluss mit Fazit).",
            caseRelationRule = CaseRelationRule.OPTIONAL_CASE_RELATION,
            questionPattern = "Diskutieren Sie Chancen und Grenzen/Risiken bei {thema}.",
            structureType = OperatorStructureType.COMPARISON,
            defaultPoints = 6
        ),
        DefaultOperatorTemplate(
            name = "Entwickeln Sie (z. B. Hypothese)",
            requirementLevel = "Taxonomie ASB",
            description = "Aus der Fallsituation heraus plausible Hypothesen (mit möglichem Grund, ohne ausführliche Begründung) oder Konzepte ableiten.",
            exampleQuestion = "Entwickeln Sie 2 Hypothesen, warum Herr Demel gestürzt ist.",
            expectationHorizon = "Konkrete fallbezogene Hypothesen mit wahrscheinlichem Auslöser aus dem Fall (z. B. ‚...wahrscheinlich gestürzt, weil laut Einfuhrprotokoll wenig getrunken‘; ‚...vermutlich Kreislaufprobleme/Schwindel‘).",
            commentNotes = "K1: Hier muss ein Fallbezug hergestellt werden!\nK2: Hypothesen müssen einen Grund enthalten (z. B. zu wenig getrunken), aber nicht begründet sein.",
            gradingRubric = "Pro Hypothese 2 Punkte (im Beispiel 4 Punkte).",
            caseRelationRule = CaseRelationRule.REQUIRED_CASE_RELATION,
            questionPattern = "Entwickeln Sie 2 fallbezogene Hypothesen zu {aspekt} bei {thema}.",
            structureType = OperatorStructureType.CAUSAL_REASONING,
            defaultPoints = 4
        ),
        DefaultOperatorTemplate(
            name = "Formulieren Sie (Pflegeplan / Ablaufplan)",
            requirementLevel = "Taxonomie ASB",
            description = "Eine vollständige Pflegeplanung nach dem Pflegeprozessmodell (PESR-Pflegeproblem, SMART-Ziel, detaillierte W-Fragen-Maßnahmen) für den Fall formulieren.",
            exampleQuestion = "Formulieren Sie eine Pflegeplanung nach dem Pflegeprozessmodell für Frau Renger.",
            expectationHorizon = "Problem/Ressource (P-E-S-R) | Ziel (SMART: Spezifisch, Messbar, Attraktiv/Akzeptiert, Realistisch, Terminiert) | Maßnahmen/Interventionen (Wer macht was, wann, wie oft, womit, wo...).",
            commentNotes = "K1: Hier muss ein Fallbezug hergestellt werden.",
            gradingRubric = "Probleme: Risikopflegediagnosen 2 P., vorgegebenes ‚P‘ 3 P., Komplettformulierung 4 P. | Ziele: 2 P. pro SMART-Ziel | Maßnahmen: 2 P. pro Maßnahme.",
            caseRelationRule = CaseRelationRule.REQUIRED_CASE_RELATION,
            questionPattern = "Formulieren Sie eine fallbezogene Pflegeplanung (PESR, SMART, Maßnahmen) zu {thema}.",
            structureType = OperatorStructureType.PROCESS_PLANNING,
            defaultPoints = 8
        ),
        DefaultOperatorTemplate(
            name = "Formulieren Sie (eine Pflegemaßnahme)",
            requirementLevel = "Taxonomie ASB",
            description = "Eine konkrete, fallbezogene Pflegemaßnahme präzise formulieren und zwingend fachlich begründen.",
            exampleQuestion = "Formulieren Sie eine sturzprophylaktische Pflegemaßnahme für Frau Renge.",
            expectationHorizon = "Konkrete Handlung der Pflegefachperson für die genannte Person + direkt angeschlossene Begründung (‚..., um ... zu erreichen/verhindern‘).",
            commentNotes = "K1: Hier muss ein Fallbezug hergestellt werden.\nK2: Die Formulierung einer einzelnen Pflegemaßnahme erfordert immer eine Begründung.",
            gradingRubric = "Pro formulierter Pflegemaßnahme 2 Punkte. Wenn Begründung fehlt: Ein Punkt.",
            caseRelationRule = CaseRelationRule.REQUIRED_CASE_RELATION,
            questionPattern = "Formulieren Sie eine konkrete Pflegemaßnahme inkl. Begründung für die Fallperson zu {thema}.",
            structureType = OperatorStructureType.PROCESS_PLANNING,
            defaultPoints = 2
        ),
        DefaultOperatorTemplate(
            name = "Beurteilen Sie...",
            requirementLevel = "Taxonomie ASB",
            description = "Eine Situation, Handlung oder Aussage im Fall anhand eines fachlichen oder rechtlichen Bewertungskriteriums (z. B. Gesetz, Leitlinie, Expertenstandard) beurteilen.",
            exampleQuestion = "Beurteilen Sie die Aussage von Pflegefachmann Heinz Dörr („Mörsere alle Tabletten gemeinsam...“).",
            expectationHorizon = "Einleitungssatz (fachlich richtig/falsch) -> Hauptteil mit konkretem Bewertungskriterium/Richtlinie/Gesetz (hier ist KEINE eigene Meinung gefragt!) -> Schlusssatz mit begründetem fachlichem Urteil.",
            commentNotes = "K1: Bei Urteilsfragen muss immer ein Fallbezug hergestellt werden.\nK2: Eine Beurteilung benötigt ein Bewertungskriterium/Richtlinie etc., hier ist keine eigene Meinung gefragt.",
            gradingRubric = "Insgesamt 6 Punkte (1 Punkt Einleitung, 4 Punkte [da ASB -> Richtigkeit, Vollständigkeit, Schlüssigkeit und Aufbau/Struktur] Analyse, 1 Punkt Schluss inklusive begründetem Urteil).",
            caseRelationRule = CaseRelationRule.REQUIRED_CASE_RELATION,
            questionPattern = "Beurteilen Sie anhand fachlicher/rechtlicher Kriterien eine Handlung oder Aussage im Fall zu {thema}.",
            structureType = OperatorStructureType.EVALUATION,
            defaultPoints = 6
        )
    )
}

@Serializable
data class SourceCitation(
    val sourceId: Long,
    val sourceTitle: String,
    val pageLabel: String,
    val sectionHeading: String,
    val excerpt: String
) {
    fun shortLabel(): String {
        val parts = buildList {
            add(sourceTitle)
            if (pageLabel.isNotBlank()) add(pageLabel)
            if (sectionHeading.isNotBlank() && sectionHeading != "Allgemein") add(sectionHeading)
        }
        return parts.joinToString(" · ")
    }
}

@Serializable
data class StudyNotePoint(
    val statement: String,
    val citation: SourceCitation
)

@Serializable
data class StudyNoteSection(
    val parameterTitle: String,
    val bullets: List<StudyNotePoint>
)

@Serializable
data class AnswerEvaluationResult(
    val matchedPoints: List<String>,
    val missingPoints: List<String>,
    val coveragePercent: Int
)

data class RawPage(
    val pageNumber: Int?,
    val text: String
)

data class ChunkInput(
    val chunkId: Long,
    val sourceId: Long,
    val sourceTitle: String,
    val sourceType: SourceType,
    val pageNumber: Int?,
    val sectionHeading: String,
    val content: String
) {
    fun pageLabel(): String = when {
        sourceType == SourceType.IMAGE_OCR && pageNumber != null -> "Scan #$pageNumber"
        pageNumber != null -> "S. $pageNumber"
        else -> ""
    }
}

data class ClassifiedSentence(
    val text: String,
    val roles: Set<SentenceRole>,
    val isListItem: Boolean
)

data class RetrievedPassage(
    val chunk: ChunkInput,
    val score: Double,
    val matchedTerms: Set<String>,
    val classifiedSentences: List<ClassifiedSentence>
)

sealed interface RetrievalOutcome {
    data object NoSources : RetrievalOutcome
    data class NoMatches(
        val searchedQueryTerms: List<String>,
        val searchedSourceCount: Int
    ) : RetrievalOutcome
    data class Found(val passages: List<RetrievedPassage>) : RetrievalOutcome
}

data class PrepGenerationConfig(
    val prepItemId: Long,
    val title: String,
    val categoryOrCompetenceArea: String,
    val keywords: List<String>,
    val detailDepth: DetailDepth,
    val focusParameters: List<String>,
    val targetFlashcardCount: Int,
    val flashcardTypes: Set<FlashcardType>,
    val selectedSourceIds: Set<Long>,
    val customPromptNote: String
)

data class OperatorPromptSpec(
    val operatorId: Long,
    val name: String,
    val requirementLevel: String,
    val description: String,
    val questionPattern: String,
    val structureType: OperatorStructureType,
    val defaultPoints: Int,
    val exampleQuestion: String = "",
    val expectationHorizon: String = "",
    val commentNotes: String = "",
    val gradingRubric: String = "",
    val caseRelationRule: CaseRelationRule = CaseRelationRule.OPTIONAL_CASE_RELATION
)
