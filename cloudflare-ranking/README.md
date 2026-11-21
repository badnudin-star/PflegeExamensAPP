# Quiz-Rangliste: serverseitiger Entwurf

**Status: vorbereitet, nicht veröffentlicht und noch nicht mit der aktuellen App verbunden.** Die App zeigt weiter eine neutrale Platzhalterseite. Der alte Worker, der beliebige `totalXp` vom Client annahm, wurde ersetzt. Alte Lernpunkte, Streaks und Rangtitel werden nicht übernommen.

## Funktionsweise des Entwurfs

- Ein Teilnehmer registriert ein Pseudonym und erhält einmalig ein zufälliges Geräte-Token; gespeichert wird nur dessen SHA-256-Hash. Der Verlust des Tokens bedeutet derzeit den Verlust des Zugangs.
- `GET /quiz/today` liefert das veröffentlichte Tagesquiz ohne die Lösungsindizes. Es akzeptiert nur einen vollständigen Satz von 50 gültigen Fragen.
- `POST /quiz/attempt` nimmt mit dem Token und einer Prüfsumme zum Quiz genau eine vollständige Antwortliste an. Der Server berechnet die Zahl richtiger Antworten. Pro Teilnehmer und Berliner Kalendertag ist dank Datenbank-Eindeutigkeit nur ein gewerteter Versuch möglich. Keine fremden XP werden angenommen.
- `GET /leaderboard` liefert einen gespeicherten Top-100-Snapshot; der Cron aktualisiert ihn stündlich. Punkte sind ausschließlich die Summe richtig beantworteter MC-Fragen. Die Antwort enthält Pseudonyme, aber keine Geräte-IDs oder Token. Alte Tabellen bleiben unberührt und werden nicht ausgewertet.

**Kein fälschungssicheres Ranking:** Der bestehende Inhaltskatalog ist öffentlich und enthält die Lösungen. Man kann Antworten dort einsehen oder mehrere anonyme Konten erstellen. Der Entwurf braucht vor öffentlichem Betrieb mindestens ein geeignetes Identitäts-/Missbrauchsschutzkonzept; die vorbereitete grobe Anfragebegrenzung reicht dafür nicht aus. Auch Datenschutz, Löschung und Verlust/Wiederherstellung des Geräte-Tokens sind noch zu klären. An der öffentlichen Inhalts-API wurde nichts verändert. Solange die App lokale Quiz ohne dieses Backend auswertet, sind keine Ranglistenpunkte möglich.

## Noch erforderlich vor Inbetriebnahme

1. Einen sicheren Missbrauchsschutz, ein Datenschutz- und Löschkonzept sowie die Android-Integration fertigstellen und mit realer Cloudflare-D1-Datenbank testen.
2. Cloudflare Worker und eine separate D1-Datenbank einrichten, `schema.sql` anwenden und D1 als `DB` binden. Der Entwurf benötigt eine öffentliche HTTPS-Adresse `QUIZ_CATALOG_URL` mit Pfad `/content`. Die ID und Adresse müssen bei der späteren Einrichtung in der Konfiguration ergänzt werden. `/health` prüft nur, ob der Worker antwortet, nicht Datenbank oder Inhalt.
3. Das vorhandene Tagesquiz muss geprüft veröffentlicht sein. Keine Gemini-Schlüssel oder Cloudflare-Tokens in App, Repository oder Chat speichern.
4. Das Deployment bleibt standardmäßig aus. Das alte Flag `ENABLE_CLOUDFLARE_DEPLOY` startet diesen Workflow nicht mehr. Das neue Flag `ENABLE_QUIZ_RANKING_DEPLOY` darf erst nach Abschluss der obigen Punkte und Prüfung der Cloudflare-Zugangsdaten gesetzt werden. Keine Preisgarantie: Kosten, Nutzungsgrenzen und Kontobedingungen hängen vom Anbieter und Verbrauch ab.

