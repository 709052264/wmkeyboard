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
 * The amount is stored too, and a Play tipper's message, for the website's
 * list. SupportKori's webhook carries no message, so its entries have none.
 * The app's list shows names only and ignores both, and
 * scripts/sync-supporters.mjs strips them from the copy the APK ships.
 * To take someone off the list, or a message that should not be public,
 * delete their key in the Cloudflare dashboard (Workers & Pages › KV › the
 * namespace bound as SUPPORTERS).
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
	/** What was paid, in [currency]'s major unit (300 for ৳300). */
	amount?: number;
	/** ISO 4217, "BDT" or "USD". */
	currency?: string;
	/** A Play tipper's own note, shown on the website only. */
	message?: string;
}

export const KEY_PREFIX = 's:';
export const NAME_LIMIT = 60;
export const MESSAGE_LIMIT = 280;

/** KV's cap on a key's metadata, less a little room for the JSON around it. */
const METADATA_BYTES = 1000;

/** Names that mean "do not list me", compared after [cleanName]. */
const ANONYMOUS = new Set(['', 'anonymous', 'anon', 'someone']);

/** Collapses whitespace and cuts to [NAME_LIMIT]; null for a name that asks not to be listed. */
export function cleanName(raw: string | undefined): string | null {
	const name = (raw ?? '').replace(/\s+/g, ' ').trim().slice(0, NAME_LIMIT);
	return ANONYMOUS.has(name.toLowerCase()) ? null : name;
}

/** Collapses runs of blank lines and spaces and cuts to [MESSAGE_LIMIT]; undefined when empty. */
export function cleanMessage(raw: unknown): string | undefined {
	if (typeof raw !== 'string') return undefined;
	const message = raw
		.replace(/[ \t]+/g, ' ')
		.replace(/\n{3,}/g, '\n\n')
		.trim()
		.slice(0, MESSAGE_LIMIT);
	return message || undefined;
}

/**
 * [supporter] as KV metadata, shortening the message until the whole entry
 * fits KV's 1 KiB limit. A long note in Bengali is three bytes a letter, so
 * [MESSAGE_LIMIT] alone does not guarantee it.
 */
export function asMetadata(supporter: Supporter): Supporter {
	const entry = { ...supporter };
	const size = () => new TextEncoder().encode(JSON.stringify(entry)).length;
	while (entry.message && size() > METADATA_BYTES) {
		entry.message = entry.message.slice(0, -10).trimEnd() + '…';
		if (entry.message.length < 4) delete entry.message;
	}
	return entry;
}

/** A positive finite number out of a number or numeric string, else undefined. */
export function positive(value: unknown): number | undefined {
	const number = typeof value === 'string' ? Number(value) : value;
	return typeof number === 'number' && Number.isFinite(number) && number > 0 ? number : undefined;
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
