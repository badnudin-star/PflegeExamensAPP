# Quiz-Rangliste: serverseitiger Entwurf

**Status: vorbereitet, nicht veröffentlicht und noch nicht mit der aktuellen App verbunden.** Die App zeigt weiter eine neutrale Platzhalterseite. Der alte Worker, der beliebige `totalXp` vom Client annahm, wurde ersetzt. Alte Lernpunkte, Streaks und Rangtitel werden nicht übernommen.

## Funktionsweise des Entwurfs

- Ein Teilnehmer registriert ein Pseudonym und erhält einmalig ein zufälliges Geräte-Token; gespeichert wird nur dessen SHA-256-Hash. Der Verlust des Tokens bedeutet derzeit den Verlust des Zugangs.
- `GET /quiz/today` liefert das veröffentlichte Tagesquiz ohne die Lösungsindizes. Es akzeptiert nur einen vollständigen Satz von 50 gültigen Fragen.
- `POST /quiz/attempt` nimmt mit dem Token und einer Prüfsumme zum Quiz genau eine vollständige Antwortliste an. Der Server berechnet die Zahl richtiger Antworten. Pro Teilnehmer und Berliner Kalendertag ist dank Datenbank-Eindeutigkeit nur ein gewerteter Versuch möglich. Keine fremden XP werden angenommen.
- `GET /leaderboard` liefert einen gespeicherten Top-100-Snapshot; der Cron aktualisiert ihn stündlich. Punkte sind ausschließlich die Summe richtig beantworteter MC-Fragen. Die Antwort enthält Pseudonyme, aber keine Geräte-IDs oder Token. Alte Tabellen bleiben unberührt und werden nicht ausgewertet.

**Kein fälschungssicheres Ranking:** Der bestehende Inhaltskatalog ist öffentlich und enthält die Lösungen. Man kann Antworten dort einsehen oder mehrere anonyme Konten erstellen. Der Entwurf braucht vor öffentlichem Betrieb mindestens ein geeignetes Identitäts-/Missbrauchsschutzkonzept und Rate-Limiting. Auch Datenschutz, Löschung und Verlust/Wiederherstellung des Geräte-Tokens sind noch zu klären. An der öffentlichen Inhalts-API wurde nichts verändert. Solange die App lokale Quiz ohne dieses Backend auswertet, sind keine Ranglistenpunkte möglich.

## Noch erforderlich vor Inbetriebnahme

1. Einen sicheren Missbrauchsschutz, ein Datenschutz- und Löschkonzept sowie die Android-Integration fertigstellen und mit realer Cloudflare-D1-Datenbank testen.
2. Cloudflare Worker und eine separate D1-Datenbank einrichten, `schema.sql` anwenden und D1 als `DB` binden. Der Entwurf benötigt eine öffentliche HTTPS-Adresse `QUIZ_CATALOG_URL` mit Pfad `/content`. Die ID und Adresse müssen bei der späteren Einrichtung in der Konfiguration ergänzt werden. `/health` prüft nur, ob der Worker antwortet, nicht Datenbank oder Inhalt.
3. Das vorhandene Tagesquiz muss geprüft veröffentlicht sein. Keine Gemini-Schlüssel oder Cloudflare-Tokens in App, Repository oder Chat speichern.
4. Das Deployment bleibt standardmäßig aus. Das alte Flag `ENABLE_CLOUDFLARE_DEPLOY` startet diesen Workflow nicht mehr. Das neue Flag `ENABLE_QUIZ_RANKING_DEPLOY` darf erst nach Abschluss der obigen Punkte und Prüfung der Cloudflare-Zugangsdaten gesetzt werden. Keine Preisgarantie: Kosten, Nutzungsgrenzen und Kontobedingungen hängen vom Anbieter und Verbrauch ab.

Lokale Tests: `npm test --prefix cloudflare-ranking` und `python -m unittest discover -s cloudflare-ranking/test -p 'test_*.py' -v`. Sie ersetzen keinen Test auf Cloudflare oder auf einem Android-Gerät.

## Löschung im Prototyp

Der noch nicht deployte Worker bietet `DELETE /participants/me` mit dem Geräte-Token als `Authorization: Bearer …`. In einer D1-Transaktion werden ausschließlich die zugehörigen Quizversuche und der Teilnehmer gelöscht und der öffentliche Ranglisten-Snapshot verworfen. Ein Generationszähler verhindert, dass ein gleichzeitig berechneter alter Snapshot die gelöschten Daten wiederherstellt; bis zur Neuberechnung kann die Rangliste vorübergehend nicht verfügbar sein. Die App hat hierfür **noch keine Bedienoberfläche**, und bei Tokenverlust besteht derzeit keine Wiederherstellung oder andere Löschmöglichkeit. Vor Deployment muss `schema.sql` einschließlich der neuen Generationstabelle angewendet werden. Nicht vorhandenes Schema führt zu Fehlern; die Operation darf nicht als erfolgreich gemeldet werden.

Eine Löschung kann Daten aus dem aktiven Ranking entfernen, aber bereits ausgelieferte Antworten, externe Kopien und etwaige Backups nicht automatisch zurückholen. Speicherfristen, Backup-Löschung, Rechteinformation und ein sicherer Prozess bei Tokenverlust bleiben vor öffentlichem Betrieb zu klären. Es gibt weiterhin weder geeignete Identitäts-/Mehrfachkontosicherung noch Rate-Limiting oder Schutz gegen Einsicht der öffentlichen Quizlösungen.
