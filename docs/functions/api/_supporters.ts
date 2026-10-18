/**
 * Shared by the SupportKori webhook, the Google Play tip endpoint and the
 * public supporters list.
 *
 * Each supporter is one KV key, `s:<createdAt>:<deliveryId>`, with everything
 * the list shows kept in the key's metadata. One key per event means two tips
 * arriving together never race on a shared array, and `list()` hands back the
 * metadata with the keys, so reading the whole list is one call with no `get`
 * per entry. A key's metadata is capped at 1 KiB, which is why names are cut
 * to [NAME_LIMIT].
 *
 * Amounts are not stored. The page credits people, it does not rank them.
 * To take someone off the list, delete their key in the Cloudflare dashboard
 * (Workers & Pages › KV › the namespace bound as SUPPORTERS).
 */

export interface Env {
	/** KV namespace; see the commented binding in wrangler.jsonc. */
	SUPPORTERS?: KVNamespace;
	/** The webhook's signing secret, set with `wrangler pages secret put`. */
	SUPPORTKORI_SECRET?: string;
	/**
	 * The Google Cloud service account key JSON that may read this app's
	 * orders through the Play Developer API, for play-tip.ts. Set with
	 * `wrangler pages secret put PLAY_SERVICE_ACCOUNT`.
	 */
	PLAY_SERVICE_ACCOUNT?: string;
	/**
	 * "true" lets a Play license tester's purchase onto the list, for an end
	 * to end test. Leave it unset in production: test orders cost nothing.
	 */
	PLAY_TIPS_ALLOW_TEST?: string;
}

export type SupporterKind = 'tip' | 'member';

export interface Supporter {
	name: string;
	kind: SupporterKind;
	/** ISO timestamp from SupportKori's `createdAt`, or the Play order's purchase time. */
	at: string;
}

export const KEY_PREFIX = 's:';
export const NAME_LIMIT = 60;

/** Names that mean "do not list me", compared after [cleanName]. */
const ANONYMOUS = new Set(['', 'anonymous', 'anon', 'someone']);

/** Collapses whitespace and cuts to [NAME_LIMIT]; null for a name that asks not to be listed. */
export function cleanName(raw: string | undefined): string | null {
	const name = (raw ?? '').replace(/\s+/g, ' ').trim().slice(0, NAME_LIMIT);
	return ANONYMOUS.has(name.toLowerCase()) ? null : name;
}

export const CORS = {
	'Access-Control-Allow-Origin': '*',
	'Access-Control-Allow-Methods': 'GET, OPTIONS',
};

export function json(body: unknown, init: ResponseInit = {}): Response {
	return new Response(JSON.stringify(body), {
		...init,
		headers: { 'Content-Type': 'application/json; charset=utf-8', ...CORS, ...init.headers },
	});
}
