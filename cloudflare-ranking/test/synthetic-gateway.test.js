import {test} from 'node:test';
import assert from 'node:assert/strict';
import gateway from '../fixtures/synthetic-ranking-gateway.mjs';

const host='https://pflegeexamen-ranking-test.beispiel.workers.dev';
const key='a1'.repeat(32);
const env={TEST_ONLY_KEY:key, QUIZ_CATALOG_URL:'https://pflegeexamen-ranking-katalog-test.beispiel.workers.dev/content'};
const req=(url, candidate=key) => new Request(url,{headers:{'X-Pflege-Test-Key':candidate}});

test('synthetic gateway fails closed before calling ranking for invalid configuration or key', async () => {
  // Throw if the ranking code is reached at an endpoint that would access D1.
  const trap={...env, DB:{prepare() {throw new Error('ranking must not be reached');}}};
  for (const [request, config] of [
    [req(host+'/leaderboard','b2'.repeat(32)), trap],
    [new Request(host+'/leaderboard'), trap],
    [req(host+'/leaderboard'), {...trap, TEST_ONLY_KEY:''}],
    [req(host+'/leaderboard'), {...trap, TEST_ONLY_KEY:'not-a-secret'}],
    [req('https://pflegeexamen-content.beispiel.workers.dev/leaderboard'), trap],
    [req('http://pflegeexamen-ranking-test.beispiel.workers.dev/leaderboard'), trap],
    [req('https://pflegeexamen-ranking-test.beispiel.workers.dev:8443/leaderboard'), trap],
    [req(host+'/leaderboard'), {...trap, QUIZ_CATALOG_URL:'https://pflegeexamen-content.beispiel.workers.dev/content'}],
    [req(host+'/leaderboard'), {...trap, QUIZ_CATALOG_URL:'https://pflegeexamen-ranking-katalog-test.anderes.workers.dev/content'}],
    [req(host+'/leaderboard'), {...trap, QUIZ_CATALOG_URL:env.QUIZ_CATALOG_URL+'?foo=bar'}],
  ]) {
    const result=await gateway.fetch(request, config, {});
    assert.equal(result.status,404);
    assert.equal(result.headers.get('Cache-Control'),'no-store');
  }
});

test('a valid isolated test configuration delegates to the real ranking Worker', async () => {
  const result=await gateway.fetch(req(host+'/health'),env,{});
  assert.equal(result.status,200);
  assert.deepEqual(await result.json(),{ok:true,service:'quiz-ranking-draft'});
});
