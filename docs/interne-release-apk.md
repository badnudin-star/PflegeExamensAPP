# Signierte interne APK mit GitHub Actions

Der Workflow **Interne signierte APK erstellen** baut nur nach manuellem Start mit Bestätigung und nur vom Branch `main`. Er lädt und prüft den öffentlichen Offline-Katalog, signiert die APK mit dem eigenen Schlüssel, prüft die Signatur und den enthaltenen Katalog und stellt nur die APK für 30 Tage als Actions-Artifact `PflegeExamen-intern-signiert` bereit. Er erstellt kein GitHub Release, veröffentlicht nichts im Play Store und aktiviert keine Rangliste. Das Artifact ist keine Ende-zu-Ende-verschlüsselte Verteilung; prüfe, wer im Repository Zugriff auf Actions und Artifacts hat. Für jede reguläre Update-Version muss `versionCode` erhöht werden.

## Sicherheit und Einrichtung

Die signierte APK enthält ein **öffentliches Zertifikat**, aber nicht den privaten Signaturschlüssel. Der Schlüssel wird in GitHub als Actions-Secret hinterlegt und für den Build kurzzeitig auf dem GitHub-Runner benutzt; er wird nicht ausschließlich auf deinem PC bleiben. Die Workflow-Berechtigungen sind auf `contents: read` begrenzt; die temporäre Schlüsseldatei wird nach dem Build bestmöglich entfernt und nur die APK hochgeladen. Ein fehlendes oder falsches Secret stoppt den Build. GitHub-Runner und berechtigte Personen mit Einfluss auf Workflows/Environment müssen vertrauenswürdig sein: Wer Workflows ändern und mit diesen Secrets ausführen kann, kann sie potenziell auslesen. Bei ungeeigneten Zugriffskontrollen nicht hinterlegen; stattdessen lokal signieren.

1. Auf GitHub im Repository unter **Settings → Environments** ein Environment `internal-release` anlegen. Nach Möglichkeit auf `main` beschränken und eine erforderliche Freigabe durch vertrauenswürdige Personen aktivieren. Ob diese Schutzoptionen verfügbar sind, hängt von Repository und GitHub-Tarif ab. Nur vertrauenswürdige Personen dürfen Branch, Workflow und Environment ändern; das ist die Voraussetzung für das Hinterlegen des Schlüssels.
2. Im Environment unter **Environment secrets** vier Secrets einrichten:
   - `PFLEGE_RELEASE_KEYSTORE_BASE64`: der Base64-kodierte Inhalt der eigenen Schlüsseldatei.
   - `PFLEGE_RELEASE_STORE_PASSWORD`: das Keystore-Passwort.
   - `PFLEGE_RELEASE_KEY_ALIAS`: der beim Anlegen gewählte Alias.
   - `PFLEGE_RELEASE_KEY_PASSWORD`: das Passwort für den Schlüssel (falls identisch, dasselbe Passwort).
3. Die Schlüsseldatei auf dem Linux-PC in Base64 umwandeln, indem du in einem **privaten** Terminal ausführst (Pfad anpassen):

   ```sh
   base64 -w 0 '/home/DEIN-BENUTZERNAME/pflege-release.jks'; echo
   ```

   Die angezeigte lange Zeichenfolge ist genauso geheim wie die Schlüsseldatei. Vollständig und ohne Umbruch als Wert von `PFLEGE_RELEASE_KEYSTORE_BASE64` in GitHub einfügen – **nicht im Chat, in Git oder in einem Screenshot teilen**. Die Zwischenablage/deren Verlauf und Terminalausgabe anschließend nach Möglichkeit löschen bzw. schließen. GitHub-Secrets sind auf 48 KiB begrenzt; der Workflow akzeptiert Schlüsseldateien bis 32 KiB. Die Schlüsseldatei selbst nicht ins Repository oder als Artifact hochladen.
4. Schlüsseldatei und Passwörter weiterhin unabhängig und sicher sichern. Der Eintrag bei GitHub ersetzt keine eigene Sicherung. Vorhandene ältere Android-Backups oder Debug-Installationen werden dadurch nicht angepasst.

## Bauen

Unter **Actions → Interne signierte APK erstellen → Run workflow** den Branch `main` wählen, die Bestätigung aktivieren und den Lauf starten. Nach Erfolg das Artifact herunterladen. Im Log der Signaturprüfung stehen der **öffentliche Zertifikat-Fingerabdruck** und der SHA-256 der APK; der Fingerabdruck kann beim späteren Abgleich helfen und ist kein privater Schlüssel. Die erste mit einem neuen Schlüssel signierte APK kann nicht als Update einer anders signierten Debug-App installiert werden. Nicht vorschnell deinstallieren: Lokaler Lernfortschritt kann verloren gehen.

Der Release-Build und das Dekodieren des Schlüssels werden in CI mit einem kurzlebigen synthetischen Testschlüssel geprüft, nicht mit dem echten Schlüssel. Die Konfiguration der echten Secrets, das Gerät und die Verteilung müssen anschließend durch den Verantwortlichen geprüft werden.
