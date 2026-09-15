import { createServer } from 'node:http';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { JsonStore } from './store.js';
import { createHandler } from './app.js';

const here=dirname(fileURLToPath(import.meta.url));
const port=Number(process.env.PORT||3000);
const store=new JsonStore(process.env.ICARUS_DATA_FILE||join(here,'../data/icarus.json'));
createServer(createHandler({store,publicDir:join(here,'../dist')})).listen(port,()=>console.log(`ICARUS listening on ${port}`));
