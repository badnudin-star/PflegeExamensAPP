// TEST ONLY: contains no real learning content or credentials. Never configure this
// fixture as the app's production catalog, and never bind it to a database.
export function syntheticCatalog(now = new Date()) {
  const date = new Intl.DateTimeFormat('en-CA', {
    timeZone:'Europe/Berlin', year:'numeric', month:'2-digit', day:'2-digit'
  }).format(now);
  return {
    schemaVersion:1,
    fixtureOnly:true,
    quizzes:Array.from({length:50}, (_, i) => ({
      id:`synthetic-ranking-${i}`,
      published:date,
      topic:'Ausschließlich technischer Test',
      question:`Künstliche Testfrage ${i + 1}: Welche Antwort hat die Nummer ${i % 4 + 1}?`,
      options:['Testantwort 1','Testantwort 2','Testantwort 3','Testantwort 4'],
      correctIndex:i % 4,
      explanation:'Künstliche Testdaten, keine medizinischen Inhalte.'
    }))
  };
}

export default {
  async fetch(request) {
    const url = new URL(request.url);
    if (request.method !== 'GET' || url.pathname !== '/content' || url.search) {
      return new Response('Nicht gefunden', {status:404, headers:{'Cache-Control':'no-store'}});
    }
    return Response.json(syntheticCatalog(), {
      headers:{'Cache-Control':'no-store', 'X-Fixture-Only':'synthetic-ranking-test'}
    });
  }
};
