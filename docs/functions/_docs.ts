/**
 * Searching the documentation, for the two doors an assistant comes in by:
 * the plain search API (`/api/docs/search`) and the MCP server (`/mcp`).
 *
 * The index is a static file the build writes (`/ai/docs-index.json`, from
 * src/pages/ai/docs-index.json.ts): every page cut into `##`/`###` sections.
 * It is fetched from the site's own assets once per isolate and kept, so a
 * search costs a ranking pass over a few thousand short texts and nothing else.
 *
 * Ranking is BM25 over words, with the page title and the heading counted
 * three times, because a question names the feature ("import a Rime
 * dictionary") and the heading is where the docs name it too. Chinese and
 * Japanese text has no spaces, so Han and kana runs are indexed as single
 * characters and pairs: a question asked in Chinese still finds 联想 under
 * "What comes next (联想)".
 */

export interface Env {
	/** The static site itself. Pages binds it for every Function. */
	ASSETS: { fetch(input: Request | string | URL): Promise<Response> };
}

export interface Chunk {
	url: string;
	title: string;
	heading: string;
	section: string;
	summary: string;
	text: string;
}

export interface Page {
	id: string;
	url: string;
	markdown: string;
	title: string;
	section: string;
	summary: string;
}

interface Index {
	version: number;
	site: string;
	pages: Page[];
	chunks: Chunk[];
}

/** The index shape these Functions read; see the `version` note in docs-index.json.ts. */
const INDEX_VERSION = 1;

export interface Hit {
	title: string;
	heading: string;
	section: string;
	url: string;
	/** The page's Markdown copy, for reading the whole of it. */
	markdown: string;
	score: number;
	text: string;
}

interface Ranked {
	index: Index;
	/** Per chunk: term → count. */
	terms: Map<string, number>[];
	lengths: number[];
	averageLength: number;
	/** Term → how many chunks hold it. */
	documentFrequency: Map<string, number>;
	markdownByPage: Map<string, string>;
}

let cached: Promise<Ranked> | null = null;

/** The ranked index, loaded once per isolate. A failed load is retried on the next request. */
export function load(env: Env, request: Request): Promise<Ranked> {
	if (!cached) {
		cached = build(env, request).catch((error) => {
			cached = null;
			throw error;
		});
	}
	return cached;
}

async function build(env: Env, request: Request): Promise<Ranked> {
	const response = await env.ASSETS.fetch(new URL('/ai/docs-index.json', request.url));
	if (!response.ok) throw new Error(`docs index: HTTP ${response.status}`);
	const index = (await response.json()) as Index;
	if (index.version !== INDEX_VERSION) throw new Error(`docs index: version ${index.version}`);

	const terms: Map<string, number>[] = [];
	const lengths: number[] = [];
	const documentFrequency = new Map<string, number>();
	for (const chunk of index.chunks) {
		const counts = new Map<string, number>();
		const add = (text: string, weight: number) => {
			for (const token of tokenize(text)) counts.set(token, (counts.get(token) ?? 0) + weight);
		};
		add(`${chunk.title} ${chunk.heading}`, 3);
		add(chunk.summary, 2);
		add(chunk.text, 1);
		let length = 0;
		for (const [token, count] of counts) {
			length += count;
			documentFrequency.set(token, (documentFrequency.get(token) ?? 0) + 1);
		}
		terms.push(counts);
		lengths.push(length);
	}
	const averageLength = lengths.reduce((a, b) => a + b, 0) / Math.max(1, lengths.length);
	const markdownByPage = new Map(index.pages.map((page) => [page.url, page.markdown]));
	return { index, terms, lengths, averageLength, documentFrequency, markdownByPage };
}

/** Words nobody searches by. Kept short: "not", "only" and "without" carry meaning in these docs. */
const STOP = new Set(
	'a an and are as at be by can do does for from how i in is it its me my of on or so that the this to was what when where which who why will with you your'.split(
		' '
	)
);

