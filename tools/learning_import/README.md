# Privater Lernzettelimport aus Google Drive

Das Werkzeug übernimmt genau die 126 einzelnen PDFs aus dem freigegebenen Drive-Ordner `QuellenExamen/Lernzettel`: 42 fest zugeordnete Themen × Kurz, Ausgeglichen (in der App „Ausgewogen“) und Ausführlich. Es extrahiert Text einschließlich Quellen-/Seitenhinweisen; die PDFs im Drive bleiben unverändert. Zwei Gesamtbände werden ignoriert. Es findet **keine** KI-Generierung und keine fachliche Prüfung statt. Vor Nutzung insbesondere medizinische Aktualität und Quellen prüfen.

**Wichtig:** Der mitgelieferte Katalog enthält einen einmaligen Import der 126 Einzel-PDFs. Dieses Skript für spätere Importe verwendet nicht die Arena-Drive-Verbindung: Der automatisierte Lauf braucht gesonderte Google-Application-Default-Credentials mit Leserecht für den Ordner. Nutzungs-/Veröffentlichungsrechte müssen vor Weitergabe von APK oder Inhalten geklärt sein. Eine öffentliche Worker-URL macht die Inhalte öffentlich lesbar; das öffentliche Git-Repository ebenfalls. Keine Zugangsdaten oder PDFs in Git oder im Chat ablegen.

## Privater lokaler APK-Build

1. Python 3, `pip install -r tools/learning_import/requirements.txt` sowie Android SDK/Gradle einrichten. Für ein Google-Dienstkonto einen sicheren ADC-Mechanismus konfigurieren und nur den betreffenden Drive-Ordner für dieses Konto lesbar freigeben; Drive-API im eigenen Projekt aktivieren. Alternativ lokale OAuth-ADC für das eigene Google-Konto konfigurieren. Credentials ausschließlich lokal/als Secret behandeln.
2. Falls noch keine veröffentlichten Karten/Fälle/Quiz vorliegen, kann die vorhandene leere `app/src/main/assets/learning_content.json` als Basis dienen. Für bestehende Daten **immer den aktuellen vollständigen Katalog** als Basis verwenden, andernfalls würden andere Inhaltsarten in der Ausgabe fehlen. Für Updates die Revisionsnummer des aktuellen, auf dem Gerät/Server genutzten Katalogs verwenden.
3. Beispiel (Ausgabe ist durch `.gitignore` geschützt):

   ```sh
   python tools/learning_import/import_notes.py \
     --folder '1Wb-3HR3kaXaxZnQvXsnXklDobDtkvtAJ' \
     --base app/src/main/assets/learning_content.json \
     --output app/src/main/assets/private/learning_content.json
   ./gradlew testDebugUnitTest assembleDebug
   ```

   Die private Datei wird beim lokalen APK-Build gebündelt und bei geeigneter Revision bevorzugt. Ein solcher Build enthält den Lerntext: nur gemäß geklärten Rechten weitergeben. Nach Import und vor Verteilung alle Zuordnungen, Textübernahme und Darstellung (insbesondere Sonderzeichen, Formeln, Tabellen, fehlende Textschichten und Seitenwechsel) prüfen. Das Importskript stoppt bei fehlenden/mehrdeutigen Dateien, ungültigen PDFs, schwankendem Drive-Inhalt, Fehlern oder zu großem Katalog ohne den alten Stand zu überschreiben. Ein reiner Textimport kann Formatierung, Bilder oder Tabellen verlieren.
4. Bei erneutem Import **den letzten vollständigen Katalog als Basis** und eine andere Ausgabedatei verwenden; danach geprüft in den privaten Asset-Pfad übernehmen. Die App wählt stets die höchste verfügbare Revision; eine alte, bereits gespeicherte Remote-Version kann eine ältere private Ausgabe überlagern.

## Spätere Updates

Der Workflow `.github/workflows/sync-learning-content.yml` ist für stündliche Aktualisierung vorbereitet und läuft nach der Einrichtung auf dem Standardbranch. Er lädt den aktuellen Katalog, ersetzt nur Lernzettel und erhält Karten, Fälle und Quiz. Änderungslose Läufe veröffentlichen nichts; bei ungültigen Daten, veränderten PDFs oder konkurrierender Publikation wird abgebrochen. Der Worker ist öffentlich lesbar und daher nicht für private Inhalte geeignet. Die Dateien müssen vor der Veröffentlichung zur Verwendung und öffentlichen Verbreitung freigegeben sowie fachlich geprüft sein.

**Einrichtung (noch nicht erfolgt):**

1. Cloudflare-D1 und Worker gemäß `cloudflare-content/README.md` einrichten, Schema anwenden und ein langes `PUBLISH_TOKEN` ausschließlich als Worker-Secret hinterlegen. Der Dienst ist öffentlich lesbar. Keine Schlüssel in APK, Quellcode oder Chat.
2. Ein Google-Cloud-Dienstkonto mit Drive-API und Leserecht nur für den Lernzettel-Ordner einrichten. Die Zugangsdaten in GitHub Actions als Secret `LEARNING_DRIVE_CREDENTIALS` hinterlegen; nie ins Repository schreiben. Dasselbe Worker-Publikationssecret als `CONTENT_PUBLISH_TOKEN` in GitHub Actions hinterlegen. Die Arena-Drive-Verbindung stellt keinen Dauerzugang für GitHub Actions dar.
3. GitHub-Actions-Variablen setzen: `LEARNING_DRIVE_FOLDER_ID` auf die ID des Ordners, `CONTENT_WORKER_ORIGIN` auf `https://…` ohne Pfad und `CONTENT_URL` auf dieselbe Origin mit `/content`. `CONTENT_URL` ist öffentlich, wird in die APK eingebaut und darf kein Secret enthalten. Der Workflow wird erst aktiv, wenn alle vier Einstellungen vorhanden sind; sonst meldet er das Überspringen. Einen manuellen Lauf starten und Logs/Serverstand prüfen. Das erste Publizieren nutzt den vollständigen gebündelten Katalog als Ausgangsbasis; bei existierendem Server wird ausschließlich dessen validierter Stand als Basis verwendet.
4. Die App nach Setzen der Variable neu bauen. Alternativ lokal `./gradlew assembleDebug -PcontentUrl=https://…/content` ausführen. Bestehende APKs ohne diesen Endpunkt erhalten keine Updates. Im Hintergrund prüft Android ungefähr stündlich, allerdings abhängig von Netzwerk und Energieverwaltung ohne Zeitgarantie. Die Synchronisierung ist ohne eingerichteten Worker/Secrets derzeit nicht aktiv.

Konflikte erfordern einen erneuten Lauf, der den neuesten Katalog erneut lädt. Die Skripte prüfen Struktur und Quellenabschnitte, aber nicht medizinische Richtigkeit, Rechte oder verlustfreie PDF-Extraktion. Fehlerhafte oder unfreigegebene Dokumente dürfen nicht automatisch bereitgestellt werden; Änderungen vor Aufnahme in den Ordner fachlich freigeben. Bei mehreren konkurrierenden Veröffentlichungswegen D1-Backups und Betrieb überwachen.

Tests ohne Drive-Zugriff: `python -m unittest discover -s tools/learning_import -v`.
