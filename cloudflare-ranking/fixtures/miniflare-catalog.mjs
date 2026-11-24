// Synthetic catalog: rejects every outgoing URL except this test endpoint.
import catalog from './synthetic-test-catalog.mjs';

export default {
  async fetch(request, env) {
    const redirected = request.url === 'https://catalog.test/redirected';
    if (request.url !== 'https://catalog.test/content' && !(env.CATALOG_STATUS === 'redirect' && redirected))
      return new Response('blocked', {status:403});
    // The destination is valid; a client that follows this redirect would succeed.
    if (env.CATALOG_STATUS === 'redirect' && !redirected) return new Response(null, {status:302, headers:{Location:'https://catalog.test/redirected'}});
    if (env.CATALOG_STATUS === 'error') return new Response('unavailable', {status:503});
    // A following client would receive a valid fixture, proving redirects matter.
    return catalog.fetch(new Request('https://catalog.test/content'));
  }
};