/**
 * Other spellings of what the docs call one thing. One-directional: the
 * left-hand word in a question also searches for the right-hand ones.
 */
const ALIASES: Record<string, string[]> = {
	sougou: ['sogou'],
	搜狗: ['sogou'],
	fcitx5: ['fcitx'],
	chinese: ['pinyin', 'cjk'],
	中文: ['chinese', 'pinyin'],
	拼音: ['pinyin'],
	词库: ['dictionary', 'word', 'list'],
	japanese: ['kana', 'cjk'],
	cantonese: ['jyutping'],
	swipe: ['glide', 'gesture'],
	glide: ['swipe'],
	dict: ['dictionary'],
	wordlist: ['word', 'list'],
	autocorrect: ['correction'],
	ai: ['llm'],
	llm: ['ai'],
	skin: ['theme'],
	backup: ['restore'],
};

const HAN_OR_KANA = /[぀-ヿ㐀-䶿一-鿿豈-﫿]/;

/** Light English stemming: enough that "dictionaries" finds "dictionary" and "importing" finds "import". */
function stem(word: string): string {
	if (word.length <= 4) return word;
	if (word.endsWith('ies')) return word.slice(0, -3) + 'y';
	if (word.endsWith('ing') && word.length > 6) return word.slice(0, -3);
	if (word.endsWith('ed') && word.length > 5) return word.slice(0, -2);
	if (word.endsWith('es') && /(?:ss|x|ch|sh)es$/.test(word)) return word.slice(0, -2);
	if (word.endsWith('s') && !word.endsWith('ss')) return word.slice(0, -1);
	return word;
}

/** A text as search terms: stemmed words, and Han/kana as single characters and pairs. */
export function tokenize(text: string): string[] {
	const out: string[] = [];
	const normalised = text.normalize('NFKC').toLowerCase();
	for (const run of normalised.match(/[\p{L}\p{N}]+/gu) ?? []) {
		if (HAN_OR_KANA.test(run)) {
			// Mixed runs ("v3版本") split into their Latin and their Han parts.
			for (const part of run.match(/[぀-ヿ㐀-䶿一-鿿豈-﫿]+|[^぀-ヿ㐀-䶿一-鿿豈-﫿]+/g) ?? []) {
				if (!HAN_OR_KANA.test(part)) {
					if (!STOP.has(part)) out.push(stem(part));
					continue;
				}
				const chars = [...part];
				for (let i = 0; i < chars.length; i++) {
					out.push(chars[i]);
					if (i + 1 < chars.length) out.push(chars[i] + chars[i + 1]);
				}
			}
			continue;
		}
		if (STOP.has(run)) continue;
		out.push(stem(run));
	}
	return out;
}

/** The terms a query searches for, aliases included, each once. */
function queryTerms(query: string): string[] {
	const raw = query.normalize('NFKC').toLowerCase();
	const words = tokenize(raw);
	const terms = new Set(words);
	for (const [word, also] of Object.entries(ALIASES)) {
		// A Latin alias matches a whole word ("ai" is not in "chain"); a Han one anywhere.
		const asked = HAN_OR_KANA.test(word) ? raw.includes(word) : words.includes(stem(word));
		if (asked) for (const term of also.flatMap((a) => tokenize(a))) terms.add(term);
	}
	return [...terms];
}

const K1 = 1.2;
const B = 0.75;

/**
 * The [limit] best sections for [query], at most one per heading anchor and
 * at most two per page, so five results are five places to look rather than
 * five slices of one long page.
 */
