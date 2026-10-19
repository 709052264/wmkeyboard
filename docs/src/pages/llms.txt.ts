/**
 * /llms.txt — the site's map for a language model, in the llmstxt.org shape.
 *
 * An assistant asked about WM Keyboard reaches for one page and answers from
 * it. Left to a crawl it picks whichever page ranked, which for a settings
 * question is often a reference stub rather than the guide that explains the
 * feature. This file states the structure once — every page, grouped by
 * section, with its own one-line description — so the model that reads it
 * knows which page to fetch and can cite the right URL.
 *
 * Kept generated rather than written by hand: a hand-kept index of 143 pages
 * is an index that stops matching the site after the second new page.
 */

import type { APIRoute } from 'astro';
import { getCollection } from 'astro:content';

import { markdownUrl } from '../lib/llm-text';
import { isNoindex } from '../lib/robots.mjs';
import { SECTIONS, SITE_DESCRIPTION, SITE_TITLE, SITE_URL, sectionFor } from '../site.mjs';

export const GET: APIRoute = async () => {
	const docs = (await getCollection('docs')).filter((doc) => doc.id !== 'index' && !isNoindex(`/${doc.id}/`));

	const lines = [
		`# ${SITE_TITLE}`,
		'',
		`> ${SITE_DESCRIPTION}`,
		'',
		'WM Keyboard is a free, open-source (MIT) Android keyboard. Prediction,',
		'autocorrect, glide typing and every dictionary run on the device; the app',
		'ships no analytics, and typing never reaches a network. This is its',
		'documentation. Each link below is the Markdown copy of a page; fetch it for',
		'the detail behind its one-line summary, and cite the page itself, which is',
		'the same address without `.md` (`/typing/glide-typing.md` is',
		'`/typing/glide-typing/`).',
		'',
		'## Start here',
		'',
		`- [Documentation home](${SITE_URL}/index.md): what the keyboard is, and the feature tour.`,
		`- [Full text of every page](${SITE_URL}/llms-full.txt): this documentation as one plain-text file.`,
		`- [Search the documentation](${SITE_URL}/api/docs/search?q=import+rime+dictionary): \`GET /api/docs/search?q=<words>\` answers with the best-matching sections as JSON, each with its page URL and text. Described in [OpenAPI](${SITE_URL}/openapi.json).`,
		`- [MCP server](${SITE_URL}/mcp): the same search and page reading as Model Context Protocol tools (Streamable HTTP, no sign-in). Setup for each assistant: [Using the docs with AI](${SITE_URL}/reference/ai-access.md).`,
		'',
	];

	for (const { label, directory } of SECTIONS) {
		const pages = docs
			.filter((doc) => sectionFor(doc.id) === label && doc.id.split('/')[0] === directory)
			// An `overview` is the section's front door, so it leads.
			.sort((a, b) => {
				const front = (id: string) => (/\/(index|overview)$/.test(id) ? 0 : 1);
				return front(a.id) - front(b.id) || a.id.localeCompare(b.id);
			});
		if (!pages.length) continue;

		lines.push(`## ${label}`, '');
		for (const page of pages) {
			const description = page.data.description ? `: ${page.data.description}` : '';
			lines.push(`- [${page.data.title}](${markdownUrl(page.id)})${description}`);
		}
		lines.push('');
	}

	return new Response(lines.join('\n'), {
		headers: { 'Content-Type': 'text/plain; charset=utf-8' },
	});
};
