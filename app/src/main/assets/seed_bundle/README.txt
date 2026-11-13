PflegeExamen APK Seed-Ordner (`app/src/main/assets/seed_bundle/`)

Wenn du eigene Quellen (PDF, DOCX, TXT, MD, Bilder), Themenlisten, Operatoren und Parameter
FEST in die APK einbauen möchtest:

1. Öffne `PflegeExamenApp/tools/PflegeBundleStudio.html` auf deinem PC im Browser.
2. Ziehe deine Quellen hinein, prüfe/bearbeite Themen & Operatoren und klicke auf
   „.pflegebundle.zip für Handy herunterladen“.
3. Entpacke dieses ZIP in genau diesen Ordner (`app/src/main/assets/seed_bundle/`), sodass hier
   `manifest.json` und `sources/...` liegen.
4. Baue die APK neu (lokal oder über GitHub Actions). Beim ersten App-Start liest die App
   `seed_bundle/manifest.json` automatisch ein und indiziert alle Quellen fest auf dem Gerät.

Alternativ (ohne APK-Neubau):
Lege die `.pflegebundle.zip` direkt aufs Handy und wähle in der App:
Einstellungen -> „.pflegebundle.zip laden“.
