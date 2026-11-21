# Lernzettelimport aus Google Drive

Die laufende Synchronisation verwendet `QuellenExamen/Lernzettel/Schriftliches Examen`. Sie ordnet 37 Paare (`<Thema>_Neu.pdf` und `Einfach_<Thema>.pdf`) explizit den 42 festen Themen zu. Kombinierte Dokumente werden für zusammengehörige Themen verwendet. Die Quellen im Drive bleiben unverändert; keine Generierung oder fachliche Prüfung findet statt. PDF-Text wird extrahiert; Abbildungen und komplexe Tabellen werden nicht originalgetreu übernommen. Quellen, medizinische Aktualität und Nutzungsrechte vor Verwendung bzw. öffentlicher Weitergabe prüfen.

`LEARNING_DRIVE_FOLDER_ID` bleibt die ID des **übergeordneten** Ordners `Lernzettel`. Der Import sucht darin den eindeutig benannten Unterordner `Schriftliches Examen`. Fehlende, doppelte, unbekannte oder während des Imports veränderte Dateien brechen die Veröffentlichung ab. Der vollständige Serverkatalog ist die Basis; Karten, Fälle und Quiz bleiben erhalten. Die beiden Varianten verwenden rückwärtskompatibel `detailed` (Neu) und `short` (Einfach), `balanced` enthält ebenfalls Neu; `formatVersion: 2` kennzeichnet den neuen Inhalt. Alte App-Versionen können daher weiterhin lesen. Neue Apps zeigen alte Offline-Lernzettel nicht fälschlich als neue Versionen an. Der GitHub-APK-Build lädt und validiert den aktuellen öffentlichen Katalog und bindet ihn als Offline-Stand ein. Bei ungültigem, unvollständigem oder veraltetem Serverstand bricht der Build ab, statt stillschweigend eine APK ohne neue Lernzettel auszuliefern. Lokale Builds ohne diesen optionalen Schritt enthalten weiter den zuletzt im Repository gebündelten Stand und benötigen zunächst eine Aktualisierung.

## Einrichtung

- Google Drive API im eigenen Projekt aktivieren und dem Dienstkonto Leserecht für den Lernzettel-Ordner geben.
- `LEARNING_DRIVE_CREDENTIALS` und `CONTENT_PUBLISH_TOKEN` als GitHub-Actions-Secrets hinterlegen; niemals im Repository, in der App oder im Chat ablegen.
- Variablen: `LEARNING_DRIVE_FOLDER_ID` (Elternordner) und `CONTENT_WORKER_ORIGIN` (öffentliche HTTPS-Origin ohne Pfad). `CONTENT_URL` kann optional die öffentliche `/content`-URL für den APK-Build überschreiben; ansonsten verwendet der Workflow die eingerichtete Worker-URL als Standard.
- Worker, Datenbank und Token gemäß `cloudflare-content/README.md` betreiben. Der Inhaltsendpunkt ist öffentlich, nicht privat. Jede Veröffentlichung muss zur öffentlichen Verbreitung freigegeben sein.
- Nach Änderungen Workflow `Lernzettel aktualisieren` manuell oder planmäßig ausführen. Es wird nur bei Änderungen mit Revisions- und Konfliktschutz veröffentlicht. Eine neue APK bauen und installieren; bestehende APKs ohne Inhaltsadresse erhalten keine Aktualisierungen.

Tests: `python -m unittest discover -s tools/learning_import -v`. Neue PDF-Inhalte und Darstellung vor Weitergabe prüfen; insbesondere Textreihenfolge, Tabellen und eingebettete Abbildungen können bei der Extraktion verloren gehen.

## Offline-Katalog im lokalen Build aktualisieren

Vor einem lokalen Build kann `python tools/learning_import/bundle_published.py --url https://pflegeexamen-content.badnudin.workers.dev/content` ausgeführt werden. Das ersetzt die Asset-Datei im lokalen Arbeitsverzeichnis nach Validierung atomar; es ändert weder Google Drive noch den Server. Den Asset-Stand nur nach Prüfung und unter Beachtung der Veröffentlichungsrechte ins öffentliche Repository übernehmen. Der GitHub-Build macht dies ausschließlich im temporären Runner-Arbeitsverzeichnis.
