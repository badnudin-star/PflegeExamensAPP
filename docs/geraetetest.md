# PflegeExamensApp auf einem Android-Gerät testen

Der Nutzer hat bestätigt, die App erfolgreich auf einem Smartphone getestet zu haben. Nach Bereitstellung der internen signierten APK **3.0.2** und Anleitung zum Update und Archivtest meldete er erneut „funktioniert“. Später bestätigte er einen weiteren APK-Test auf dem Gerät, ohne den genauen Buildstand anzugeben. Gerätemodell, Android-Version und ein detailliertes Testprotokoll wurden nicht angegeben; daraus folgt keine Prüfung aller Einzelschritte, späterer Änderungen oder anderer Geräte. Diese Liste dient weiteren freiwilligen Tests; die unten beschriebene Download-Anleitung bezieht sich auf die **Debug-APK**, nicht die interne signierte APK. Eine Debug-Version kann Fehler enthalten. Die vorhandenen Inhalte sind nach Angabe des Nutzers fachlich geprüft und stammen aus einem Lehrbuch; ein Gerätetest prüft die technische Darstellung.

## APK herunterladen und installieren

1. Auf GitHub unter **Actions → Build Android APK** einen erfolgreichen Lauf des gewünschten Stands öffnen. Am Ende das Artifact **PflegeExamenLokal-debug-apk** herunterladen. Die ZIP-Datei enthält `PflegeExamenLokal-debug.apk`. Artifacts sind nur begrenzt verfügbar und der Download kann eine GitHub-Anmeldung erfordern.
2. Die APK auf ein Android-Gerät mit Android 8.0 oder neuer übertragen und installieren. Android kann für die gewählte Quelle eine gesonderte Erlaubnis zur Installation verlangen. Nur einer vertrauenswürdigen APK und Quelle zustimmen.
3. Ist bereits eine Version mit anderer Signatur installiert, kann Android das Update verweigern. **Nicht vorschnell deinstallieren:** Dabei können lokal gespeicherter Lernfortschritt und andere App-Daten verloren gehen. Erst Sicherung und Vorgehen klären.

## Offline lernen

1. App starten und danach den Flugmodus aktivieren. Themen öffnen und stichprobenartig in beiden Versionen einen Lernzettel lesen. Prüfen, ob Texte, Tabellen und gegebenenfalls Bilder ausreichend lesbar sind. Auf mögliche Darstellungs- oder Übertragungsfehler achten.
2. In der Themensuche nach einem tatsächlich vorhandenen Wort suchen, einen angezeigten Treffer öffnen und prüfen, ob zur Fundstelle gesprungen wird. Über das Listensymbol im Lernzettel eine erkannte Überschrift auswählen.
3. Zu passenden Karteikarten wechseln, eine Karte umdrehen, als gelernt ausblenden und über **Gelernte Karten einzeln wieder einblenden** wieder zum Lernen freigeben. Prüfen, dass andere Karten sichtbar bleiben. Die Lernansicht auch einmal bei gedrehtem Gerät prüfen.
4. Ein Fallbeispiel nach Prüfungstag öffnen; prüfen, ob Antworten unter der jeweiligen Aufgabe auf- und wieder zuklappbar sind. Die Suche nach einem passenden Wort testen.
5. App verlassen und wieder öffnen. Prüfen, ob die selbst gesetzten Karten- und Fall-Markierungen noch vorhanden sind. Nur falls ein geprüftes Quiz veröffentlicht ist: eine Frage beantworten, die App verlassen und die Übung fortsetzen. Ohne veröffentlichtes Quiz ist die leere Anzeige erwartbar.

## Verbindung und Rückmeldung

- Flugmodus wieder ausschalten und in den Einstellungen die Aktualisierungsprüfung testen. Bei einem fehlgeschlagenen Abruf soll die vorhandene Offline-Kopie erhalten bleiben. Bei gesetzter oder anders konfigurierter Serveradresse können Ergebnisse abweichen.
- Die Rangliste ist für die interne signierte App eingerichtet und freiwillig. Lokale Quizübungen zählen nicht für die Rangliste; eine gewertete Teilnahme erfolgt separat. Die automatische Gemini-Generierung ist bis zur bestätigten Kostenprüfung pausiert; ältere Fragen bleiben im geladenen Inhaltsstand verfügbar.
- Für einen Fehler sind Android-Version, Gerätemodell, App-Version, genaue Schritte und das beobachtete Verhalten hilfreich. Screenshots können Lerninhalte oder persönliche Informationen enthalten; vor dem Teilen prüfen. Keine Zugangsdaten oder Schlüssel weitergeben.

Ein einzelner Gerätetest ersetzt keine Tests auf weiteren Android-Versionen. Emulator-Tests mit synthetischen Daten prüfen nur ausgewählte Abläufe.


## Backup und Gerätewechsel

Android-Backups und Geräteübertragungen können lokale Karten-, Fall- und Quizfortschritte übernehmen, sind aber abhängig von Gerät, Android-Version, Einstellungen und Sicherungsdienst. Sie sind **nicht garantiert** und ersetzen keinen überprüften Wiederherstellungstest. Ein manueller vollständiger Fortschrittsexport ist derzeit nicht eingebaut. Vor Deinstallation, Gerätewechsel oder Wechsel der App-Signatur nicht davon ausgehen, dass die Daten wiederherstellbar sind.

Für neu erstellte Android-Backups und direkte Geräteübertragungen schließen die App-Regeln zwei ältere Einstellungsdateien mit möglichen KI-API-Schlüsseln bzw. Ranglistenkennungen aus. Frühere Sicherungen werden dadurch nicht automatisch gelöscht. Der experimentelle Ranglistenzugang, ausstehende Abgaben und Löschjournale liegen separat im No-Backup-Speicher und werden nicht mit dem Android-Backup übertragen. Ein optionaler Ranglisten-Sicherungscode ist ein vollständiger Zugang und ersetzt keine Übertragung des lokalen Lernfortschritts. Ein echter Backup-/Restore-Test auf verschiedenen Geräten steht noch aus.

Der Inhaltspaket-Export der früheren Oberfläche ist kein vollständiges Fortschrittsbackup und kann eine gespeicherte Ranking-URL enthalten. Eine technische Bestandsaufnahme und die offenen Anforderungen für einen späteren manuellen Export stehen im [Sicherungskonzept](fortschritt-sicherung-konzept.md).
