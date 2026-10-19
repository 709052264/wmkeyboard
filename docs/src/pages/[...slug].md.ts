/**
 * `/<page>.md` — every page as plain Markdown, at its own address plus `.md`.
 *
 * The llmstxt.org convention: an agent that has a page's URL, from llms.txt,
 * a search result or a link a user pasted, asks for the same path with `.md`
 * and gets the text without the navigation, the scripts and the sidebar of 143
 * links that make up most of the HTML. `/typing/glide-typing/` reads as
 * `/typing/glide-typing.md`; the landing page is `/index.md`. Every HTML page
 * names its copy in a `<link rel="alternate" type="text/markdown">` (Head.astro),
 * so an agent that only has the HTML finds it too.
 */

import type { APIRoute, GetStaticPaths } from 'astro';
import { getCollection, type CollectionEntry } from 'astro:content';

import { orderedDocs, pageMarkdown } from '../lib/llm-text';

export const getStaticPaths: GetStaticPaths = async () =>
	orderedDocs(await getCollection('docs')).map((doc) => ({
		params: { slug: doc.id || 'index' },
		props: { doc },
	}));

export const GET: APIRoute = ({ props }) =>
	new Response(pageMarkdown((props as { doc: CollectionEntry<'docs'> }).doc), {
		headers: { 'Content-Type': 'text/markdown; charset=utf-8' },
	});
