// Retrieval only: no new memories, inferred tasks, or cross-conversation data.
const stopWords = new Set('a an and are as at be been but by can could did do does for from had has have how i in is it its me my of on or our please that the their them there these they this to us was we were what when where which who why will with would you your'.split(' '));
const terms = value => new Set((String(value || '').toLowerCase().match(/[\p{L}\p{N}]{3,}/gu) || []).filter(word => !stopWords.has(word)));
const relevance = (content, query) => [...terms(content)].reduce((score, word) => score + (query.has(word) ? 1 : 0), 0);
const recentFirst = (a, b) => b.score - a.score || b.position - a.position;

export function buildAssistantContext(messages, memories) {
  const query = terms(messages.findLast(message => message.role === 'user')?.content);
  const history = messages.slice(-30).map(({role, content}) => ({role: role === 'assistant' ? 'assistant' : 'user', content: String(content)}));
  const rankedMemory = memories.map((memory, position) => ({...memory, position, score: relevance(memory.content, query)}));
  // Recent corrections always survive; relevant older preferences can replace
  // otherwise unrelated older memories within the existing 20-memory window.
  const selectedMemory = [...rankedMemory.slice(-10), ...rankedMemory.slice(0, -10).sort(recentFirst).slice(0, 10)]
    .sort((a, b) => a.position - b.position);
  const older = messages.slice(0, Math.max(0, messages.length - 30))
    .map((message, position) => ({...message, position, score: relevance(message.content, query)}))
    .filter(message => message.role === 'user');
  const first = older[0];
  const selectedEarlier = first ? [first, ...older.slice(1).filter(message => message.score > 0).sort(recentFirst).slice(0, 5)] : [];
  selectedEarlier.sort((a, b) => a.position - b.position);
  return {
    history,
    reference: {
      savedMemory: selectedMemory.map(({content, createdAt}) => ({content: String(content), ...(createdAt ? {createdAt} : {})})),
      earlierConversation: selectedEarlier.map(({role, content, position}) => ({role, content: String(content).slice(0, 2000), position, truncated: String(content).length > 2000})),
      historyOmitted: messages.length > history.length + selectedEarlier.length || selectedEarlier.some(message => String(message.content).length > 2000),
      memoryOmitted: memories.length > selectedMemory.length,
    },
  };
}
