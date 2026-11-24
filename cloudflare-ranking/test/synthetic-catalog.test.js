import {test} from 'node:test';
import assert from 'node:assert/strict';
import catalog, {syntheticCatalog} from '../fixtures/synthetic-test-catalog.mjs';

const url = 'https://synthetic.example/content';

test('synthetic catalog supplies only 50 predictable technical questions for Berlin today', () => {
  // Around a Berlin midnight and a daylight-saving transition, not the UTC day.
  for (const [iso, date] of [
    ['2026-01-01T22:59:59Z','2026-01-01'],
    ['2026-01-01T23:00:00Z','2026-01-02'],
    ['2026-03-29T21:59:59Z','2026-03-29'],
    ['2026-03-29T22:00:00Z','2026-03-30'],
  ]) {
    const value = syntheticCatalog(new Date(iso));
    assert.equal(value.schemaVersion,1);
    assert.equal(value.fixtureOnly,true);
    assert.equal(value.quizzes.length,50);
    assert.equal(new Set(value.quizzes.map(q=>q.id)).size,50);
    for (const [i, q] of value.quizzes.entries()) {
      assert.equal(q.id,`synthetic-ranking-${i}`);
      assert.equal(q.published,date);
      assert.equal(q.topic,'Ausschließlich technischer Test');
      assert.equal(q.options.length,4);
      assert.equal(new Set(q.options).size,4);
      assert.equal(q.correctIndex,i % 4);
      assert.match(q.explanation,/keine medizinischen Inhalte/);
    }
  }
});

test('only GET /content returns explicitly marked, uncached test data', async () => {
  const response = await catalog.fetch(new Request(url));
  assert.equal(response.status,200);
  assert.equal(response.headers.get('X-Fixture-Only'),'synthetic-ranking-test');
  assert.equal(response.headers.get('Cache-Control'),'no-store');
  const body = await response.json();
  assert.equal(body.fixtureOnly,true);
  assert.equal(body.quizzes.length,50);
  for (const request of [new Request('https://synthetic.example/'),
    new Request(url + '?anything=1'), new Request(url,{method:'POST'})]) {
    assert.equal((await catalog.fetch(request)).status,404);
  }
});
