import {createHash} from 'node:crypto';
const stop=new Set('что ты знаешь известно расскажи про об о компания компании компанию ооо ао найди найти поищи проверь проверить официальный официальную сайт сайты интернете интернет актуальная информация информацию пожалуйста цене цены средние напиши покажи latest search official'.split(' '));
function words(text){return (text.toLocaleLowerCase('ru').match(/[\p{L}\d]{3,}/gu)??[]).filter(w=>!stop.has(w));}
function translit(text){const a='абвгдеёжзийклмнопрстуфхцчшщыэюя',b=['a','b','v','g','d','e','e','zh','z','i','i','k','l','m','n','o','p','r','s','t','u','f','h','c','ch','sh','sch','y','e','yu','ya'];return text.toLowerCase().replace(/[а-яё]/g,c=>b[a.indexOf(c)]??c).replace(/[ьъ]/g,'');}
export function researchRequest(text){
  if(/(?:найди|поищи|проверь)[\s\S]{0,50}(?:файл|папк|диск|локальн|в проекте)/i.test(text)&&!/(?:https?:\/\/|интернет|сайт|веб|web)/i.test(text))return null;
  const explicit=/(?:найди|поищи|погугли|search|look up|проверь\s+(?:сайт|информац)|официальн[\s\S]{0,30}сайт|что[\s\S]{0,20}знаешь[\s\S]{0,30}компан|расскажи[\s\S]{0,25}(?:компан|[А-ЯЁ][а-яё]+\s+[А-ЯЁ][а-яё]+)|сколько\s+стоит|по\s*ч[её]м|актуальн[\s\S]{0,20}(?:цен|информац))/i.test(text);
  if(!explicit)return null;
  const raw=text.match(/https?:\/\/[^\s<>]+|(?<![@\w])(?:www\.)?(?:[a-z0-9][a-z0-9-]*\.)+[a-z]{2,}(?:\/[^\s<>]*)?/i)?.[0]?.replace(/[.,!?;)]+$/,'');
  return {query:text.slice(0,400),url:raw?(raw.startsWith('http')?raw:'https://'+raw):null};
}
function excerpt(text,max=650){return String(text??'').replace(/\s+/g,' ').trim().slice(0,max);}
const cleanTitle=text=>String(text??'').replace(/[\[\]\r\n]/g,' ').slice(0,200);
export async function verifiedResearch(web,input,options={}){
  const request=researchRequest(input.text);if(!request)throw Error('Research intent required');
  const observedAt=new Date(options.now?.()??Date.now()).toISOString();
  const evidence=[],errors=[];
  async function readPage(url){try{const result=await web.read(url);evidence.push({kind:'page',url:result.url,title:result.title,text:result.text,truncated:!!result.truncated,observedAt,sha256:createHash('sha256').update(result.text).digest('hex')});}catch(error){errors.push({url,error:error.message});}}
  if(request.url)await readPage(request.url);
  else{
    const result=await web.search(request.query);const terms=words(input.text);const accepted=(result.results??[]).filter(source=>{
      if(!terms.length)return true;const haystack=translit(source.title+' '+source.snippet+' '+source.url);const significant=terms.filter(w=>!/^\d+$/.test(w));return !significant.length||significant.filter(w=>haystack.includes(translit(w))).length>=Math.min(2,significant.length);
    }).slice(0,5);
    for(const source of accepted)evidence.push({kind:'search-snippet',...source,query:request.query,observedAt});
    for(const source of accepted.slice(0,2))await readPage(source.url);
  }
  const pages=evidence.filter(e=>e.kind==='page');
  let answer;
  if(pages.length){answer='Открытые источники проверены '+observedAt+'. Ниже — выдержки из реально прочитанных страниц:\n\n'+pages.map(p=>`**${cleanTitle(p.title)||p.url}**\n[Открытая страница](${p.url})\n> ${excerpt(p.text)}${p.truncated?'\nПрочитана доступная часть страницы.':''}`).join('\n\n')+'\n\nЭти выдержки не подтверждают дополнительные выводы о людях, руководстве, банкротстве или финансах, если таких сведений в них нет.';}
  else{
    const hints=evidence.filter(e=>e.kind==='search-snippet');
    answer=hints.length?'Найдены результаты поиска, но страницы прочитать не удалось. Это поисковые описания, а не проверенные факты:\n\n'+hints.map(p=>`- [${cleanTitle(p.title)}](${p.url}): ${excerpt(p.snippet,300)}`).join('\n'):'По этому запросу подтверждающие страницы не получены. Я не буду придумывать сведения.';
  }
  if(errors.length)answer+='\n\nОшибки чтения:\n'+errors.map(e=>`- ${e.url}: ${e.error}`).join('\n');
  return {answer,evidence,errors,sources:evidence.map(e=>({url:e.url,title:e.title,kind:e.kind,observedAt:e.observedAt}))};
}
