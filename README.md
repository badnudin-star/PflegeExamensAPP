> **Version 3 (Arbeitsstand):** Für alle 42 festen Themen sind Lernzettel in drei Längen aus den bereitgestellten PDFs im Offline-Katalog enthalten. Der Text wurde aus PDFs extrahiert und nicht vollständig fachlich oder auf Übertragungsfehler geprüft. Karteikarten, Fallbeispiele und MC-Fragen sind noch nicht mitgeliefert. Automatische Updates, tägliche Erzeugung und Quiz-Rangliste sind noch nicht produktiv eingerichtet. Alte Lernpunkte werden nicht hochgeladen. Planung: [cloudflare-content/README.md](cloudflare-content/README.md). Die nachfolgende Dokumentation beschreibt den älteren Stand und nicht die aktuell erreichbare Oberfläche.

# PflegeExamen Lokal (Android · Kotlin / Jetpack Compose)

Kostenlose Lernapp für die schriftliche Examensprüfung in der generalistischen Pflegeausbildung.

## Lernen und Smartphone-KI

- Die Navigation konzentriert sich auf **Heute**, **Themen**, **Karten** und **Falltraining**. Die Prüfungsthemen sind fest vorgegeben und können nicht erstellt, gelöscht oder durch Paketimporte ersetzt werden. Bestehende Lernstände und ältere eigene Themen bleiben gespeichert.
- Quellen können direkt in der App über **Quellen** importiert oder weiterhin mit `tools/PflegeBundleStudio.html` als Paket eingespielt werden. Die App sucht beim Öffnen eines Themas und beim Erstellen von Lernmaterial passende Textabschnitte in allen importierten Quellen. Die Zuordnung ist ein automatischer Vorschlag und sollte geprüft werden; die Themen bleiben fest vorgegeben. Operatoren werden weiterhin über das HTML-Studio verwaltet.
- Die App liest Originaldateien nur und speichert extrahierten Text in ihrer eigenen Datenbank. Auch beim Löschen des App-Eintrags bleibt die Ursprungsdatei erhalten. Ein Bundle-Backup enthält extrahierten Text, keine originalgetreue Kopie der PDF-/DOCX-Dateien. OCR oder PDF-Extraktion können vom Original abweichen.
- Anatomie und Physiologie sind kein eigener Themenbereich: Sie sind Teil der passenden Krankheitsbilder. Entsprechende Skripte können diesen Themen zugeordnet werden.
- Auf kompatiblen Android-Geräten nutzt die App standardmäßig **Gemini Nano über Android AICore / ML Kit GenAI**. Die Generierung erfolgt auf dem Gerät ohne API-Schlüssel; der erste Modelldownload benötigt Internet und genügend Speicherplatz. Verfügbarkeit und Download können in den Einstellungen geprüft werden.
- Im Smartphone-KI-Modus sind eigene Quellen erforderlich. Die Suche wählt je nach Detailtiefe höchstens 8, 14 oder 24 passende Abschnitte aus allen Quellen. Die Smartphone-KI bewertet deren überprüfbare Einzelsätze in mehreren kleinen Anfragen; akzeptiert werden nur gültige Satznummern. Anschließend erstellt die lokale regelbasierte Engine daraus Lernzettel, Karten und fallbezogene Übungen. Die KI formuliert diese Materialien dabei **nicht selbst frei aus**.
- Nur unverändert im extrahierten Quellentext auffindbare Sätze werden als Grundlage verwendet, jeweils mit Herkunft und gegebenenfalls Seitenzahl. Nicht eindeutig zuordenbare oder zu lange Sätze werden ausgelassen. Ungültige KI-Antworten oder Modellfehler brechen den Vorgang ohne Speichern ab. Bei mehr als 32 Teilschritten wird um eine engere Auswahl gebeten.
- **Keine Vollständigkeits- oder Korrektheitsgarantie:** Suche und KI können wichtige Stellen übersehen; Einzelstellen können Kontext verlieren, OCR kann Fehler enthalten, Vorlagen und Fallbezüge können unpassend sein. Inhalte und Quellenzusammenhang vor der Prüfung und Anwendung anhand aktueller Fachquellen überprüfen. Bereits früher erstellte Lernmaterialien bleiben unverändert und sollten neu erstellt werden.
- Nicht jedes Smartphone unterstützt Gemini Nano. Ist es nicht verfügbar, kann man in den Einstellungen stattdessen den Offline-Modus für regelbasierte Erstellung aus passenden Quellen wählen; die KI-Auswahl entfällt dann.
- Die optionalen externen Anbieter bleiben in den Einstellungen verfügbar. Dabei können Inhalte an den jeweiligen Anbieter übertragen werden.

