/**
 * The documentation as text a language model reads: one place that turns a
 * page's MDX into Markdown, shared by every machine-facing route.
 *
 * Four readers: `/llms-full.txt` (every page in one file), `/<page>.md` (one
 * page, the llmstxt.org convention), `/ai/docs-index.json` (the chunks the
 * search API and the MCP server rank) and `/llms.txt` (which links the `.md`
 * copies). They used to be one private function inside llms-full.txt.ts, and a
 * second copy is how two of them would start disagreeing about what a page says.
 *
 * The MDX is stripped rather than rendered: imports, JSX components and the
 * `{/* shot: ... *\/}` screenshot placeholders are page machinery, not prose,
 * and a model reading `<PhoneFrame caption=...>` learns nothing from it. What
 * survives is the headings, the prose and the code blocks, which is what the
 * page says.
 */

import GithubSlugger from 'github-slugger';
import type { CollectionEntry } from 'astro:content';

import { isNoindex } from './robots.mjs';
import { SECTIONS, SITE_URL, sectionFor } from '../site.mjs';

type Doc = CollectionEntry<'docs'>;

/** MDX down to the Markdown a reader would see, with every link absolute. */
export function toPlainText(body: string) {
	return (
		body
			// Frontmatter is already read from `data`.
			.replace(/^---\n[\s\S]*?\n---\n/, '')
			// `import X from '...'` lines, and the blank line after them.
			.replace(/^import .*(?:\n|$)/gm, '')
			.replace(/^export .*(?:\n|$)/gm, '')
			// JSX comments, including the screenshot placeholders.
			.replace(/\{\/\*[\s\S]*?\*\/\}/g, '')
			// Self-closing components: `<SettingsPath path="Privacy" />`. Keep the
			// quoted text, which is usually the only content the tag carries.
			.replace(/<([A-Z][\w.]*)\s([^>]*?)\/>/g, (_, _tag, attrs: string) => {
				const quoted = [...attrs.matchAll(/="([^"]*)"|='([^']*)'/g)].map((m) => m[1] ?? m[2]);
				return quoted.length ? quoted.join(' — ') : '';
			})
			.replace(/<[A-Z][\w.]*\s*\/>/g, '')
			// Opening and closing component tags, leaving whatever was between them.
			.replace(/<\/?[A-Z][\w.]*(?:\s[^>]*)?>/g, '')
			// Starlight's asides keep their text, lose their fence.
			.replace(/^:::\w+(?:\[[^\]]*\])?\s*$/gm, '')
			.replace(/^:::\s*$/gm, '')
			// Site-relative links become absolute: a model reading this file has
			// no page to resolve `/languages/dictionaries/` against, and a broken
			// citation is worse than no citation.
			.replace(/\]\((\/[^)]*)\)/g, (_, href: string) => `](${SITE_URL}${href})`)
			// Three or more blank lines collapse to one.
			.replace(/\n{3,}/g, '\n\n')
			.trim()
	);
}

/** The page's own address, as a person would open it. */
export const pageUrl = (id: string) => (id === 'index' || id === '' ? `${SITE_URL}/` : `${SITE_URL}/${id}/`);

/** The page's Markdown copy: `/typing/glide-typing.md`, `/index.md` for the landing page. */
export const markdownUrl = (id: string) => `${SITE_URL}/${id === '' ? 'index' : id}.md`;

/** Every page a machine may read, landing page first, then in sidebar order. */
export function orderedDocs(docs: Doc[]): Doc[] {
	const byId = new Map(docs.map((doc) => [doc.id, doc]));
	const order: string[] = [];
	for (const { label, directory } of SECTIONS) {
		const ids = docs
			.filter((doc) => sectionFor(doc.id) === label && doc.id.split('/')[0] === directory)
			.map((doc) => doc.id)
			// An `overview` is the section's front door, so it leads.
			.sort((a, b) => {
				const front = (id: string) => (/\/(index|overview)$/.test(id) ? 0 : 1);
				return front(a) - front(b) || a.localeCompare(b);
			});
		order.push(...ids);
	}
	// The landing page first, then anything a section didn't claim.
	const ids = ['index', ...order, ...docs.map((d) => d.id).filter((id) => id !== 'index' && !order.includes(id))];
	return ids.map((id) => byId.get(id)).filter((doc): doc is Doc => !!doc && !isNoindex(`/${doc.id}/`));
}

