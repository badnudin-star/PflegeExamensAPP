# PflegeExamen Lokal (Android · Kotlin / Jetpack Compose)

Kostenlose Lernapp für die **schriftliche Examensprüfung in der generalistischen Pflegeausbildung (Deutschland)** – **100 % Offline im Standard** und optional mit **kostenloser Hybrid-KI (0 €)** ohne Backend- oder Abo-Kosten.

## Kernprinzipien
- **Dauerhaft 0 € Kosten:** Kein kostenpflichtiges Backend, kein Entwickler-API-Key, kein Abo. Im Standard läuft die App **100 % offline** auf deinem Gerät.
- **Optionales Gratis-KI-Hybridmodell (`SettingsScreen`):**
  - **100 % Offline (Standard):** Vollständig lokal ohne Internet.
  - **Groq Free Tier (0 €, ohne Kreditkarte):** Schnelle Ausformulierung über deinen eigenen kostenlosen Groq-Key (`console.groq.com`).
  - **Google Gemini Free Tier (0 €, ohne Kreditkarte):** Nutzung über eigenen AI-Studio-Key (`aistudio.google.com`) inkl. Datenschutzhinweis.
  - **Lokales Ollama (0 €, Heim-WLAN):** Verbindung zu deinem eigenen Rechner/Laptop im lokalen Netzwerk (`OLLAMA_HOST=0.0.0.0`).
  - **Automatischer Offline-Fallback:** Bei fehlendem Netz, Timeout oder Limit übernimmt nahtlos die lokale Engine.
- **Streng quellenbasiert (Zero Hallucination):** Alle Lernzettel, Karteikarten, Fallvignetten und Musterlösungen entstehen **ausschließlich aus deinen eigenen Quellen** und tragen Belege (`SourceCitation`) mit Dokumenttitel, Seiten-/Scan-Nummer, Abschnitt und Originalzitat. Bei KI-Nutzung werden nur lokal ermittelte Belegstellen `[BELEG-X]` übergeben.
- **Zukunftssicher für iOS (KMP-vorbereitet):** Die reine Kotlin-Domain (`domain/model`, `domain/engine`, `domain/ai`) ist plattformunabhängig gehalten, sodass sie später per **Kotlin Multiplatform (KMP)** auch unter iOS (inkl. identischer Gratis-REST-Provider oder Apple Foundation Models ab iOS 26) geteilt werden kann.

---

## Funktionsumfang

1. **Eigene Quellen (`SourcesScreen`)**
   - **PDF-Import:** Seitenweise Textextraktion via `pdfbox-android` (inkl. automatischem Fallback auf Android `PdfRenderer` + Offline-OCR bei eingescannten PDFs).
   - **Word-Import (`.docx`):** Lokaler Parser für Absätze, Überschriften, Aufzählungen und Tabellen.
   - **Text & Markdown (`.txt`, `.md`) & Direkteingabe:** Schneller Import eigener Mitschriften.
   - **Foto-OCR (Kamera & Galerie):** On-Device-Texterkennung via Google ML Kit mit mehrseitigem Korrekturdialog.
   - **Kategorien & Abschnitt-Inspektor:** Zuordnung zu Kompetenzbereichen/Fächern und Einsicht in alle indexierten Textabschnitte.

2. **Vorbereitungsliste Frühjahr 2027 & Lernkategorien (`PreparationScreen`, `SettingsScreen`)**
   - Vorbefüllt mit allen **42 offiziellen Prüfungsthemen (Vorbereitungsliste Frühjahr 2027)**, strukturiert nach **Tag 1** (`I.1, I.5, I.6, II.1, IV`), **Tag 2** (`I.2, II.2, IV.1`) und **Tag 3** (`I.3, I.4, II.3, III.2`).
   - Alle **Krankheitsbilder** tragen automatisch **Anatomie & Physiologie** als ersten Lern- und Frageparameter neben Pathophysiologie, Symptomen, Pflegemaßnahmen, Begründungen und Cave – komplett editierbar, filterbar und bereit für deine Quellen.
   - **Lernzettel (`StudyNotesScreen`) & Karteikarten (`FlashcardsScreen`):** Strukturierte Markdown-Lernzettel mit anklickbaren Quellenbelegen sowie 5 Karteikartentypen mit **SM-2 Spaced Repetition**.

3. **Operatorenliste nach Landes-AG / Jann Wegner (Überarbeitung Dezember 2025, `OperatorsScreen`)**
   - Vorbefüllt mit allen **17 offiziellen Operatoren** aus **Teil A (Taxonomie WV)** und **Teil B (Taxonomie ASB)** sowie anatomischer Beschriftung – jeweils inkl. *Beispielfrage*, *Erwartungshorizont*, *Kommentar (K1–K3)*, *Fallbezugs-Regel* (allgemeingültig vs. Fallbezug Pflicht) und *Bepunktungsschema*. Jeder Eintrag ist frei editierbar, löschbar, per JSON ex-/importierbar und wiederherstellbar.

