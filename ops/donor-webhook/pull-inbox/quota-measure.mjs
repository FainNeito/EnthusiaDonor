import {Miniflare} from 'miniflare';
import {readFileSync} from 'node:fs';
const mf=new Miniflare({modules:true,script:'export default {fetch(){return new Response("ok")}}',compatibilityDate:'2026-07-01',host:'127.0.0.1',port:0,d1Databases:['DB']});
try {const db=await mf.getD1Database('DB'); for(const s of readFileSync(new URL('schema.sql',import.meta.url),'utf8').split(';').filter(x=>x.trim())) await db.prepare(s).run();
for(const scenario of ['done','pending_exhausted','pending_leased']) {await db.prepare('DELETE FROM inbox').run();
const values=scenario==='done'?"'done',NULL,NULL,unixepoch(),0,0":scenario==='pending_exhausted'?"'pending',x'01','fixture',NULL,5,0":"'pending',x'01','fixture',NULL,1,unixepoch()+60";
await db.prepare(`WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM c WHERE x<10000) INSERT INTO inbox(source,id,state,body,signature,completed_at,attempts,lease_until) SELECT 'test',printf('%064x',x),${values} FROM c`).run();
for(const [name,bind] of [['status',['test',5]],['claim',['test','token',90,5]],['quarantine',['test',5]],['prune',['test',7776000]],['enqueue',['test','f'.repeat(64),new Uint8Array([1]),'s',10000,67108864]]]) {const result=await db.prepare(readFileSync(new URL(name+'.sql',import.meta.url),'utf8')).bind(...bind).run(); console.log(JSON.stringify({scenario,name,rows_read:result.meta.rows_read,rows_written:result.meta.rows_written}));}}
} finally {await mf.dispose()}
