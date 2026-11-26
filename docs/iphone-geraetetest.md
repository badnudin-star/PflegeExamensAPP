# iPhone-Vorschau sicher testen

Die iPhone-IPA ist ein **nicht signierter Testkandidat**, keine freigegebene Klassen-App und nicht vollständig zur Android-App gleichwertig. Die Wertungsrangliste ist im normalen Build noch gesperrt. Erst nach einem erfolgreichen Test auf einem echten Gerät, Prüfung der offenen Funktionen und der Verteilung darf sie als Klassen-App bezeichnet werden.

## Voraussetzungen

- Ein freiwilliger Tester mit eigenem iPhone (iOS 16 oder neuer), Apple-Konto und WLAN. Ohne iPhone kann weder die SideStore-Einrichtung noch die Installation bestätigt werden.
- Ein Linux-PC und ein USB-Datenkabel für die erste SideStore-Einrichtung. Nach der [offiziellen Anleitung](https://docs.sidestore.io/docs/installation/prerequisites) SideStore und die erforderliche lokale VPN-Konfiguration einrichten. Die Anleitung und Sicherheitsbedingungen der verwendeten Komponenten vorher lesen.
- Bei einem kostenlosen Apple-Konto müssen sideloaded Apps spätestens nach sieben Tagen erneuert werden; automatische Erneuerung ist nicht garantiert. Apple begrenzt die Zahl gleichzeitig seitengeladener Apps. Keine kostenpflichtige Mitgliedschaft voraussetzen.
- Apple-Passwörter, Zwei-Faktor-Codes, Pairing-Dateien und den Ranglistenzugang niemals im Chat, Repository oder an andere Personen geben. Jeder Tester verwendet nur sein eigenes Konto und Gerät.

## Test-IPA beziehen und installieren

1. Im aktuell geprüften [iOS-Actions-Lauf](https://github.com/badnudin-star/PflegeExamensAPP/actions/workflows/ios-preview.yml) den erfolgreichen Lauf für den gewünschten Commit öffnen. Unter **Artifacts** `PflegeExamen-iOS-SideStore-IPA-Vorschau-UNSIGNIERT` herunterladen; der Zugriff kann eine GitHub-Anmeldung erfordern, und das Artifact läuft nach sieben Tagen ab. Ein älterer Lauf entspricht nicht automatisch dem neuesten Stand.
2. Das heruntergeladene ZIP entpacken. Die darin enthaltene `.ipa` ist nicht signiert und nicht direkt installierbar. Auf einem vertrauenswürdigen Weg in die Dateien-App des Test-iPhones übertragen; keine Passwörter oder Pairing-Dateien mit übertragen.
3. Nach Abschluss der SideStore-Ersteinrichtung die IPA über SideStore aus **Dateien** auswählen und mit dem eigenen Apple-Konto signieren und installieren. Die Bedienung kann je nach SideStore-Version abweichen; im Zweifel die offizielle Anleitung nutzen. Bei Fehlern nicht fremde Zertifikate oder Konten verwenden.
4. Nach sieben Tagen oder vor Ablauf die Erneuerung prüfen. WLAN und die für SideStore nötige VPN-Konfiguration müssen funktionieren. Ein Erfolg beim ersten Start beweist noch nicht, dass spätere Erneuerungen funktionieren.

## Funktionsprüfung

Nur synthetische oder unkritische lokale Angaben verwenden. Persönliche Daten und Zugangscodes nicht in Screenshots oder Fehlerberichten zeigen.

- App startet nach Installation und erneut nach vollständigem Schließen; Offline-Start im Flugmodus prüfen.
- Mehrere Lernzettel in beiden Versionen öffnen, Text und Original-PDF anzeigen. Mindestens ein bild-/tabellenhaltiges Original visuell mit der bekannten Vorlage vergleichen. Ein Textabgleich ist kein medizinischer oder grafischer Vollständigkeitsnachweis.
- Karten suchen, lernen, markieren und die Markierung nach Neustart prüfen. Fälle nach Prüfungstag und Aufgabe suchen und als abgeschlossen markieren.
- Falls Quizfragen vorhanden sind, eine lokale Übung beantworten, Ergebnis und Durchsicht prüfen und nur auf dem Testgerät zurücksetzen. Archivtag bei Verbindung laden und anschließend offline öffnen, sofern ein Tag veröffentlicht ist.
- Öffentliche Rangliste und Katalogaktualisierung testen. Die Anzeige sendet die IP-Adresse an die jeweiligen Server. **Die aktive Ranglistenteilnahme ist in dieser IPA nicht freigeschaltet**; keine Konto- oder Punktefunktion als getestet melden.
- Große Schrift und VoiceOver stichprobenartig prüfen; Geräte- und iOS-Version, verwendete IPA-Version, Zeitpunkt und beobachtete Fehler ohne persönliche Daten dokumentieren.
- Nach SideStore-Erneuerung App erneut öffnen und prüfen, ob lokale Markierungen erhalten sind. Vorher keine App löschen: lokaler Fortschritt kann dabei verloren gehen.

## Vor der Klassenfreigabe

Das reale Ergebnis einschließlich Signierung, Installation, Erneuerung und Funktion muss ausgewertet werden. Die unabhängige Löschjournal-Integration, ein sicherer Restore, die iOS-Wertung und der Datenschutz müssen gesondert geprüft und aktiviert werden. Eine dauerhaft abrufbare IPA und eine dazu passende SideStore-Quelle fehlen noch; ein zeitlich begrenztes Actions-Artifact ersetzt keine öffentliche Quelle. Erst nach diesen Schritten und einer bewussten Freigabe die App in der Klasse verteilen.
