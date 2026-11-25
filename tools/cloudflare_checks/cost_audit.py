#!/usr/bin/env python3
"""Read-only Cloudflare billing/alert permission audit; no personal data in output."""
import datetime as dt
import json
import os
import re
import sys
from urllib.error import HTTPError, URLError
from urllib.request import Request, build_opener, ProxyHandler
from readiness import NoRedirect, read_json


def audit(env, opener):
    token = env.get('CLOUDFLARE_API_TOKEN', '').strip()
    account = env.get('CLOUDFLARE_ACCOUNT_ID', '').strip()
    if not token or not re.fullmatch(r'[0-9a-fA-F]{32}', account):
        raise ValueError('missing configuration')
    base = 'https://api.cloudflare.com/client/v4/accounts/' + account
    results = []
    # Only count enabled billing alert types; no names, addresses, or policy IDs.
    for label, path in [('Tarif', '/subscriptions'), ('Kostenwarnungen', '/alerting/v3/policies')]:
        try:
            result = read_json(opener, base + path, token)
            if not isinstance(result, list): raise ValueError('invalid response')
            if label == 'Tarif':
                # Account subscriptions do not reliably describe all Workers usage.
                results.append('Tarif: Abonnements lesbar (%d Einträge); Workers-Tarif und Abrechnung gesondert im Dashboard prüfen.' % len(result))
            else:
                active = sum(1 for item in result if isinstance(item, dict) and item.get('enabled') is True and
                             item.get('alert_type') in ('billing_budget_alert', 'billing_usage_alert'))
                results.append('Kostenwarnungen: %d aktive Budget-/Nutzungswarnungen gefunden; Zustellung und Schwellen nicht geprüft.' % active)
        except HTTPError as exc:
            results.append(f'{label}: nicht lesbar (HTTP {exc.code}); Status unbekannt.')
        except (URLError, OSError, ValueError, TypeError, json.JSONDecodeError):
            results.append(f'{label}: nicht lesbar; Status unbekannt.')
    try:
        end = dt.datetime.now(dt.timezone.utc).date()
        payload = json.dumps({'query': '''query Audit($account: string!, $start: Date!, $end: Date!) {
          viewer { accounts(filter: {accountTag: $account}) {
            d1AnalyticsAdaptiveGroups(limit: 100, filter: {date_geq: $start, date_leq: $end}) {
              sum { rowsRead rowsWritten } dimensions { date }
            }
          } }
        }''', 'variables': {'account': account, 'start': (end - dt.timedelta(days=6)).isoformat(), 'end': end.isoformat()}}).encode()
        req = Request('https://api.cloudflare.com/client/v4/graphql', data=payload, method='POST', headers={
            'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json', 'Accept': 'application/json'})
        with opener.open(req, timeout=30) as response:
            raw = response.read(1024 * 1024 + 1)
        if len(raw) > 1024 * 1024: raise ValueError('response too large')
        data = json.loads(raw)
        if not isinstance(data, dict) or data.get('errors'): raise ValueError('graphql error')
        accounts = data['data']['viewer']['accounts']
        if not isinstance(accounts, list) or len(accounts) != 1: raise ValueError('account missing')
        rows = accounts[0]['d1AnalyticsAdaptiveGroups']
        if not isinstance(rows, list) or len(rows) >= 100: raise ValueError('incomplete analytics')
        reads = [row['sum']['rowsRead'] for row in rows]
        writes = [row['sum']['rowsWritten'] for row in rows]
        if any(type(n) is not int or n < 0 for n in reads + writes): raise ValueError('invalid metrics')
        results.append(f'D1-Metriken letzte sieben UTC-Kalendertage: {sum(reads)} gelesene und {sum(writes)} geschriebene Zeilen (kontoweit; aktueller Tag unvollständig).')
    except HTTPError as exc:
        results.append(f'D1-Metriken: nicht lesbar (HTTP {exc.code}); Nutzung unbekannt.')
    except (URLError, OSError, ValueError, TypeError, KeyError, json.JSONDecodeError):
        results.append('D1-Metriken: nicht lesbar; Nutzung unbekannt.')
    return results


def main():
    try:
        messages = audit(os.environ, build_opener(ProxyHandler({}), NoRedirect()))
    except ValueError:
        print('::error::Cloudflare-Zugangskonfiguration fehlt oder ist ungültig.')
        return 1
    for message in messages:
        print('::notice title=Cloudflare-Kostenprüfung::' + message)
    print('::warning::Nutzung, Tarif, Warnzustellung und tatsächliche Kosten sind damit nicht vollständig verifiziert. Keine Einstellungen geändert.')
    return 0


if __name__ == '__main__': sys.exit(main())
