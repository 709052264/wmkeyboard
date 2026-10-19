/**
 * GET /api/docs/search?q=<words>[&limit=5][&section=<name>] — the documentation
 * sections that best answer a question, as JSON.
 *
 * For anything that can make an HTTP request but does not speak MCP: the
 * keyboard's own AI tool (`search_wm_docs`), a custom GPT action (described by
 * /openapi.json), a script. Each hit is one `##`/`###` section with its page
 * URL and anchor, so the answer can cite the paragraph it came from.
 */
import { CORS, type Env, json, load, search } from '../../_docs';

const MAX_LIMIT = 10;

export const onRequestGet: PagesFunction<Env> = async ({ request, env }) => {
	const params = new URL(request.url).searchParams;
	const query = (params.get('q') ?? params.get('query') ?? '').trim().slice(0, 300);
	if (!query) return json({ error: 'Pass the question as ?q=' }, { status: 400 });
	const limit = Math.min(MAX_LIMIT, Math.max(1, Number.parseInt(params.get('limit') ?? '5', 10) || 5));

	let ranked;
	try {
		ranked = await load(env, request);
	} catch {
		return json({ error: 'The search index is not available right now.' }, { status: 503 });
	}
	const results = search(ranked, query, limit, params.get('section') ?? undefined);
	return json(
		{ query, results },
		// Same answer until the next deploy; a short cache keeps a burst cheap.
		{ headers: { 'Cache-Control': 'public, max-age=300' } }
	);
};

export const onRequestOptions: PagesFunction = async () => new Response(null, { status: 204, headers: CORS });
