// TEST ONLY. This gateway is not an application entrypoint and must never be
// deployed in place of the production content Worker or future ranking Worker.
// It has no SQL/test-admin endpoint and does not verify the actual D1 binding.
import ranking from '../src/worker.js';

const denied = () => new Response('Nicht gefunden', {
  status:404, headers:{'Cache-Control':'no-store'}
});
const encoder = new TextEncoder();

async function matchesSecret(expected, presented) {
  if (!/^[0-9a-f]{64}$/.test(expected || '') || !/^[0-9a-f]{64}$/.test(presented || '')) return false;
  const key = await crypto.subtle.importKey('raw', encoder.encode(expected),
    {name:'HMAC',hash:'SHA-256'}, false, ['sign','verify']);
  const candidate = await crypto.subtle.sign('HMAC', key, encoder.encode(presented));
  return crypto.subtle.verify('HMAC', key, candidate, encoder.encode(expected));
}

export default {
  async fetch(request, env, ctx) {
    try {
      const u = new URL(request.url);
      // Limit this fixture to distinct workers.dev names in the same account.
      const match = /^pflegeexamen-ranking-test\.([a-z0-9-]+)\.workers\.dev$/.exec(u.hostname);
      if (u.protocol !== 'https:' || u.port || !match ||
          env.QUIZ_CATALOG_URL !== `https://pflegeexamen-ranking-katalog-test.${match[1]}.workers.dev/content` ||
          !await matchesSecret(env.TEST_ONLY_KEY, request.headers.get('X-Pflege-Test-Key'))) return denied();
      const headers = new Headers(request.headers);
      headers.delete('X-Pflege-Test-Key');
      return ranking.fetch(new Request(request, {headers}), env, ctx);
    } catch {
      return denied();
    }
  }
};
