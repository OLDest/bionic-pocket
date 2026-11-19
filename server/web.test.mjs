import test from 'node:test';
import assert from 'node:assert/strict';
import {createWebAccess,parseSearch,visibleText} from './web.mjs';
const resolve=async()=>[{address:'93.184.216.34'}];
test('search parses actual titles, redirected URLs and snippets',()=>{
 const html='<a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fgo.dev%2Fdoc%2F&amp;rut=x">Go &amp; Docs</a><a class="result__snippet">Official <b>documentation</b>.</a>';
 assert.deepEqual(parseSearch(html),[{title:'Go & Docs',url:'https://go.dev/doc/',snippet:'Official documentation.'}]);
});
test('page text removes executable markup and preserves readable content',()=>{
 assert.equal(visibleText('<script>bad()</script><h1>Title</h1><p>A &lt; B</p><style>hidden</style>'),'Title\n\nA < B');
});
test('web read validates every redirect and never sends credentials',async()=>{
 let count=0;
 const web=createWebAccess({resolve,fetch:async(url,options)=>{assert.equal(options.headers.Authorization,undefined);count++;return count===1?new Response('',{status:302,headers:{location:'/docs'}}):new Response('<title>Docs</title><p>Hello</p>',{headers:{'content-type':'text/html'}});}});
 assert.equal((await web.read('https://example.org')).url,'https://example.org/docs');assert.equal(count,2);
 const hostile=createWebAccess({resolve,fetch:async()=>new Response('',{status:302,headers:{location:'http://127.0.0.1:1236/health'}})});
 await assert.rejects(hostile.read('https://example.org'),/public Internet/);
});
test('private, credential-bearing and file URLs are rejected; long pages are bounded',async()=>{
 const web=createWebAccess({resolve,fetch:async()=>new Response('a'.repeat(7000),{headers:{'content-type':'text/plain'}})});
 for(const url of ['file:///etc/passwd','http://localhost/','http://10.0.0.1/','https://u:p@example.org/','http://[::1]/'])await assert.rejects(web.read(url));
 const page=await web.read('https://example.org');assert.equal(page.text.length,6000);assert.equal(page.truncated,true);
});
test('search failures are explicit provider errors, not fabricated results',async()=>{
 const web=createWebAccess({resolve,fetch:async()=>new Response('<form id="challenge-form">captcha</form>')});
 await assert.rejects(web.search('test'),/verification/);await assert.rejects(web.search(''));
});
