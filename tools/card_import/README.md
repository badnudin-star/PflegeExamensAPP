# Karteikartenimport

Der einmalige Import übernimmt die beiden aktuellen Excel-Dateien aus `QuellenExamen/Karteikarten`: `Karteikarten_30_Themen_Teilstand.xlsx` und `Karteikarten_12_Themen_abgeglichen.xlsx`. Die ältere `Karteikarten_12_Themen_Ergaenzung.xlsx` nicht zusätzlich importieren. Das Skript prüft Themenblätter, Überschriften, laufende Kartennummern und Quellen und übernimmt Frage und Antwort unverändert. Es ist keine medizinische oder rechtliche Prüfung; Aktualität und Freigabe vor Veröffentlichung prüfen. Die Quell-Excel-Dateien werden nicht verändert oder im Repository gespeichert.

```sh
pip install 'openpyxl>=3.1,<4'
python tools/card_import/import_cards.py --directory /pfad/zu/den/heruntergeladenen/dateien \
  --base /pfad/zum/aktuellen/vollstaendigen/katalog.json --output /anderer/pfad/katalog.json
python -m unittest discover -s tools/card_import -v
```

Die Basis muss immer der aktuelle vollständige Katalog sein, damit Lernzettel, Fälle und Quiz erhalten bleiben. Ausgabe und Basis müssen verschiedene Dateien sein. Der Import ersetzt die gesamte Kartenliste und erhöht die Revision. IDs bestehen aus Themen- und laufender Kartennummer: Werden Karten in Excel umnummeriert, ändern sich ihre IDs. Das Skript publiziert nicht automatisch; nach Prüfung kann das Ergebnis für einen APK-Build oder nach entsprechendem Konfliktschutz über den Worker veröffentlicht werden. Beim bestehenden Drive-Lernzettel-Sync bleiben die Karten des Serverkatalogs erhalten – **Kartenänderungen werden dabei nicht automatisch aus Excel eingelesen**. Ein älterer oder bereits höher revisionierter Server-/Cache-Katalog kann den APK-Stand überlagern; bei Einrichtung der Synchronisierung die Karten zuerst in den vollständigen Serverstand übernehmen und eine höhere Revision publizieren.

Ein optionaler geplanter Drive-Abgleich für diese Arbeitsmappen ist auf dem aktuellen Branch vorbereitet; Einrichtung, Identitätsregeln und Grenzen stehen in `tools/learning_import/README.md`. Er ist ohne zusätzliche Konfiguration nicht aktiv.
