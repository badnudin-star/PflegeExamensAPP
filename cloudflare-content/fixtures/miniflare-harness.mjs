// Test-only entrypoint: provides local schema setup. Never deploy this module.
import worker from '../src/worker.js';
export default {
  async fetch(request, env, ctx) {
    if (new URL(request.url).pathname === '/__test/sql') {
      try {
        const {statements} = await request.json();
        const results = await env.DB.batch(statements.map(s => env.DB.prepare(s.sql).bind(...(s.args || []))));
        return Response.json(results);
      } catch (error) { return Response.json({error:String(error)}, {status:500}); }
    }
    return worker.fetch(request, env, ctx);
  }
};
