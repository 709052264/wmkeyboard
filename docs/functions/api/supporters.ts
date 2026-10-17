/**
 * GET /api/supporters — everyone the webhook has recorded, newest first.
 *
 * Read by the Supporters page's refresh button, the app's, and
 * scripts/sync-supporters.mjs, which bakes the list into the build. Open to
 * any origin: it is the same list the page already prints.
 */
import { type Env, type Supporter, CORS, KEY_PREFIX, json } from './_supporters';

export const onRequestGet: PagesFunction<Env> = async ({ env }) => {
	if (!env.SUPPORTERS) return json({ supporters: [] }, { status: 503 });

	const supporters: Supporter[] = [];
	let cursor: string | undefined;
	do {
		const page = await env.SUPPORTERS.list<Supporter>({ prefix: KEY_PREFIX, cursor });
		for (const key of page.keys) if (key.metadata?.name) supporters.push(key.metadata);
		cursor = page.list_complete ? undefined : page.cursor;
	} while (cursor);

	supporters.sort((a, b) => b.at.localeCompare(a.at));
	return json(
		{ updated: new Date().toISOString(), supporters },
		{ headers: { 'Cache-Control': 'public, max-age=60' } }
	);
};

export const onRequestOptions: PagesFunction = async () => new Response(null, { status: 204, headers: CORS });
