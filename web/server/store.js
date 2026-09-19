import { mkdir, readFile, rename, writeFile } from 'node:fs/promises';
import { dirname } from 'node:path';
import { randomUUID } from 'node:crypto';

export const emptyStore = () => ({ users: [], sessions: [], conversations: [], messages: [], memories: [], turns: [], actionRequests: [] });

export class JsonStore {
  constructor(file) { this.file = file; this.queue = Promise.resolve(); }
  async read() {
    try { return { ...emptyStore(), ...JSON.parse(await readFile(this.file, 'utf8')) }; }
    catch (error) { if (error.code === 'ENOENT') return emptyStore(); throw error; }
  }
  async update(mutator) {
    const operation = this.queue.then(async () => {
      const data = await this.read();
      const result = await mutator(data);
      await mkdir(dirname(this.file), { recursive: true });
      const temporary = `${this.file}.${randomUUID()}.tmp`;
      await writeFile(temporary, `${JSON.stringify(data, null, 2)}\n`, { mode: 0o600 });
      await rename(temporary, this.file);
      return result;
    });
    this.queue = operation.catch(() => {});
    return operation;
  }
}
