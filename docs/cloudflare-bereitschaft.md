# Cloudflare-Bereitschaft und sichere Inbetriebnahme

Der manuell startbare Workflow **Cloudflare-Bereitschaft schreibgeschützt prüfen** verwendet die bereits für das Ranking-Deployment vorgesehenen GitHub-Actions-Secrets `CLOUDFLARE_API_TOKEN` und `CLOUDFLARE_ACCOUNT_ID`, sofern sie vorhanden sind. Er führt ausschließlich zwei GET-Abfragen gegen die feste Cloudflare-API aus: Einstellungen des bestehenden Inhalts-Workers sowie die D1-Datenbankliste. Er meldet nur, ob das Binding `DB` und die erforderliche Leseberechtigung erkannt wurden. Zugangsdaten, Konto-ID, Datenbanknamen, Datenbankinhalte und API-Antworten werden nicht ausgegeben. Fehlende Konfiguration, fehlende Berechtigungen und API-Fehler führen zu einem fehlgeschlagenen Lauf; HTTP-Weiterleitungen werden nicht verfolgt.

Ein positiver Check beweist **keine** Schreibrechte, keinen gültigen Inhalt der Datenbank, kein funktionierendes Backup, keine Rollout- oder Datenschutzfreigabe und keine Kostenfreiheit. Es werden keine Datenbanken angelegt oder verändert, keine Migrationen angewendet, keine Worker oder App bereitgestellt und keine Rangliste eingeschaltet. Die echte Infrastruktur muss separat geprüft werden.

## Bevor Archiv oder Ranking in Betrieb gehen

1. Verantwortliche klären Kosten, Zugriff, Datenschutz und Aufbewahrung. Besonders die Löschsperre im Ranking speichert einen pseudonymen Token-Hash ohne automatische Frist; siehe `cloudflare-ranking/README.md`.
2. Erst eine getrennte echte Testumgebung mit eigenen D1-Datenbanken und synthetischen Daten einrichten. Archivmigration und Ranking-Schema dort ausprobieren; anschließend Registrierung, Abgabe, Konflikte, Kontolöschung, Restore und ältere App-Versionen testen. Ein Miniflare-Test ersetzt das nicht.
3. Vor jeder Änderung an der bestehenden Inhaltsdatenbank eine **unabhängige vollständige Sicherung** anlegen und deren Wiederherstellung in einer getrennten Datenbank überprüfen. Erst danach kann die Archivmigration und ein kontrollierter Worker-Rollout erfolgen. Keine Quiztage entfernen, bevor ältere App-Versionen und die Risiken berücksichtigt sind.
4. Für Ranking separate Produktionsdatenbank und D1-Bindings, Rate-Limit-Namespaces, Katalog-URL, Schutz und Überwachung konfigurieren; App-Teilnahme erst nach erfolgreichen Tests ausdrücklich aktivieren. Lösungen sind sofort sichtbar, daher bleibt das Ranking manipulierbar.

Keine Geheimnisse in Chat, Repository, App oder Logs eingeben. Der Bereitschafts-Workflow ist keine Deployment-Anleitung und keine Inbetriebnahmefreigabe.
