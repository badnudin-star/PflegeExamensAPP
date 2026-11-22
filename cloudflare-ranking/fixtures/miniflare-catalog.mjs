// Synthetic catalog: rejects every outgoing URL except this test endpoint.
export default {
  async fetch(request, env) {
    const redirected = request.url === 'https://catalog.test/redirected';
    if (request.url !== 'https://catalog.test/content' && !(env.CATALOG_STATUS === 'redirect' && redirected))
      return new Response('blocked', {status:403});
    // The destination is valid; a client that follows this redirect would succeed.
    if (env.CATALOG_STATUS === 'redirect' && !redirected) return new Response(null, {status:302, headers:{Location:'https://catalog.test/redirected'}});
    if (env.CATALOG_STATUS === 'error') return new Response('unavailable', {status:503});
    const date = new Intl.DateTimeFormat('en-CA', {timeZone:'Europe/Berlin',year:'numeric',month:'2-digit',day:'2-digit'}).format(new Date());
    return Response.json({schemaVersion:1, quizzes:Array.from({length:50}, (_, i) => ({
      id:`q-${i}`, published:date, topic:'Test', question:`Frage ${i}`,
      options:['A','B','C','D'], correctIndex:i%4, explanation:'Test'
    }))});
  }
};
