# PflegeExamen Lokal

Android-Lernapp für die schriftliche Prüfung in der generalistischen Pflegeausbildung. Die 42 festen Themen folgen der bereitgestellten Vorbereitungsliste Frühjahr 2027 und sind nach Prüfungstagen gegliedert. Anatomie und Physiologie gehören zu den jeweiligen Krankheitsbildern, nicht zu einem eigenen Thema.

## Lernen

- **Lernzettel:** Pro Thema stehen die verfügbaren Bereiche „Neu“ und „Einfach“ bereit. Eine Volltextsuche führt zur ersten Fundstelle; Pfeile im Lernzettel wechseln zu weiteren Treffern. Die verwendeten PDF-Texte werden extern zugeordnet; Quellenangaben bleiben intern erhalten, werden aber nicht unter den Lernzetteln angezeigt.
- **Karteikarten:** Thematisch oder zufällig lernen; einzelne bekannte Karten lassen sich aus- und wieder einblenden. Die lokale Suche findet Karten nach Thema, Frage oder Antwort, ohne die Lösung in der Übersicht anzuzeigen. Beim Lernen werden nur passende sichtbare Karten verwendet.
- **Fallbeispiele:** Nach Prüfungstag sortiert, mit Antworten direkt unter den jeweiligen Fragen zum Aufklappen.
- **MC-Quiz:** Veröffentlichte Fragen können in der App lokal geübt werden. Der Fortschritt wird pro Quiz auf dem Gerät gespeichert und kann nach einer Unterbrechung fortgesetzt oder zurückgesetzt werden; bei geänderten Quizinhalten beginnt die Übung erneut. Der vorbereitete Ablauf zur täglichen Erstellung von 50 Fragen ist noch nicht produktiv eingerichtet und erfordert fachliche Freigabe. Näheres unter [`tools/daily_quiz`](tools/daily_quiz/README.md).
- **Quiz-Rangliste:** Noch nicht eingerichtet. Eine standardmäßig deaktivierte experimentelle Teilnahmeoberfläche und ein nicht deployter Worker unter [`cloudflare-ranking`](cloudflare-ranking/README.md) verwenden ausschließlich MC-Quizpunkte; Sicherheits- und Datenschutzfragen sind vor dem Einsatz offen. Alte Lernpunkte werden nicht übernommen.

Inhalte stammen aus dem bereitgestellten Ordner `QuellenExamen`; die Originaldateien werden nicht verändert. PDF-Extraktion kann insbesondere bei Bildern, Tabellen und Layout zu Fehlern oder Lücken führen. Der Nutzer bestätigt, dass die vorhandenen Inhalte fachlich geprüft sind und aus einem Lehrbuch stammen. Die automatische Übernahme oder ein Gerätetest prüft neue oder geänderte Inhalte nicht eigenständig.

## Inhalte und Aktualisierung

Die App enthält einen Offline-Katalog und kann zusätzlich veröffentlichte Inhalte über HTTPS laden. Der Android-Build in GitHub Actions lädt den veröffentlichten Katalog und prüft, dass er in der APK enthalten ist. Ein lokaler Build verwendet den Katalog im Repository, sofern er nicht zuvor aktualisiert wurde; ältere Installationen können einen ersten Online-Abgleich benötigen. Details zur Veröffentlichung und Konfiguration: [`cloudflare-content`](cloudflare-content/README.md).

Der zuletzt importierte Bestand umfasste 523 Karten und 30 Fallbeispiele mit 494 Frage-Antwort-Paaren. Verfügbarkeit und Aktualität hängen vom jeweiligen Katalog ab. Für die aktuelle App gibt es keine Quellenverwaltung oder eigene Inhaltserstellung in der Oberfläche.

## Installation und Build

- Eine Debug-APK ist im erfolgreichen GitHub-Actions-Lauf **Build Android APK** als Artifact `PflegeExamenLokal-debug-apk` verfügbar, solange das Artifact aufbewahrt wird. Releases können ebenfalls APKs enthalten.
- Für einen lokalen Build werden Android Studio bzw. JDK 17 und Android SDK Platform 36 benötigt (`minSdk = 26`):

  ```sh
  ./gradlew testDebugUnitTest assembleDebug
  ```

  Die APK liegt danach unter `app/build/outputs/apk/debug/app-debug.apk`.

Eine Anleitung für den freiwilligen Smartphone-Test steht unter [Gerätetest](docs/geraetetest.md).

Der Build kann je nach Konfiguration einen öffentlichen Katalog laden; Zugangsschlüssel gehören weder in die App noch ins Repository.

## Backup und Release-Status

Die CI prüft einen unsignierten Release-Build und signiert anschließend einen weiteren Build ausschließlich mit einem kurzlebigen synthetischen Testschlüssel. Dieser Schlüssel ist **kein Veröffentlichungsschlüssel**; diese APK wird nicht als Release bereitgestellt. Für eine echte Veröffentlichung fehlen ein dauerhaft geschützter eigener Signaturschlüssel, ein sicheres Verteilungsverfahren und Tests auf weiteren Geräten.

Ein Release-Build kann lokal mit den vier Umgebungsvariablen `PFLEGE_RELEASE_KEYSTORE` (Dateipfad), `PFLEGE_RELEASE_STORE_PASSWORD`, `PFLEGE_RELEASE_KEY_ALIAS` und `PFLEGE_RELEASE_KEY_PASSWORD` signiert werden. Alle vier müssen vollständig gesetzt sein; eine unvollständige Konfiguration bricht ab. Ohne Variablen entsteht nur eine unsignierte Release-APK. Schlüssel und Passwörter nicht in Git, Chat, App oder Logs speichern; der echte Signaturschlüssel muss unabhängig und sicher gesichert werden. Der Schlüssel aus der CI ist nicht für die Veröffentlichung geeignet. Eine neue App-Signatur ist nicht mit bestehenden Installationen anderer Signatur updatekompatibel; Deinstallation kann lokale Daten löschen.

Android kann lokale Lernfortschritte sichern oder auf ein neues Gerät übertragen, garantiert dies aber nicht; ein manueller vollständiger Fortschrittsexport und ein echter Wiederherstellungstest fehlen. Ältere Einstellungsdateien mit möglichen API-Schlüsseln oder Ranglistenkennungen werden für neue Android-Backups und Geräteübertragungen ausgeschlossen. Vorhandene ältere Sicherungen bleiben davon unberührt. Ranglistentoken und ausstehende Ranglistenaktionen liegen im No-Backup-Speicher. Details und Warnungen stehen in der [Gerätetest-Anleitung](docs/geraetetest.md).

Die schreibgeschützte Cloudflare-Bereitschaftsprüfung und die Voraussetzungen für Archiv und Rangliste sind unter [Cloudflare-Bereitschaft](docs/cloudflare-bereitschaft.md) dokumentiert.
