# Privater Lernzettelimport aus Google Drive

Das Werkzeug übernimmt genau die 126 einzelnen PDFs aus dem freigegebenen Drive-Ordner `QuellenExamen/Lernzettel`: 42 fest zugeordnete Themen × Kurz, Ausgeglichen (in der App „Ausgewogen“) und Ausführlich. Es extrahiert Text einschließlich Quellen-/Seitenhinweisen; die PDFs im Drive bleiben unverändert. Zwei Gesamtbände werden ignoriert. Es findet **keine** KI-Generierung und keine fachliche Prüfung statt. Vor Nutzung insbesondere medizinische Aktualität und Quellen prüfen.

**Wichtig:** Bislang ist noch kein vollständiger Import mit echten PDFs erfolgt. Dieses Skript verwendet nicht die Arena-Drive-Verbindung: Der automatisierte Lauf braucht gesonderte Google-Application-Default-Credentials mit Leserecht für den Ordner. Nutzungs-/Veröffentlichungsrechte müssen vor Weitergabe von APK oder Inhalten geklärt sein. Eine öffentliche Worker-URL macht die Inhalte öffentlich lesbar; das öffentliche Git-Repository ebenfalls. Keine Zugangsdaten oder PDFs in Git oder im Chat ablegen.

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

Das Skript kann in einem privaten, vertrauenswürdigen geplanten Job gestartet werden. Der Job müsste vor jedem Lauf den aktuellen vollständigen Katalog laden, den neuen Snapshot prüfen und ihn erst dann mit höherer Revision an den Worker übermitteln. Der Worker erlaubt derzeit öffentlichen Lesezugriff und darf für private Inhalte so **nicht** verwendet werden. Kein Job, keine Drive-Automatisierung und kein Worker wurden durch dieses Werkzeug eingerichtet. Die App-URL ist weiterhin leer; stündliche Prüfungen sind auch nach Einrichtung durch Android nur Best-Effort.

Tests ohne Drive-Zugriff: `python -m unittest discover -s tools/learning_import -v`.
