# Stündliches PflegeExamen-Ranking (0 € · Cloudflare Workers + D1 + optional GitHub Pages)

Dieses Backend verursacht **0 € Kosten**, benötigt **keine Kreditkarte** und aktualisiert das globale Ranking automatisch **einmal pro Stunde** (`0 * * * *`).

## Was passiert stündlich?
1. Nutzer starten die App und wählen ein Pseudonym (z. B. `PflegeFuchs27`).
2. Jede Stunde sendet die App über `POST /score` anonymisiert `{ userId, username, totalXp, streakDays, rankTitle }` an diesen Worker (oder lädt lesend `GET /leaderboard`).
3. Der Worker-Cron (`0 * * * *`) erstellt jede volle Stunde eine feste **Top-100-Momentaufnahme** in D1 (`hourly_snapshots`) und optional auch als `docs/leaderboard.json` in deinem GitHub-Repository.

## Einrichtung in 5 Minuten (0 €)

### 1. Kostenloses Cloudflare-Konto & D1-Datenbank anlegen
```bash
cd cloudflare-ranking
npx wrangler login
npx wrangler d1 create pflege_ranking_db
```
Die ausgegebene `database_id` in `wrangler.toml` eintragen.

### 2. SQLite-Tabellen in D1 anlegen
```bash
npx wrangler d1 execute pflege_ranking_db --remote --file=./schema.sql
```

### 3. Worker kostenlos veröffentlichen
```bash
npx wrangler deploy
```
Du erhältst sofort eine HTTPS-URL wie:
`https://pflege-examen-ranking.<dein-subdomain>.workers.dev`

### 4. URL in der App oder fest in der APK hinterlegen
- **In der App:** *Einstellungen -> Benutzername & Stündliches Ranking -> Ranking-URL* eintragen & *Verbindung testen*.
- **Für alle Nutzer fest in der APK/Bundle:** Die URL in `RankingSettings.DEFAULT_RANKING_ENDPOINT_URL` (oder in `PflegeBundleStudio.html` -> `rankingEndpointUrl`) eintragen, bevor du die APK baust!

### Optional: Automatischer GitHub-JSON-Export
Falls zusätzlich `docs/leaderboard.json` für GitHub Pages jede Stunde committet werden soll, einmalig zwei Secrets setzen:
```bash
npx wrangler secret put GITHUB_TOKEN
npx wrangler secret put GITHUB_REPO   # Format: deinuser/deinrepo
```