4. **Externes Einspeise-Tool & feste App-Integration (`tools/PflegeBundleStudio.html` & `.pflegebundle.zip`)**
   - **`tools/PflegeBundleStudio.html`** läuft 100 % offline am PC im Browser und enthält bereits alle 42 Themen (Frühjahr 2027) sowie die 17 Operatoren (Dez. 2025).
   - Eigene Skripte/PDFs/DOCX/Texte per Drag & Drop hinzufügen, Parameter anpassen und als `.pflegebundle.zip` speichern.
   - **Ohne APK-Neubau:** In der App unter *Einstellungen → .pflegebundle.zip laden* direkt importieren (und umgekehrt als Backup exportieren).
   - **Fest in die APK integriert:** Das Archiv nach `app/src/main/assets/seed_bundle/` entpacken (`manifest.json` + `sources/`) und APK bauen – jede App-Installation lädt alle Inhalte beim ersten Start automatisch ein.

5. **Fallbeispiele & „Antwort nach Bestätigung“ (`CaseStudiesScreen`, `CaseStudyPracticeScreen`)**
   - Erzeugt Fallvignette + Operator-Aufgaben aus deinen eigenen Quellen unter Beachtung der exakten Operatorregeln (Fallbezug, Einleitung/Hauptteil/Schluss, P-E-S-R + SMART, Begründungspflicht).
   - **Antwort-Sperre:** Musterlösung & Bewertung werden erst sichtbar, nachdem deine eigene Antwort (oder „Auf Papier bearbeitet“) ausdrücklich bestätigt wurde.
   - **Optionaler Prüfungs-Timer:** Ruhige Stoppuhr oder Countdown (`15 / 30 / 45 Min`), standardmäßig ausgeschaltet.
   - Nach Bestätigung: lokaler Kernpunkt-Abgleich, Quellenbelege und bei aktiver Gratis-KI ein zusätzliches quellenbasiertes Feedback.

6. **Schichtfreundliche Pflege-Gamification (`HomeScreen`, `GamificationEngine`)**
   - **Examens-Rang & XP:** 5 Stufen von *Orientierungseinsatz* bis *Prüfungsreif*.
   - **Streak mit Schicht-Schutz:** Bis zu 2 automatisch regenerierende **„Frei-Tage“**, damit Frühdienst/Spätdienst/Nachtdienst keinen Lern-Streak brechen.
   - **Tagesziel, Examens-Reife (%) & Schwachstellen-Radar** direkt auf dem Startbildschirm.

7. **Benutzername & Stündliches Community-Ranking (0 € · `RankingScreen` & `cloudflare-ranking/`)**
   - **Pflicht-Pseudonym beim ersten Öffnen:** Beim ersten Start vergibt jede*r Lernende einen Benutzernamen (jederzeit in den Einstellungen änderbar oder pausierbar).
   - **Stündlicher Abgleich ohne laufende Kosten:** Nativer Android-`JobScheduler` (60-Minuten-Takt) + stündlicher App-Check senden ausschließlich `{ userId, username, totalXp, streakDays, rankTitle }` und laden den vorberechneten Stunden-Snapshot (offline gepuffert).
   - **Fertiges 0-€-Backend (`cloudflare-ranking/`):** Cloudflare Worker + D1 SQLite Free Tier + Cron (`0 * * * *`) + optionaler automatischer `leaderboard.json`-Export zu GitHub Pages (inkl. Workflow `.github/workflows/deploy-ranking-worker.yml`). Einrichtung in ca. 5 Minuten ohne Kreditkarte – Details siehe `cloudflare-ranking/README.md`.

---

## Nutzung & Installation auf Android

### Option A: Direkt als APK installieren
Im Ordner `apk/` liegt die fertig kompilierte Debug-APK (`apk/PflegeExamenLokal-debug.apk`).

### Option B: In Android Studio öffnen & selbst bauen
- Voraussetzungen: Android Studio, **JDK 17**, **Android SDK Platform 35** (`compileSdk = 35`, `minSdk = 26`).
- Auf der Kommandozeile:
  ```bash
  ./gradlew testDebugUnitTest assembleDebug
  ```

### Option C: Automatischer APK-Build über GitHub Actions (`.github/workflows/android-apk.yml`)
1. Repository auf GitHub anlegen und Inhalt von `PflegeExamenApp/` auf `main` pushen.
2. Unter **Actions → „Build Android APK“** wird die APK automatisch gebaut und als Artifact (`PflegeExamenLokal-debug-apk`) bereitgestellt.
3. Beim Pushen eines Tags (z. B. `git tag v2.0.0 && git push origin v2.0.0`) wird zusätzlich automatisch ein GitHub Release mit der APK erstellt.