---

## Vorgaben aus QuellenExamen

Die Prüfungsthemen und Kompetenzbeschreibungen orientieren sich an der bereitgestellten Vorbereitungsliste Frühjahr 2027 (Seiten 1–2). Die Beschreibungen stehen in der Themenansicht hinter den Kompetenzkürzeln; die Liste ordnet die Kompetenzen jeweils einem ganzen Themenblock zu. Krankheitsbilder enthalten Anatomie und Physiologie als Schwerpunkt. Die 17 Operatorenvarianten entsprechen den bereitgestellten Fotos der Operatorenliste. Andere Operatoren bleiben in bestehenden Daten erhalten, werden für neue Fallaufgaben aber nicht angeboten; zulässige Operatoren lassen sich weiterhin über das HTML-Studio bearbeiten.

**Quellenbasis noch nicht vollständig:** Die beiden bereitgestellten iCare-PDFs waren für die verwendete Drive-Schnittstelle zu groß und konnten nicht ausgewertet werden. Es wurden keine vollständigen Lehrbücher oder fachlichen Quellen in diese öffentliche Codebasis kopiert; importiere berechtigte Quellen direkt auf dem Gerät. Für die vollständige Auswertung werden kleinere Teil-PDFs mit nachvollziehbaren Seitenangaben benötigt. Es kann daher noch keine vollständige Themenabdeckung zugesichert werden.

## Große Dateien

PDFs werden mit temporärem Speicher verarbeitet und seitenweise ausgelesen; Word-Dateien werden stromweise gelesen. Sehr große oder beschädigte Dateien werden mit einem Hinweis abgelehnt, statt unvollständig gespeichert zu werden: maximal 256 MiB für ein PDF, 12 MiB für Textdateien und 8 Millionen Zeichen extrahierten Text. Gescanntes PDF: maximal 40 Seiten pro Datei für die OCR; größere Scans bitte vor dem Import aufteilen. Lernpakete sind auf 64 MiB entpackten Inhalt begrenzt. Das Original wird nicht verändert. Die tatsächliche Grenze kann je nach Gerätespeicher niedriger sein.

## Funktionsumfang

1. **Eigene Quellen (direkt in der App oder über das HTML-Studio)**
   - **PDF-Import:** Seitenweise Textextraktion via `pdfbox-android` (inkl. automatischem Fallback auf Android `PdfRenderer` + Offline-OCR bei eingescannten PDFs).
   - **Word-Import (`.docx`):** Lokaler Parser für Absätze, Überschriften, Aufzählungen und Tabellen.
   - **Text & Markdown (`.txt`, `.md`) & Direkteingabe:** Schneller Import eigener Mitschriften.



2. **Vorbereitungsliste Frühjahr 2027 & Lernkategorien (`PreparationScreen`, `SettingsScreen`)**
   - Vorbefüllt mit allen **42 offiziellen Prüfungsthemen (Vorbereitungsliste Frühjahr 2027)**, strukturiert nach **Tag 1** (`I.1, I.5, I.6, II.1, IV`), **Tag 2** (`I.2, II.2, IV.1`) und **Tag 3** (`I.3, I.4, II.3, III.2`).
   - Alle **Krankheitsbilder** tragen automatisch **Anatomie & Physiologie** als ersten Lern- und Frageparameter neben Pathophysiologie, Symptomen, Pflegemaßnahmen, Begründungen und Cave – fest vorgegeben, filterbar und bereit für zugeordnete Quellen.
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
