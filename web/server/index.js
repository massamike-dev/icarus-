import { createServer } from 'node:http';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { JsonStore } from './store.js';
import { createHandler } from './app.js';
import { bootstrapPrivateTester, privateTestConfig } from './private-test.js';

const here=dirname(fileURLToPath(import.meta.url));
const port=Number(process.env.PORT||3000);
const privateTest=privateTestConfig(process.env);
if(privateTest&&!process.env.ICARUS_DATA_FILE)throw new Error('Private testing requires an explicit ICARUS_DATA_FILE on isolated storage.');
const store=new JsonStore(process.env.ICARUS_DATA_FILE||join(here,'../data/icarus.json'));
const handler=createHandler({store,publicDir:join(here,'../dist')});
await bootstrapPrivateTester(store,privateTest);
createServer(handler).listen(port,()=>console.log(`ICARUS listening on ${port}`));
