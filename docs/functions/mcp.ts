/**
 * /mcp — the documentation as a Model Context Protocol server.
 *
 * Add `https://wmkeyboard.pages.dev/mcp` to Claude, ChatGPT, Cursor, VS Code
 * or any other MCP client and it can search these docs and read whole pages
 * while it answers, instead of answering about WM Keyboard from training data
 * that predates half the features. The keyboard's own AI chat can add it the
 * same way, under its MCP servers.
 *
 * Streamable HTTP, stateless: every POST carries one JSON-RPC message (or a
 * batch, for clients on the 2025-03-26 revision) and gets a plain JSON answer.
 * There is nothing to subscribe to, so GET answers 405, which the transport
 * spec allows, and no session id is issued. No sign-in: the tools only read
 * the public documentation.
 */
import { CORS, type Env, formatHits, json, load, pages, resolvePage, search } from './_docs';

/** Newest first. A client asking for one of these gets it; any other gets the newest. */
const PROTOCOL_VERSIONS = ['2025-11-25', '2025-06-18', '2025-03-26', '2024-11-05'];

const SERVER_INFO = {
	name: 'wmkeyboard-docs',
	title: 'WM Keyboard documentation',
	version: '1.0.0',
	websiteUrl: 'https://wmkeyboard.pages.dev/',
};

const INSTRUCTIONS =
	'Official documentation of WM Keyboard, a free, open-source Android keyboard. ' +
	'Use search_docs for any question about how WM Keyboard works, what a setting does, ' +
	'which files it can import, or how to do something in it. Read the whole page with read_page ' +
	'when a section is not enough. Cite the URL of the section you used. ' +
	'The docs describe the current release; if they do not cover something, say so rather than guess.';

const READ_ONLY = { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false };

const TOOLS = [
	{
		name: 'search_docs',
		title: 'Search the WM Keyboard docs',
		description:
			'Search the WM Keyboard documentation. Returns the best-matching sections, each with its page title, ' +
			'heading, URL and full text. Write the query as plain words or a question, in English for best results.',
		inputSchema: {
			type: 'object',
			properties: {
				query: { type: 'string', description: 'What to look for, such as "import a Rime dictionary for Chinese".' },
				limit: { type: 'integer', minimum: 1, maximum: 10, description: 'How many sections to return. Default 5.' },
				section: {
					type: 'string',
					description: 'Only search one part of the docs, such as "Languages", "Tools" or "Privacy & security".',
				},
			},
			required: ['query'],
		},
		annotations: READ_ONLY,
	},
	{
		name: 'read_page',
		title: 'Read a WM Keyboard docs page',
		description:
			'Read one documentation page in full, as Markdown. Pass the URL from a search result or list_pages, ' +
			'or a path such as /languages/cjk/.',
		inputSchema: {
			type: 'object',
			properties: { page: { type: 'string', description: 'The page URL, path or id.' } },
			required: ['page'],
		},
		annotations: READ_ONLY,
	},
	{
		name: 'list_pages',
		title: 'List the WM Keyboard docs pages',
		description: 'List every documentation page with its URL and one-line summary, optionally for one section.',
		inputSchema: {
			type: 'object',
			properties: {
				section: { type: 'string', description: 'Only this section, such as "Typing" or "Reference".' },
			},
		},
		annotations: READ_ONLY,
	},
];

interface RpcRequest {
	jsonrpc?: string;
	id?: string | number | null;
	method?: string;
	params?: Record<string, unknown>;
}

class RpcError extends Error {
	constructor(
		readonly code: number,
		message: string
	) {
		super(message);
	}
}

const text = (value: string) => ({ content: [{ type: 'text', text: value }] });
const toolError = (value: string) => ({ content: [{ type: 'text', text: value }], isError: true });

async function handle(message: RpcRequest, env: Env, request: Request): Promise<unknown> {
	const params = message.params ?? {};
	switch (message.method) {
		case 'initialize': {
			const asked = String(params.protocolVersion ?? '');
			return {
				protocolVersion: PROTOCOL_VERSIONS.includes(asked) ? asked : PROTOCOL_VERSIONS[0],
				capabilities: { tools: { listChanged: false }, resources: { listChanged: false } },
				serverInfo: SERVER_INFO,
				instructions: INSTRUCTIONS,
			};
		}
		case 'ping':
			return {};
		case 'tools/list':
			return { tools: TOOLS };
		case 'tools/call':
			return callTool(String(params.name ?? ''), (params.arguments ?? {}) as Record<string, unknown>, env, request);
		case 'resources/list': {
			const ranked = await load(env, request);
			return {
				resources: [
					{
						uri: new URL('/llms.txt', request.url).href,
						name: 'llms.txt',
						title: 'Index of every page',
						mimeType: 'text/plain',
					},
					...ranked.index.pages.map((page) => ({
						uri: page.markdown,
						name: page.id,
						title: page.title,
						description: page.summary || undefined,
						mimeType: 'text/markdown',
					})),
				],
			};
		}
		case 'resources/templates/list':
			return { resourceTemplates: [] };
		case 'resources/read': {
			const uri = String(params.uri ?? '');
			const target = new URL(uri, request.url);
			if (target.origin !== new URL(request.url).origin) throw new RpcError(-32002, `Resource not found: ${uri}`);
			const response = await env.ASSETS.fetch(target);
			if (!response.ok) throw new RpcError(-32002, `Resource not found: ${uri}`);
			const mimeType = uri.endsWith('.md') ? 'text/markdown' : 'text/plain';
			return { contents: [{ uri, mimeType, text: await response.text() }] };
		}
		case 'prompts/list':
			return { prompts: [] };
		default:
			throw new RpcError(-32601, `Method not found: ${message.method}`);
	}
}

