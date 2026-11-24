# Isolierter Cloudflare-Ranglistentest

Dieser Bericht dokumentiert einen vom Nutzer freigegebenen Test mit künstlichen Daten. Er ist kein Nachweis für die Produktionsreife und keine Freigabe, die Rangliste in der App zu aktivieren. Ergebnisse und Dashboard-Zustände beruhen auf den vom Nutzer übermittelten Ausgaben und Screenshots; sie sind keine unabhängige Prüfung sämtlicher Cloudflare-Einstellungen.

## Abgrenzung

- Separater Ranking-Testworker `pflegeexamen-ranking-test`, synthetischer Katalogworker `pflegeexamen-ranking-katalog-test` und separate D1 `pflegeexamen-ranking-test` (ID `8cfb983c-e1f8-4138-a58d-9cee24a568ea`). Die produktive Content-D1 wurde nicht angebunden.
- Der Testkatalog lieferte 50 künstliche Fragen, keine Lehrbuchinhalte. Der Schlüssel des Testgateways blieb geheim; im Test wurden zufällige Testkonto-Token und künstliche Pseudonyme verwendet.
- Das Dashboard zeigte `DB` auf die Test-D1 und die vier Rate-Limiter-Bindings. Die Tests belegen deren grundsätzliche Erreichbarkeit für verwendete Endpunkte, nicht die genauen Grenzwerte, globale Durchsetzung oder vollständigen Missbrauchsschutz.
- Der Gateway filtert den HTTP-Zugriff, ist aber kein vollständiger Angriffsschutz und kann die Identität des D1-Bindings nicht überprüfen. Der lokale SQL-Harness wurde nicht deployt. Logs, Traces und Issues wurden für den Test ausgeschaltet.

## Beobachtungen

1. Ohne Testschlüssel und mit falschem Schlüssel kam 404; mit richtigem Schlüssel antwortete `/health` mit 200. `/health` selbst prüft weder D1 noch Katalog.
2. Der direkte Aufruf des Testkatalogs lieferte 50 Fragen, während `/quiz/today` im Ranking-Testworker zunächst 503 ergab. `/leaderboard` und eine Abfrage eines unbekannten Testkontos funktionierten. Die Ursache des 503 wurde nicht abschließend nachgewiesen.
3. Für den Dashboard-Test wurde der Katalogaufruf **nur in der Test-Worker-Kopie** auf ein Service Binding `TEST_CATALOG` zum synthetischen Katalogworker umgestellt. Danach lieferte `/quiz/today` 200 und 50 künstliche Fragen. Das ist eine Abweichung vom regulären Ranking-Worker im Repository, der den Katalog per globalem `fetch` lädt; der Test belegt daher nicht, dass dessen unveränderte Konfiguration auf Cloudflare funktioniert.
4. Ein zufälliges Testkonto wurde registriert; 50 richtige Antworten wurden gespeichert, das Ergebnis abgerufen und eine doppelte Abgabe mit 409 abgelehnt. Die Löschung lieferte die erwartete Bestätigung; erneute Registrierung mit demselben Token wurde mit 410 abgelehnt.
5. Ein zweites Testkonto erschien mit 50 Punkten im Test-Snapshot und war nach seiner Löschung nicht mehr in der Rangliste. Die lokalen Testkonto-Token-Dateien wurden laut Ausgabe entfernt.
6. Eine lesende D1-Abfrage ergab 0 Teilnehmer, 0 Versuche, 0 Tagessperren, 2 Löschsperren, 1 Snapshot und Generation 2. Die Löschsperren enthalten pseudonyme Token-Hashes; sie haben keine automatische Frist. Es wurden keine Token-Hashes in der Abfrage ausgegeben.
7. Ein weiterer Test mit zwei künstlichen Konten prüfte laut Nutzerausgabe, dass das zweite Konto das Ergebnis des ersten nicht sieht, bei zwei parallelen Abgaben desselben Kontos genau eine gewertet wird und das zweite Konto nach Löschung des ersten erreichbar bleibt. Beide Konten wurden gelöscht und die lokale Sicherungsdatei entfernt. Ein einzelner Paralleltest erzwingt weder Überlappung noch alle verteilten Reihenfolgen.
8. Für einen zusätzlichen Test wurde der Dashboard-Gateway um einen `scheduled`-Handler ergänzt und vorübergehend ein minütlicher Cron-Trigger eingerichtet. Der Snapshot-Zeitstempel erhöhte sich nach Aktivierung; danach zeigte das Dashboard **keinen konfigurierten Cron-Trigger** mehr. Das bestätigt einen beobachteten automatischen Testlauf, nicht das Verhalten eines stündlichen Produktionszeitplans oder die dauerhafte Einhaltung eines Zeitplans.
9. Ein weiterer Test startete laut Nutzerausgabe Abgabe gegen Tagessperre sowie Abgabe gegen Löschung. Beobachtet wurde jeweils ein Ausgang, in dem die Abgabe erfolgreich war; Tagessperre bzw. Löschung und anschließende Kontosperre waren konsistent. Beide künstlichen Konten wurden gelöscht und die lokale Sicherungsdatei entfernt. Das beweist weder, dass die Anfragen tatsächlich überlappten, noch die möglichen anderen Reihenfolgen.

Der zusätzliche Scheduled-Handler prüft die erwartete Testkatalog-URL, das Format des vorhandenen Testschlüssels und das Vorhandensein von `DB`, aber nicht die Identität der gebundenen D1. Er darf nicht unverändert als Produktions-Gateway betrachtet werden. Die Dashboard-Kopie und ihre Bindings unterscheiden sich von den unveränderten Repository-Fixtures und von der Produktionskonfiguration.

## Offene Punkte

Der isolierte Testworker und der Katalogworker waren nach dem Test weiterhin vorhanden; sie sollten nicht als öffentliches Produkt oder produktive Infrastruktur behandelt werden. Ein Testschlüssel ersetzt keine gründliche Sicherheitsprüfung. Aufräumen oder Abschalten der Testressourcen ist separat kontrolliert vorzunehmen; Cron-Änderungen können zeitverzögert wirksam werden. Eine abschließende lesende D1-Zählung ergab 0 Teilnehmer, 0 Versuche, 0 Tagessperren, 6 pseudonyme Löschsperren, 0 verwaiste Versuche, 0 Snapshots und Generation 6. Ein Snapshot kann nach der letzten Löschung fehlen, bis er erneut angelegt wird.

Vor einem echten Betrieb sind insbesondere Cloudflare-Katalogtransport und Bindings, exakte Rate-Limit-Konfiguration und ihre Grenzen, stündliche Aktualisierung, verteilte Rennen, Geräteintegration, Sicherung und Wiederherstellung sowie Datenschutz, Aufbewahrung und Löschung zu prüfen. Sichtbare Lösungen erlauben manipulierbare Wertungen. Die produktive App und die deaktivierte Rangliste bleiben unverändert.
