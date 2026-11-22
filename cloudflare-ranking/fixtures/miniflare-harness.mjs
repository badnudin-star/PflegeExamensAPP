// Local-only test harness. Never use this as a deployed entrypoint.
import worker from '../src/worker.js';
export default {
  async fetch(request, env, ctx) {
    const path = new URL(request.url).pathname;
    if (path === '/__test/sql') {
      try {
        const {statements} = await request.json();
        const results = await env.DB.batch(statements.map(s => env.DB.prepare(s.sql).bind(...(s.args || []))));
        return Response.json(results);
      } catch (error) { return Response.json({error:String(error)}, {status:500}); }
    }
    const mode = request.headers.get('X-Test-Rate');
    if (mode) {
      const limiter = {async limit() { if (mode === 'error') throw Error('test'); return {success: mode !== 'deny'}; }};
      env = {...env, REGISTRATION_RATE:limiter, READ_RATE:limiter, AUTH_RATE:limiter, ACCOUNT_RATE:limiter};
    }
    if (request.headers.get('X-Test-No-Db')) env = {...env, DB:undefined};
    if (request.headers.get('X-Test-Bad-Catalog')) env = {...env, QUIZ_CATALOG_URL:'https://catalog.test/not-content'};
    return worker.fetch(request, env, ctx);
  }
};
