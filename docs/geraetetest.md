# PflegeExamensApp auf einem Android-Gerät testen

Diese Liste dient einem freiwilligen Test der **Debug-APK**, nicht einer signierten Veröffentlichung. Eine Debug-Version kann Fehler enthalten. Die verwendeten Lerninhalte und ihre medizinische Richtigkeit sind damit nicht fachlich freigegeben.

## APK herunterladen und installieren

1. Auf GitHub unter **Actions → Build Android APK** einen erfolgreichen Lauf des gewünschten Stands öffnen. Am Ende das Artifact **PflegeExamenLokal-debug-apk** herunterladen. Die ZIP-Datei enthält `PflegeExamenLokal-debug.apk`. Artifacts sind nur begrenzt verfügbar und der Download kann eine GitHub-Anmeldung erfordern.
2. Die APK auf ein Android-Gerät mit Android 8.0 oder neuer übertragen und installieren. Android kann für die gewählte Quelle eine gesonderte Erlaubnis zur Installation verlangen. Nur einer vertrauenswürdigen APK und Quelle zustimmen.
3. Ist bereits eine Version mit anderer Signatur installiert, kann Android das Update verweigern. **Nicht vorschnell deinstallieren:** Dabei können lokal gespeicherter Lernfortschritt und andere App-Daten verloren gehen. Erst Sicherung und Vorgehen klären.

## Offline lernen

1. App starten und danach den Flugmodus aktivieren. Themen öffnen und stichprobenartig in beiden Versionen einen Lernzettel lesen. Prüfen, ob Texte, Tabellen und gegebenenfalls Bilder ausreichend lesbar sind. PDF-Extraktion kann Inhalte unvollständig wiedergeben; nicht als fachliche Bestätigung verstehen.
2. In der Themensuche nach einem tatsächlich vorhandenen Wort suchen, einen angezeigten Treffer öffnen und prüfen, ob zur Fundstelle gesprungen wird. Über das Listensymbol im Lernzettel eine erkannte Überschrift auswählen.
3. Zu passenden Karteikarten wechseln, eine Karte umdrehen, als gelernt ausblenden und über **Gelernte Karten einzeln wieder einblenden** wieder zum Lernen freigeben. Prüfen, dass andere Karten sichtbar bleiben. Die Lernansicht auch einmal bei gedrehtem Gerät prüfen.
4. Ein Fallbeispiel nach Prüfungstag öffnen; prüfen, ob Antworten unter der jeweiligen Aufgabe auf- und wieder zuklappbar sind. Die Suche nach einem passenden Wort testen.
5. App verlassen und wieder öffnen. Prüfen, ob die selbst gesetzten Karten- und Fall-Markierungen noch vorhanden sind. Nur falls ein geprüftes Quiz veröffentlicht ist: eine Frage beantworten, die App verlassen und die Übung fortsetzen. Ohne veröffentlichtes Quiz ist die leere Anzeige erwartbar.

## Verbindung und Rückmeldung

- Flugmodus wieder ausschalten und in den Einstellungen die Aktualisierungsprüfung testen. Bei einem fehlgeschlagenen Abruf soll die vorhandene Offline-Kopie erhalten bleiben. Bei gesetzter oder anders konfigurierter Serveradresse können Ergebnisse abweichen.
- Die Rangliste und die tägliche Quizgenerierung sind noch nicht für den Betrieb eingerichtet. Lokale Quizpunkte zählen nicht für ein Ranking.
- Für einen Fehler sind Android-Version, Gerätemodell, App-Version, genaue Schritte und das beobachtete Verhalten hilfreich. Screenshots können Lerninhalte oder persönliche Informationen enthalten; vor dem Teilen prüfen. Keine Zugangsdaten oder Schlüssel weitergeben.

Ein einzelner Gerätetest ersetzt weder fachliche Prüfung noch Tests auf weiteren Android-Versionen. Emulator-Tests mit synthetischen Daten prüfen nur ausgewählte Abläufe.
