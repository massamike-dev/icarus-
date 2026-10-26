// Greek vocabulary data is MPL-1.1; see data/recovery-greek-README.md.
// These are recovery challenges, NOT passwords or long-term backup secrets.
import {randomInt} from 'node:crypto';
import {readFileSync} from 'node:fs';

const source = JSON.parse(readFileSync(new URL('./data/recovery-greek-words.json', import.meta.url), 'utf8'));
export const RECOVERY_WORDS = Object.freeze(source.entries.map(entry => entry.latin));
const vocabulary = new Set(RECOVERY_WORDS);
if (RECOVERY_WORDS.length !== 2048 || vocabulary.size !== 2048 ||
    RECOVERY_WORDS.some(word => !/^[a-z]{5,8}$/.test(word))) {
  throw new Error('invalid_recovery_vocabulary');
}

export function generateRecoveryCode() {
  // Each draw is independent and uniform, including the valid case of a
  // repeated word. There are 2048² = 4,194,304 equally likely ordered pairs.
  return `${RECOVERY_WORDS[randomInt(RECOVERY_WORDS.length)]}-${RECOVERY_WORDS[randomInt(RECOVERY_WORDS.length)]}`;
}

export function normalizeRecoveryCode(input) {
  if (typeof input !== 'string' || input.length > 64 || !/^[A-Za-z\s-]+$/.test(input)) return null;
  const match = input.trim().toLowerCase().match(/^([a-z]{5,8})(?:\s*-\s*|\s+)([a-z]{5,8})$/);
  if (!match || !vocabulary.has(match[1]) || !vocabulary.has(match[2])) return null;
  return `${match[1]}-${match[2]}`;
}