/** One page as a standalone Markdown document, with the facts a citation needs on top. */
export function pageMarkdown(doc: Doc): string {
	const section = sectionFor(doc.id);
	return [
		`# ${doc.data.title}`,
		'',
		...(doc.data.description ? [`> ${doc.data.description}`, ''] : []),
		`URL: ${pageUrl(doc.id)}`,
		...(section ? [`Section: ${section}`] : []),
		'',
		toPlainText(doc.body ?? ''),
		'',
	].join('\n');
}

/** One searchable piece of a page: a section under one heading. */
export interface DocChunk {
	/** Address of the page, with the heading's anchor when there is one. */
	url: string;
	/** Page title. */
	title: string;
	/** The heading this piece sits under; empty for the page's opening text. */
	heading: string;
	/** Sidebar section. */
	section: string;
	/** The page's one-line summary, on its first piece only. */
	summary: string;
	text: string;
}

/**
 * Longest piece the index keeps. A search result is pasted into a prompt
 * whole, and a small on-device model reading four 6,000-character pieces has
 * no room left to answer; a piece this size still holds a whole how-to.
 */
const MAX_CHUNK = 1800;

/**
 * A page cut at its `##` to `####` headings, each piece under the anchor
 * Starlight gives that heading (github-slugger, the same one rehype-slug
 * uses), so a result links to the paragraph and not just the page.
 */
export function pageChunks(doc: Doc): DocChunk[] {
	const text = toPlainText(doc.body ?? '');
	const slugger = new GithubSlugger();
	const base = pageUrl(doc.id);
	const section = sectionFor(doc.id) ?? '';
	const out: DocChunk[] = [];

	let heading = '';
	let anchor = '';
	let lines: string[] = [];
	let inFence = false;

	const flush = () => {
		const body = lines.join('\n').trim();
		lines = [];
		if (!body && heading === '') return;
		for (const piece of split(body)) {
			out.push({
				url: anchor ? `${base}#${anchor}` : base,
				title: doc.data.title,
				heading,
				section,
				summary: out.length === 0 ? (doc.data.description ?? '') : '',
				text: piece,
			});
		}
	};

	for (const line of text.split('\n')) {
		if (/^(```|~~~)/.test(line)) inFence = !inFence;
		const match = !inFence && /^(#{2,4})\s+(.+?)\s*#*$/.exec(line);
		if (match) {
			flush();
			heading = match[2].replace(/\[([^\]]*)\]\([^)]*\)/g, '$1').replace(/[*_`]/g, '');
			anchor = slugger.slug(heading);
			continue;
		}
		lines.push(line);
	}
	flush();
	return out;
}

/** A long section in pieces of at most [MAX_CHUNK], cut between paragraphs. */
function split(body: string): string[] {
	if (body.length <= MAX_CHUNK) return [body];
	const pieces: string[] = [];
	let current = '';
	for (const paragraph of body.split(/\n{2,}/)) {
		if (current && current.length + paragraph.length + 2 > MAX_CHUNK) {
			pieces.push(current);
			current = '';
		}
		// A single paragraph longer than a piece (a big table) is cut by line.
		if (paragraph.length > MAX_CHUNK) {
			for (const line of paragraph.split('\n')) {
				if (current && current.length + line.length + 1 > MAX_CHUNK) {
					pieces.push(current);
					current = '';
				}
				current = current ? `${current}\n${line}` : line;
			}
			continue;
		}
		current = current ? `${current}\n\n${paragraph}` : paragraph;
	}
	if (current) pieces.push(current);
	return pieces;
}