Lokale Tests: `npm test --prefix cloudflare-ranking` und `python -m unittest discover -s cloudflare-ranking/test -p 'test_*.py' -v`. Sie ersetzen keinen Test auf Cloudflare oder auf einem Android-Gerät.

## Löschung im Prototyp

Der noch nicht deployte Worker bietet `DELETE /participants/me` mit dem Geräte-Token als `Authorization: Bearer …`. In einer D1-Transaktion werden ausschließlich die zugehörigen Quizversuche und der Teilnehmer gelöscht und der öffentliche Ranglisten-Snapshot verworfen. Ein Generationszähler verhindert, dass ein gleichzeitig berechneter alter Snapshot die gelöschten Daten wiederherstellt; bis zur Neuberechnung kann die Rangliste vorübergehend nicht verfügbar sein. Die App hat hierfür **noch keine Bedienoberfläche**, und bei Tokenverlust besteht derzeit keine Wiederherstellung oder andere Löschmöglichkeit. Vor Deployment muss `schema.sql` einschließlich der neuen Generationstabelle angewendet werden. Nicht vorhandenes Schema führt zu Fehlern; die Operation darf nicht als erfolgreich gemeldet werden.

Eine Löschung kann Daten aus dem aktiven Ranking entfernen, aber bereits ausgelieferte Antworten, externe Kopien und etwaige Backups nicht automatisch zurückholen. Speicherfristen, Backup-Löschung, Rechteinformation und ein sicherer Prozess bei Tokenverlust bleiben vor öffentlichem Betrieb zu klären. Es gibt weiterhin weder geeignete Identitäts-/Mehrfachkontosicherung noch Schutz gegen Einsicht der öffentlichen Quizlösungen. Die vorbereiteten Anfragebegrenzungen ersetzen keinen solchen Schutz.

## Vorbereitete Anfragebegrenzung

Der nicht deployte Worker begrenzt Registrierung, Lesezugriffe und authentifizierte Vorgänge näherungsweise über Cloudflares Rate-Limiting-Bindings. Bei ungültigem oder fehlendem `CF-Connecting-IP` oder nicht verfügbarer Begrenzung antwortet der jeweilige Endpunkt mit 503; bei einer Ablehnung mit 429. Für Registrierung und lesende Anfragen wird ein Hash der von Cloudflare bereitgestellten IP verwendet, für authentifizierte Vorgänge zusätzlich ein Hash des Tokens. Der Worker schreibt diese Schlüssel nicht in seine Datenbank oder eigenen Logs. **Ein unsalziger IP-Hash ist pseudonymisiert, nicht anonym** und kann rückrechenbar sein; Cloudflare verarbeitet weiterhin die ursprüngliche IP. Rechtliche Grundlage, Speicherfristen und Anbieterbedingungen sind vor Betrieb zu prüfen. Nur Cloudflares vertrauenswürdiges Header-Set darf als Herkunft dienen; das Konzept muss bei vorgeschalteten Proxys oder Worker-zu-Worker-Aufrufen überprüft werden.

Die Werte in `wrangler.toml` sind Startwerte, keine zugesicherten Grenzen. Cloudflare zählt näherungsweise und getrennt je Standort; Grenzen können überschritten oder durch wechselnde IPs, Tokens und Standorte umgangen werden. Geteilte Anschlüsse und Mobilfunknetze können mehrere legitime Personen gemeinsam ausbremsen; auch die Löschung kann vorübergehend begrenzt werden. Namespace-IDs müssen im Cloudflare-Konto eindeutig sein und die Bindings, Kosten und Limits vor einem späteren Deployment tatsächlich getestet werden. Tests mit lokalen Mocks ersetzen keinen Cloudflare-Integrationstest. Identität, Mehrfachkonten, Datenschutz und öffentliche Lösungen sind weiterhin ungelöst; das Ranking bleibt deaktiviert.

Technische Grundlage: https://developers.cloudflare.com/workers/runtime-apis/bindings/rate-limit/
