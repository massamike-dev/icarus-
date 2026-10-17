// Explicit natural-language opt-in, shared by Chat and voice. Time-sensitive
// words alone are not consent to send a private conversation to live search.
export function searchIntent(text) {
  const match = String(text || '').trim().match(/^(?:please\s+|(?:can|could|would) you\s+(?:please\s+)?)?(?:search (?:the )?(?:web|internet)|look up)\b[\s:,-]*(.*)$/is);
  if (!match || /^to\b/i.test(match[1])) return null;
  const query = match[1].replace(/^for(?:\s+|$)/i, '').trim();
  return {query};
}

export function standaloneSearchQuery(query) {
  return Boolean(/[\p{L}\p{N}]/u.test(query) && !/^(?:it|this|that|those|these|the above|what we discussed|what i said)[.!?]*$/i.test(query));
}
