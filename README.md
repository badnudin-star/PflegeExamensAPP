# PflegeExamen Lokal

Android-Lernapp für die schriftliche Prüfung in der generalistischen Pflegeausbildung. Die 42 festen Themen folgen der bereitgestellten Vorbereitungsliste Frühjahr 2027 und sind nach Prüfungstagen gegliedert. Anatomie und Physiologie gehören zu den jeweiligen Krankheitsbildern, nicht zu einem eigenen Thema.

## Lernen

- **Lernzettel:** Pro Thema stehen die verfügbaren Bereiche „Neu“ und „Einfach“ bereit. Eine Volltextsuche führt zur Fundstelle. Die verwendeten PDF-Texte werden extern zugeordnet; Quellenangaben bleiben intern erhalten, werden aber nicht unter den Lernzetteln angezeigt.
- **Karteikarten:** Thematisch oder zufällig lernen; einzelne bekannte Karten lassen sich aus- und wieder einblenden.
- **Fallbeispiele:** Nach Prüfungstag sortiert, mit Antworten direkt unter den jeweiligen Fragen zum Aufklappen.
- **MC-Quiz:** Veröffentlichte Fragen können in der App lokal geübt werden. Der Fortschritt wird pro Quiz auf dem Gerät gespeichert und kann nach einer Unterbrechung fortgesetzt oder zurückgesetzt werden; bei geänderten Quizinhalten beginnt die Übung erneut. Der vorbereitete Ablauf zur täglichen Erstellung von 50 Fragen ist noch nicht produktiv eingerichtet und erfordert fachliche Freigabe. Näheres unter [`tools/daily_quiz`](tools/daily_quiz/README.md).
- **Quiz-Rangliste:** Noch nicht eingerichtet. Ein nicht deployter und nicht in die App integrierter Entwurf unter [`cloudflare-ranking`](cloudflare-ranking/README.md) berechnet ausschließlich MC-Quizpunkte; Sicherheits- und Datenschutzfragen sind vor dem Einsatz offen. Alte Lernpunkte werden nicht übernommen.

Inhalte stammen aus dem bereitgestellten Ordner `QuellenExamen`; die Originaldateien werden nicht verändert. PDF-Extraktion kann insbesondere bei Bildern, Tabellen und Layout zu Fehlern oder Lücken führen. Die Inhalte und Zuordnungen sind nicht vollständig fachlich geprüft und ersetzen keine fachliche Kontrolle.

## Inhalte und Aktualisierung

Die App enthält einen Offline-Katalog und kann zusätzlich veröffentlichte Inhalte über HTTPS laden. Der Android-Build in GitHub Actions lädt den veröffentlichten Katalog und prüft, dass er in der APK enthalten ist. Ein lokaler Build verwendet den Katalog im Repository, sofern er nicht zuvor aktualisiert wurde; ältere Installationen können einen ersten Online-Abgleich benötigen. Details zur Veröffentlichung und Konfiguration: [`cloudflare-content`](cloudflare-content/README.md).

Der zuletzt importierte Bestand umfasste 523 Karten und 30 Fallbeispiele mit 494 Frage-Antwort-Paaren. Verfügbarkeit und Aktualität hängen vom jeweiligen Katalog ab. Für die aktuelle App gibt es keine Quellenverwaltung oder eigene Inhaltserstellung in der Oberfläche.

## Installation und Build

- Eine Debug-APK ist im erfolgreichen GitHub-Actions-Lauf **Build Android APK** als Artifact `PflegeExamenLokal-debug-apk` verfügbar, solange das Artifact aufbewahrt wird. Releases können ebenfalls APKs enthalten.
- Für einen lokalen Build werden Android Studio bzw. JDK 17 und Android SDK Platform 35 benötigt (`minSdk = 26`):

  ```sh
  ./gradlew testDebugUnitTest assembleDebug
  ```

  Die APK liegt danach unter `app/build/outputs/apk/debug/app-debug.apk`.

Der Build kann je nach Konfiguration einen öffentlichen Katalog laden; Zugangsschlüssel gehören weder in die App noch ins Repository.
