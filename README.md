# PflegeExamen Lokal (Android · Kotlin / Jetpack Compose)

Kostenlose Lernapp für die schriftliche Examensprüfung in der generalistischen Pflegeausbildung.

## Lernen und Smartphone-KI

- Die Navigation konzentriert sich auf **Heute**, **Themen**, **Karten** und **Falltraining**. Quellen und Operatoren werden ausschließlich mit `tools/PflegeBundleStudio.html` bearbeitet und als `.pflegebundle.zip` in den App-Einstellungen importiert.
- Auf kompatiblen Android-Geräten nutzt die App standardmäßig **Gemini Nano über Android AICore / ML Kit GenAI**. Die Generierung erfolgt auf dem Gerät ohne API-Schlüssel; der erste Modelldownload benötigt Internet und genügend Speicherplatz. Verfügbarkeit und Download können in den Einstellungen geprüft werden.
- Nicht jedes Smartphone unterstützt Gemini Nano. Wenn es nicht verfügbar ist oder die KI-Antwort fehlschlägt, kann die Offline-Engine nur dann Material erstellen, wenn passende Texte über ein Lernpaket importiert wurden. Ohne Modell und ohne passende Texte gibt die App einen Hinweis aus; sie kann kein Fachwissen aus dem Nichts erzeugen.
- KI-generierte Inhalte sind **keine Quellenbelege** und können fachliche Fehler enthalten. Vor der Prüfung und insbesondere vor der Anwendung in der Pflege stets mit verlässlichen Fachquellen und aktuellen Standards abgleichen.
- Die optionalen externen Anbieter bleiben in den Einstellungen verfügbar. Dabei können Inhalte an den jeweiligen Anbieter übertragen werden.

---

## Funktionsumfang

1. **Eigene Quellen (ausschließlich über das HTML-Studio)**
   - **PDF-Import:** Seitenweise Textextraktion via `pdfbox-android` (inkl. automatischem Fallback auf Android `PdfRenderer` + Offline-OCR bei eingescannten PDFs).
   - **Word-Import (`.docx`):** Lokaler Parser für Absätze, Überschriften, Aufzählungen und Tabellen.
   - **Text & Markdown (`.txt`, `.md`) & Direkteingabe:** Schneller Import eigener Mitschriften.



2. **Vorbereitungsliste Frühjahr 2027 & Lernkategorien (`PreparationScreen`, `SettingsScreen`)**
   - Vorbefüllt mit allen **42 offiziellen Prüfungsthemen (Vorbereitungsliste Frühjahr 2027)**, strukturiert nach **Tag 1** (`I.1, I.5, I.6, II.1, IV`), **Tag 2** (`I.2, II.2, IV.1`) und **Tag 3** (`I.3, I.4, II.3, III.2`).
   - Alle **Krankheitsbilder** tragen automatisch **Anatomie & Physiologie** als ersten Lern- und Frageparameter neben Pathophysiologie, Symptomen, Pflegemaßnahmen, Begründungen und Cave – komplett editierbar, filterbar und bereit für deine Quellen.
   - **Lernzettel (`StudyNotesScreen`) & Karteikarten (`FlashcardsScreen`):** Strukturierte Markdown-Lernzettel mit anklickbaren Quellenbelegen sowie 5 Karteikartentypen mit **SM-2 Spaced Repetition**.

3. **Operatorenliste nach Landes-AG / Jann Wegner (Überarbeitung Dezember 2025, `OperatorsScreen`)**
   - Vorbefüllt mit allen **17 offiziellen Operatoren** aus **Teil A (Taxonomie WV)** und **Teil B (Taxonomie ASB)** sowie anatomischer Beschriftung – jeweils inkl. *Beispielfrage*, *Erwartungshorizont*, *Kommentar (K1–K3)*, *Fallbezugs-Regel* (allgemeingültig vs. Fallbezug Pflicht) und *Bepunktungsschema*. Die Einträge werden im HTML-Studio bearbeitet und über ein Paket importiert.

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
   - **Optionales Pseudonym:** Ein Benutzername kann in den Einstellungen für das Ranking eingerichtet werden.
   - **Stündlicher Abgleich ohne laufende Kosten:** Nativer Android-`JobScheduler` (60-Minuten-Takt) + stündlicher App-Check senden ausschließlich `{ userId, username, totalXp, streakDays, rankTitle }` und laden den vorberechneten Stunden-Snapshot (offline gepuffert).
   - **Fertiges 0-€-Backend (`cloudflare-ranking/`):** Cloudflare Worker + D1 SQLite Free Tier + Cron (`0 * * * *`) + optionaler automatischer `leaderboard.json`-Export zu GitHub Pages (inkl. Workflow `.github/workflows/deploy-ranking-worker.yml`). Einrichtung in ca. 5 Minuten ohne Kreditkarte – Details siehe `cloudflare-ranking/README.md`.

---

## Nutzung & Installation auf Android

### Option A: Direkt als APK installieren
Unter **GitHub Actions → „Build Android APK“ → Artifacts** (`PflegeExamenLokal-debug-apk`) oder unter **Releases** kann die fertig kompilierte Debug-APK (`PflegeExamenLokal-debug.apk`) direkt heruntergeladen und auf dem Android-Gerät installiert werden.

### Option B: In Android Studio öffnen & selbst bauen
- Voraussetzungen: Android Studio, **JDK 17**, **Android SDK Platform 35** (`compileSdk = 35`, `minSdk = 26`).
- Auf der Kommandozeile:
  ```bash
  ./gradlew testDebugUnitTest assembleDebug
  ```
  Die kompilierte APK liegt anschließend unter `app/build/outputs/apk/debug/app-debug.apk`.

### Option C: Automatischer APK-Build über GitHub Actions (`.github/workflows/android-apk.yml`)
1. Bei jedem Push oder Pull Request auf `main` baut **Actions → „Build Android APK“** automatisch die App, führt alle Unit-Tests aus und stellt `PflegeExamenLokal-debug-apk` als Artifact bereit.
2. Beim Pushen eines Tags (z. B. `git tag v2.0.0 && git push origin v2.0.0`) wird zusätzlich automatisch ein GitHub Release mit der APK erstellt.
