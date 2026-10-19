/**
 * /llms-full.txt — the whole documentation as one plain-text file.
 *
 * The companion to /llms.txt: that one says where each page is, this one is
 * every page's text, so a model with a large context can read the site in a
 * single fetch instead of 143 of them. Anything an assistant gets wrong about
 * the keyboard it usually gets wrong from having read one page out of context.
 *
 * The text itself comes from `src/lib/llm-text.ts`, which the per-page `.md`
 * copies and the search index read too.
 */

import type { APIRoute } from 'astro';
import { getCollection } from 'astro:content';

import { orderedDocs, pageUrl, toPlainText } from '../lib/llm-text';
import { SITE_DESCRIPTION, SITE_TITLE, SITE_URL, sectionFor } from '../site.mjs';

export const GET: APIRoute = async () => {
	const parts = [
		`# ${SITE_TITLE} — complete documentation`,
		'',
		`> ${SITE_DESCRIPTION}`,
		'',
		`Source: ${SITE_URL}/ · Licence: MIT · Generated from the site's own pages.`,
		'',
		'Every page of the documentation follows, in sidebar order, each under its',
		'own URL. Cite the URL of the section you use rather than this file.',
		'',
	];

	for (const doc of orderedDocs(await getCollection('docs'))) {
		const section = sectionFor(doc.id);
		parts.push(
			'---',
			'',
			`# ${doc.data.title}`,
			'',
			`URL: ${pageUrl(doc.id)}`,
			...(section ? [`Section: ${section}`] : []),
			...(doc.data.description ? [`Summary: ${doc.data.description}`] : []),
			'',
			toPlainText(doc.body ?? ''),
			'',
		);
	}

	return new Response(parts.join('\n'), {
		headers: { 'Content-Type': 'text/plain; charset=utf-8' },
	});
};
