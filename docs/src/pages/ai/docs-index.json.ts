/**
 * `/ai/docs-index.json` — the documentation cut into searchable pieces.
 *
 * What the search API (`functions/api/docs/search.ts`) and the MCP server
 * (`functions/mcp.ts`) rank. Built here, at build time, from the same text as
 * the `.md` copies, so the Functions never parse MDX and an answer quotes
 * exactly what the page says. Each piece is a `##` or `###` section with the
 * anchor of its heading, so a result links to the paragraph.
 *
 * `version` is the shape, not the content: a Function that finds a version it
 * does not know answers 503 rather than ranking fields that moved.
 */

import type { APIRoute } from 'astro';
import { getCollection } from 'astro:content';

import { markdownUrl, orderedDocs, pageChunks, pageUrl } from '../../lib/llm-text';
import { SITE_TITLE, SITE_URL, sectionFor } from '../../site.mjs';

export const GET: APIRoute = async () => {
	const docs = orderedDocs(await getCollection('docs'));
	const index = {
		version: 1,
		site: SITE_URL,
		title: SITE_TITLE,
		generated: new Date().toISOString(),
		pages: docs.map((doc) => ({
			id: doc.id || 'index',
			url: pageUrl(doc.id),
			markdown: markdownUrl(doc.id),
			title: doc.data.title,
			section: sectionFor(doc.id) ?? '',
			summary: doc.data.description ?? '',
		})),
		chunks: docs.flatMap((doc) => pageChunks(doc)),
	};
	return new Response(JSON.stringify(index), {
		headers: { 'Content-Type': 'application/json; charset=utf-8' },
	});
};
