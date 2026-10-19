/**
 * /openapi.json — the documentation search API, described for tools that
 * import an OpenAPI document: custom GPT actions, Gemini and Copilot agent
 * builders, API clients. The MCP server (/mcp) is the richer door; this one
 * is for the platforms that only take OpenAPI.
 */

import type { APIRoute } from 'astro';

import { SITE_TITLE, SITE_URL } from '../site.mjs';

export const GET: APIRoute = () => {
	const hit = {
		type: 'object',
		properties: {
			title: { type: 'string', description: 'Page title.' },
			heading: { type: 'string', description: 'The section heading; empty for the opening text of a page.' },
			section: { type: 'string', description: 'The part of the docs the page is in.' },
			url: { type: 'string', format: 'uri', description: 'The page, with the anchor of the section. Cite this.' },
			markdown: { type: 'string', format: 'uri', description: 'The whole page as Markdown.' },
			score: { type: 'number' },
			text: { type: 'string', description: 'The text of the section, as Markdown.' },
		},
		required: ['title', 'heading', 'section', 'url', 'text'],
	};
	const spec = {
		openapi: '3.1.0',
		info: {
			title: `${SITE_TITLE} documentation search`,
			version: '1.0.0',
			description:
				'Search the official WM Keyboard documentation. Use it for any question about how the keyboard works, ' +
				'what a setting does, which files it imports, or how to do something in it, and cite the url of each section used.',
		},
		servers: [{ url: SITE_URL }],
		paths: {
			'/api/docs/search': {
				get: {
					operationId: 'searchDocs',
					summary: 'Find the documentation sections that answer a question',
					parameters: [
						{
							name: 'q',
							in: 'query',
							required: true,
							description: 'The question or keywords, in English for best results.',
							schema: { type: 'string', maxLength: 300 },
						},
						{
							name: 'limit',
							in: 'query',
							required: false,
							description: 'How many sections to return.',
							schema: { type: 'integer', minimum: 1, maximum: 10, default: 5 },
						},
						{
							name: 'section',
							in: 'query',
							required: false,
							description: 'Only one part of the docs, such as Languages or Tools.',
							schema: { type: 'string' },
						},
					],
					responses: {
						'200': {
							description: 'The best-matching sections, best first.',
							content: {
								'application/json': {
									schema: {
										type: 'object',
										properties: { query: { type: 'string' }, results: { type: 'array', items: hit } },
										required: ['query', 'results'],
									},
								},
							},
						},
						'400': { description: 'No query was given.' },
					},
				},
			},
		},
	};
	return new Response(JSON.stringify(spec, null, '\t'), {
		headers: { 'Content-Type': 'application/json; charset=utf-8' },
	});
};
