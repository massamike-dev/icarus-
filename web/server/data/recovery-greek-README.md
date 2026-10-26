# Greek recovery vocabulary

This is a derived data file, not a new dictionary. The original Greek spellings
remain alongside the Latin spelling in `recovery-greek-words.json`.

## Source and license

- Upstream: LibreOffice `dictionaries`, `el_GR/el_GR.dic`, dictionary version 0.9.
- Pinned commit: `8236765eb34de01083e0554c3ccafa6d8ff1ee18`.
- Dictionary URL: https://raw.githubusercontent.com/LibreOffice/dictionaries/8236765eb34de01083e0554c3ccafa6d8ff1ee18/el_GR/el_GR.dic
- Upstream README: https://raw.githubusercontent.com/LibreOffice/dictionaries/8236765eb34de01083e0554c3ccafa6d8ff1ee18/el_GR/README_el_GR.txt
- Dictionary SHA-256: `e5b9b9c2cf05bbc59e03fe302b462dae85968f822f4fc219a8ed2879d6943720`.
- Upstream notice SHA-256: `8b942846bba64ac1b28c38aee294f1667d0063e2162d59030d0da09332e8c342`.
- Upstream offers MPL 1.1/GPL 2.0/LGPL 2.1; this derivative uses MPL 1.1.
  Preserve `recovery-greek-upstream-notice.txt`, the included full
  `recovery-greek-MPL-1.1.txt`, this notice and the editable data/generator when
  redistributing. The source data and generator must remain available to
  recipients under the applicable license. Review the license before release;
  this note is not legal advice.

The preserved upstream notice has whitespace-only normalization (trailing spaces/blank lines removed); its source SHA above refers to the downloaded original.

Modified for ICARUS on October 26, 2026 (selection v2): retained lowercase
Greek entries, removed accents, applied the explicit Greeklish-style map in
the generator and deduplicated transformed output. Candidates must have 5–8
Latin letters with at least two vowels. A documented conservative fragment
blocklist removes known Greek slang/sensitive families and alarming English
collisions. A SHA-256-based deterministic ordering spreads selection across
the alphabet. Greedy selection retains at most one word per first-four-letter
prefix and excludes any pair with Levenshtein distance below two. The final
2,048 entries, selected from 103,807 eligible canonical candidates, are sorted for inspection; this order is not generation bias.

This replaces the initial shortest-first list, which contained abbreviations,
sexual/slang terms and many nearly identical inflections. Those issues were
found during review and corrected before final delivery. This algorithm is
not frequency ranking or an ISO 843/phonetic transliteration guarantee. No
words were invented. The original dictionary includes inflected and uncommon
words. The source list is authoritative as spell-checker data, not a guarantee
that these are all familiar, easy to pronounce, or free of sensitive meanings.

**Recommended before real-user release:** preview the vocabulary/typing
experience with the user and obtain qualified Greek-language/content review.
Automated filtering and this limited AI-assisted review do not certify full
semantic cleanliness or usability. Release readiness remains the owner’s
decision after the stated limitations are disclosed. Any replacement list
must retain 2,048 unique canonical entries and rerun entropy, collision,
normalization and recovery-flow tests. Do not weaken rate limits based on
word-list secrecy: the list is public.

Final dataset SHA-256: `35966ce60064aec63b0b29dd19c35da339a24c082af9a083873ccb3d6e57d2b5`.

The second review additionally excluded known sexual, derogatory and religious-slur
fragments, including `ntavatz`, `ches`, `styt`, `tsats`, `pidix`, `kayl`, `mpini`,
`gyft`, `gkiaoy` and `lagne`; conservative anatomical fragments `kolpik` and
`mastos` are also excluded. The complete reproducible blocklist is in the generator.

## Reproduction

From the repository root:

    python scripts/generate-recovery-words.py

The script downloads only pinned text data, verifies SHA-256 before processing,
and runs no downloaded code. To reproduce offline, provide a directory holding
the exact `el_GR.dic` and `README_el_GR.txt` using `--source-dir DIRECTORY`.

All 2,048 final Latin spellings are unique after canonicalization; no two
selected words have Levenshtein distance one. Two
independent uniform `crypto.randomInt` selections yield 4,194,304 ordered pairs
(22 bits), with repeated words allowed. Canonical input accepts ASCII case and
a single word separator (hyphen or whitespace); no spelling aliases, fuzzy
matching, Greek-script substitutions or unordered pairs are accepted.

This small secret requires expiry, durable attempt limits, single-use atomic
consumption and a server-keyed digest. It is not suitable as a long-term backup
code or password. Random generation must never use Math.random, timestamps or
model-generated choices. The vocabulary is public and secrecy is not assumed.
