import test from 'node:test';
import assert from 'node:assert/strict';
import {researchRequest,verifiedResearch} from './research.mjs';
test('explicit website uses actual page read; no invented facts or model needed',async()=>{
  assert.equal(researchRequest('Проверь сайт agbis.ru').url,'https://agbis.ru');let seen;
  const r=await verifiedResearch({read:async url=>{seen=url;return {url,title:'АГБИС',text:'Автоматизация химчисток',truncated:false};},search:async()=>{throw Error('must not search instead of provided URL');}},{text:'Проверь сайт agbis.ru'});
  assert.equal(seen,'https://agbis.ru');assert.equal(r.evidence[0].kind,'page');assert.ok(r.evidence[0].sha256);assert.match(r.answer,/Автоматизация химчисток/);assert.ok(!r.answer.includes('ликвидирована'));
});
test('search snippets are distinguished from read pages and unrelated registry links are filtered',async()=>{
  const r=await verifiedResearch({search:async()=>({results:[{title:'Компания АГБИС',url:'https://agbis.ru',snippet:'Программное обеспечение АГБИС'},{title:'Български компании',url:'https://registri.bg',snippet:'ЕИК'}]}),read:async()=>{throw Error('HTTP503');}},{text:'Найди официальный сайт компании АГБИС'});
  assert.equal(r.evidence.length,1);assert.equal(r.evidence[0].kind,'search-snippet');assert.match(r.answer,/не проверенные факты/);assert.ok(!r.answer.includes('registri.bg'));
});
test('unreadable source produces concrete error instead of asserting company/person does not exist',async()=>{
  const r=await verifiedResearch({read:async()=>{throw Error('provider verification');}},{text:'Проверь сайт https://example.org'});assert.match(r.answer,/provider verification/);assert.ok(!r.answer.includes('Интернет недоступен'));
});
