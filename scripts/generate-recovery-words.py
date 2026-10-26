#!/usr/bin/env python3
"""Generate the Greek recovery vocabulary from a pinned LibreOffice dictionary.
Only data is downloaded; no source code is imported or executed.
Usage: python scripts/generate-recovery-words.py [--source-dir DIRECTORY]
"""
import argparse
import hashlib
import json
import pathlib
import re
import unicodedata
import urllib.request

COMMIT = '8236765eb34de01083e0554c3ccafa6d8ff1ee18'
BASE = f'https://raw.githubusercontent.com/LibreOffice/dictionaries/{COMMIT}/el_GR/'
SOURCES = {
    'el_GR.dic': 'e5b9b9c2cf05bbc59e03fe302b462dae85968f822f4fc219a8ed2879d6943720',
    'README_el_GR.txt': '8b942846bba64ac1b28c38aee294f1667d0063e2162d59030d0da09332e8c342',
}
# Explicit, deterministic Greeklish-style transliteration. This is not a claim
# of ISO 843 compliance or phonetic equivalence; users copy the rendered form.
MAP = dict(zip('αβγδεζηθικλμνξοπρσςτυφχψω',
               ['a','v','g','d','e','z','i','th','i','k','l','m','n','x','o','p','r','s','s','t','y','f','ch','ps','o']))

# Conservative exclusions: known Greek sensitive/slang families and alarming
# English collisions. This is a heuristic, not a complete semantic review.
EXCLUDED_FRAGMENTS = (
    'gam', 'peo', 'porn', 'poyts', 'pouts', 'moyn', 'vyz', 'kolo', 'kole',
    'malak', 'archid', 'arxid', 'skat', 'pipa', 'pipi', 'sex', 'nazi',
    'fuck', 'shit', 'cunt', 'bitch', 'cock', 'dick', 'piss', 'slut', 'rape',
    'ass', 'fag', 'psofi', 'psofo', 'thanat', 'nekro', 'sperm',
    'moich', 'ptom', 'erot', 'viast', 'prostyt', 'mastoy', 'aftokt',
    'ntavatz', 'ches', 'styt', 'tsats', 'pidix', 'kayl', 'mpini',
    'gyft', 'gkiaoy', 'lagne', 'kolpik', 'mastos',
)

def one_edit_neighbours(word):
    alphabet = 'abcdefghijklmnopqrstuvwxyz'
    for i in range(len(word)):
        yield word[:i] + word[i+1:]
        for c in alphabet:
            yield word[:i] + c + word[i+1:]
    for i in range(len(word)+1):
        for c in alphabet:
            yield word[:i] + c + word[i:]

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--source-dir', type=pathlib.Path)
    args = ap.parse_args()
    data = {}
    for name, expected in SOURCES.items():
        raw = (args.source_dir/name).read_bytes() if args.source_dir else urllib.request.urlopen(BASE+name, timeout=60).read()
        assert hashlib.sha256(raw).hexdigest() == expected, f'Unexpected source checksum: {name}'
        data[name] = raw
    lines = data['el_GR.dic'].decode('iso8859-7').splitlines()
    assert int(lines[0]) == len(lines)-1, 'Dictionary count mismatch'
    candidates = {}
    for entry in lines[1:]:
        word = entry.split('/',1)[0]
        # Exclude capitalized proper-name entries, punctuation, digits, and all
        # non-Greek letters. Retain only genuine entries, not affix inventions.
        if word != word.lower() or not word.isalpha():
            continue
        base = ''.join(c for c in unicodedata.normalize('NFD', word) if unicodedata.category(c) != 'Mn')
        if not all(c in MAP for c in base):
            continue
        latin = ''.join(MAP[c] for c in base)
        if not re.fullmatch('[a-z]{5,8}', latin) or sum(c in 'aeioy' for c in latin) < 2 or any(fragment in latin for fragment in EXCLUDED_FRAGMENTS):
            continue
        # Security-critical: uniqueness is enforced AFTER every transformation.
        if latin not in candidates or word < candidates[latin]:
            candidates[latin] = word
    # Deterministic pseudorandom order distributes entries across the alphabet;
    # it is NOT how recovery codes are selected at runtime.
    ordered = sorted(candidates, key=lambda x:hashlib.sha256(('icarus-greek-v2:' + x).encode()).digest())
    chosen, prefixes, near = [], set(), set()
    for latin in ordered:
        if latin[:4] in prefixes or latin in near:
            continue
        chosen.append(latin)
        prefixes.add(latin[:4])
        near.update(one_edit_neighbours(latin))
        if len(chosen) == 2048:
            break
    chosen.sort()
    assert len(chosen) == len(set(chosen)) == 2048
    entries = [{'latin':latin,'greek':candidates[latin]} for latin in chosen]
    root = pathlib.Path(__file__).resolve().parents[1]
    target = root/'web/server/data/recovery-greek-words.json'
    result = {'source':BASE+'el_GR.dic','sourceSha256':SOURCES['el_GR.dic'],
      'license':'MPL-1.1 (chosen from MPL-1.1/GPL-2.0/LGPL-2.1 tri-license)',
      'transliteration':'Explicit accent-stripped Greeklish-style map; not ISO 843',
      'selection':'v2: 5-8 letters, >=2 vowels, conservative exclusion fragments, one per first4 prefix, minimum Levenshtein distance2, SHA256 deterministic ordering',
      'count':len(entries),'entries':entries}
    target.write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n')
    # Normalize whitespace only; preserve all upstream notice words.
    notice = data['README_el_GR.txt'].decode('utf-8')
    (target.parent/'recovery-greek-upstream-notice.txt').write_text('\n'.join(line.rstrip() for line in notice.splitlines()).rstrip()+'\n')
    print(f'{len(candidates)} unique eligible canonical words; selected {len(entries)}; pairs={len(entries)**2}; entropy=22 bits')
    print('generated sha256:', hashlib.sha256(target.read_bytes()).hexdigest())

if __name__ == '__main__':
    main()