async function callTool(name: string, args: Record<string, unknown>, env: Env, request: Request) {
	const ranked = await load(env, request);
	switch (name) {
		case 'search_docs': {
			const query = String(args.query ?? '').trim().slice(0, 300);
			if (!query) return toolError('Pass what to look for as "query".');
			const limit = Math.min(10, Math.max(1, Number(args.limit) || 5));
			const section = typeof args.section === 'string' ? args.section : undefined;
			return text(formatHits(query, search(ranked, query, limit, section)));
		}
		case 'read_page': {
			const reference = String(args.page ?? args.url ?? '');
			const page = resolvePage(ranked, reference);
			if (!page) return toolError(`No page "${reference}". Use search_docs or list_pages to find one.`);
			const response = await env.ASSETS.fetch(new URL(page.markdown));
			if (!response.ok) return toolError(`Could not read ${page.url} (HTTP ${response.status}).`);
			return text(await response.text());
		}
		case 'list_pages': {
			const section = typeof args.section === 'string' ? args.section : undefined;
			const list = pages(ranked, section);
			if (!list.length) return toolError(`No section called "${section}".`);
			return text(
				list.map((page) => `- [${page.title}](${page.url}) (${page.section || 'Home'})${page.summary ? `: ${page.summary}` : ''}`).join('\n')
			);
		}
		default:
			throw new RpcError(-32602, `Unknown tool: ${name}`);
	}
}

/** One message's answer, or null for a notification (which gets none). */
async function answer(message: RpcRequest, env: Env, request: Request): Promise<unknown | null> {
	const isRequest = message.id !== undefined && message.id !== null;
	if (!message.method) {
		// A response or an error from the client; this server never asks it anything.
		return null;
	}
	if (!isRequest) return null;
	try {
		return { jsonrpc: '2.0', id: message.id, result: await handle(message, env, request) };
	} catch (error) {
		const code = error instanceof RpcError ? error.code : -32603;
		const reason = error instanceof Error ? error.message : 'Internal error';
		return { jsonrpc: '2.0', id: message.id, error: { code, message: reason } };
	}
}

export const onRequestPost: PagesFunction<Env> = async ({ request, env }) => {
	let body: unknown;
	try {
		body = await request.json();
	} catch {
		return json({ jsonrpc: '2.0', id: null, error: { code: -32700, message: 'Parse error' } }, { status: 400 });
	}
	const headers = { 'Mcp-Protocol-Version': request.headers.get('Mcp-Protocol-Version') ?? PROTOCOL_VERSIONS[0] };
	if (Array.isArray(body)) {
		const replies = (await Promise.all(body.map((message) => answer(message as RpcRequest, env, request)))).filter(
			(reply) => reply !== null
		);
		return replies.length ? json(replies, { headers }) : new Response(null, { status: 202, headers: CORS });
	}
	const reply = await answer(body as RpcRequest, env, request);
	return reply === null ? new Response(null, { status: 202, headers: CORS }) : json(reply, { headers });
};

/**
 * No server-to-client stream to open. A browser landing here gets a line
 * saying what the address is for instead of a bare 405 page.
 */
export const onRequestGet: PagesFunction = async ({ request }) => {
	const wantsStream = (request.headers.get('Accept') ?? '').includes('text/event-stream');
	return new Response(
		wantsStream
			? null
			: 'This is the MCP server of the WM Keyboard documentation (Streamable HTTP). ' +
				'Add this address to an MCP client; see https://wmkeyboard.pages.dev/reference/ai-access/\n',
		{ status: 405, headers: { ...CORS, Allow: 'POST, OPTIONS', 'Content-Type': 'text/plain; charset=utf-8' } }
	);
};

/** Stateless: there is no session to end. */
export const onRequestDelete: PagesFunction = async () =>
	new Response(null, { status: 405, headers: { ...CORS, Allow: 'POST, OPTIONS' } });

export const onRequestOptions: PagesFunction = async () => new Response(null, { status: 204, headers: CORS });