export function search(ranked: Ranked, query: string, limit: number, section?: string): Hit[] {
	const terms = queryTerms(query);
	if (!terms.length) return [];
	const total = ranked.index.chunks.length;
	const scored: { at: number; score: number }[] = [];
	const wanted = section?.trim().toLowerCase();

	for (let at = 0; at < total; at++) {
		const chunk = ranked.index.chunks[at];
		if (wanted && !chunk.section.toLowerCase().includes(wanted)) continue;
		const counts = ranked.terms[at];
		let score = 0;
		let matched = 0;
		for (const term of terms) {
			const tf = counts.get(term);
			if (!tf) continue;
			matched++;
			const df = ranked.documentFrequency.get(term) ?? 0;
			const idf = Math.log(1 + (total - df + 0.5) / (df + 0.5));
			score += (idf * tf * (K1 + 1)) / (tf + K1 * (1 - B + (B * ranked.lengths[at]) / ranked.averageLength));
		}
		// A section that answers more of the question beats one that repeats one word of it.
		if (score > 0) scored.push({ at, score: score * (0.5 + matched / terms.length) });
	}
	scored.sort((a, b) => b.score - a.score);

	const perPage = new Map<string, number>();
	const hits: Hit[] = [];
	for (const { at, score } of scored) {
		const chunk = ranked.index.chunks[at];
		const page = chunk.url.split('#')[0];
		const seen = perPage.get(page) ?? 0;
		if (seen >= 2) continue;
		if (hits.some((hit) => hit.url === chunk.url)) continue;
		perPage.set(page, seen + 1);
		hits.push({
			title: chunk.title,
			heading: chunk.heading,
			section: chunk.section,
			url: chunk.url,
			markdown: ranked.markdownByPage.get(page) ?? '',
			score: Math.round(score * 100) / 100,
			text: chunk.text,
		});
		if (hits.length >= limit) break;
	}
	return hits;
}

/**
 * The page a reference names, as the address of its Markdown copy, or null
 * when no page matches. Takes what an assistant is likely to have: a full URL
 * (with or without `#anchor` or `.md`), a path, or a page id.
 */
export function resolvePage(ranked: Ranked, reference: string): Page | null {
	const cleaned = reference
		.trim()
		.replace(/^https?:\/\/[^/]+/i, '')
		.replace(/[?#].*$/, '')
		.replace(/\.md$/, '')
		.replace(/^\/+|\/+$/g, '');
	const id = cleaned === '' ? 'index' : cleaned;
	return ranked.index.pages.find((page) => page.id === id) ?? null;
}

/** The pages, optionally one section's. */
export function pages(ranked: Ranked, section?: string): Page[] {
	const wanted = section?.trim().toLowerCase();
	return wanted ? ranked.index.pages.filter((page) => page.section.toLowerCase().includes(wanted)) : ranked.index.pages;
}

/** Hits as Markdown, the way a model reads them best: heading, address, then the section's text. */
export function formatHits(query: string, hits: Hit[]): string {
	if (!hits.length) {
		return `No section of the WM Keyboard documentation matches "${query}". Try other words, or list_pages to browse.`;
	}
	return hits
		.map(
			(hit, i) =>
				`## ${i + 1}. ${hit.title}${hit.heading ? ` › ${hit.heading}` : ''}\n` +
				`URL: ${hit.url}\nSection: ${hit.section}\n\n${hit.text}`
		)
		.join('\n\n---\n\n');
}

/** Open to every origin: the same text the pages already print. */
export const CORS: Record<string, string> = {
	'Access-Control-Allow-Origin': '*',
	'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
	'Access-Control-Allow-Headers': 'Content-Type, Accept, Authorization, Mcp-Session-Id, Mcp-Protocol-Version, Last-Event-ID',
	'Access-Control-Expose-Headers': 'Mcp-Session-Id, Mcp-Protocol-Version',
	'Access-Control-Max-Age': '86400',
};

export function json(body: unknown, init: ResponseInit = {}): Response {
	return new Response(JSON.stringify(body), {
		...init,
		headers: {
			'Content-Type': 'application/json; charset=utf-8',
			// Left open in robots.txt, so an assistant browsing for a user may call
			// it, but nothing here belongs in a search index: every answer is
			// already on a real page.
			'X-Robots-Tag': 'noindex',
			...CORS,
			...(init.headers ?? {}),
		},
	});
}
