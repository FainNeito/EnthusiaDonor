import { Miniflare } from 'miniflare';
import { readFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
const ref=process.argv[2];
const read=(name,baseline)=>baseline?execFileSync('git',['show',`${ref}:ops/donor-webhook/pull-inbox/${name}`],{encoding:'utf8'}):readFileSync(new URL(name,import.meta.url),'utf8');
for(const baseline of [true,false]) {
 const mf=new Miniflare({modules:true,script:'export default {fetch(){return new Response("ok")}}',compatibilityDate:'2026-07-01',host:'127.0.0.1',port:0,d1Databases:['DB']});
 try {const db=await mf.getD1Database('DB');const schema=read('schema.sql',baseline).split('-- TRIGGERS START');
 for(const sql of schema[0].split(';').filter(x=>x.trim())) await db.prepare(sql).run();
 for(const sql of schema[1].split('-- trigger-break')) await db.prepare(sql).run();
 const run=async(name,args)=>{const r=await db.prepare(read(name+'.sql',baseline)).bind(...args).run();console.log(JSON.stringify({revision:baseline?'baseline':'candidate',name,reads:r.meta.rows_read,writes:r.meta.rows_written}));return r};
 const id='a'.repeat(64),lease='b'.repeat(64),body=new Uint8Array([1,2,3]);
 await run('enqueue',['test',id,body,'s',10000,67108864]);await run('enqueue',['test',id,body,'s',10000,67108864]);
 await run('claim',['test',lease,90,5]);await run('ack',['test',id,lease]);
 await db.prepare("UPDATE inbox SET completed_at=unixepoch()-8000000 WHERE id=?").bind(id).run();await run('prune',['test',7776000]);
 await db.prepare(`WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM c WHERE x<100)
 INSERT INTO inbox(source,id,body,signature,attempts,lease_until) SELECT 'test',printf('%064x',x),x'01','s',5,0 FROM c`).run();
 await run('quarantine',['test',5]);
 } finally {await mf.dispose()}
}
