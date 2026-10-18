/**
 * POST /api/supportkori — SupportKori's webhook.
 *
 * Subscribe it to `support.created` and `membership.joined`. Every request is
 * checked against the HMAC-SHA256 signature SupportKori sends, over the raw
 * body exactly as it arrived, before anything in it is trusted. Test events
 * (`livemode: false`) are acknowledged and dropped, and so is a supporter who
 * left the name blank or chose to stay anonymous.
 *
 * SupportKori retries a failed delivery once, so a repeat is expected: the key
 * includes the delivery id, and writing it twice stores the same entry.
 */
import { type Env, type SupporterKind, KEY_PREFIX, asMetadata, cleanName, positive } from './_supporters';

const KINDS: Record<string, SupporterKind> = {
	'support.created': 'tip',
	'membership.joined': 'member',
};

export const onRequestPost: PagesFunction<Env> = async ({ request, env }) => {
	if (!env.SUPPORTERS || !env.SUPPORTKORI_SECRET) {
		return new Response('Not configured', { status: 503 });
	}

	const raw = await request.text();
	const signature = request.headers.get('X-SupportKori-Signature') ?? '';
	if (!(await verify(raw, signature, env.SUPPORTKORI_SECRET))) {
		return new Response('Bad signature', { status: 401 });
	}

	let payload: {
		id?: string;
		event?: string;
		createdAt?: string;
		livemode?: boolean;
		/** The documented fields; SupportKori sends no coffee count and no message. */
		data?: { itemTitle?: string; buyerName?: string; amount?: unknown; currency?: string; orderId?: string };
	};
	try {
		payload = JSON.parse(raw);
	} catch {
		return new Response('Bad JSON', { status: 400 });
	}

	const kind = KINDS[payload.event ?? ''];
	// Anything else is answered 2xx all the same, or SupportKori would mark
	// the delivery failed and retry an event this endpoint will never want.
	if (!kind || payload.livemode !== true || !payload.id) return new Response('ignored');

	const name = cleanName(payload.data?.buyerName);
	if (name === null) return new Response('anonymous');

	// Fields per https://www.supportkori.com/help/article/setup-webhooks.
	// The amount is in the currency's major unit (500 for ৳500).
	const data = payload.data ?? {};
	const at = isoOr(payload.createdAt);
	const supporter = asMetadata({
		name,
		kind,
		at,
		amount: positive(data.amount),
		currency: typeof data.currency === 'string' ? data.currency.toUpperCase() : 'BDT',
	});
	await env.SUPPORTERS.put(`${KEY_PREFIX}${at}:${payload.id}`, '', { metadata: supporter });
	return new Response('ok');
};

/** `sha256=<hex>` over [body], compared in constant time. */
async function verify(body: string, header: string, secret: string): Promise<boolean> {
	const encoder = new TextEncoder();
	const key = await crypto.subtle.importKey(
		'raw',
		encoder.encode(secret),
		{ name: 'HMAC', hash: 'SHA-256' },
		false,
		['sign']
	);
	const mac = new Uint8Array(await crypto.subtle.sign('HMAC', key, encoder.encode(body)));
	const expected = 'sha256=' + [...mac].map((b) => b.toString(16).padStart(2, '0')).join('');
	if (header.length !== expected.length) return false;
	let diff = 0;
	for (let i = 0; i < expected.length; i++) diff |= header.charCodeAt(i) ^ expected.charCodeAt(i);
	return diff === 0;
}

function isoOr(value: string | undefined): string {
	const date = value ? new Date(value) : new Date();
	return Number.isNaN(date.getTime()) ? new Date().toISOString() : date.toISOString();
}
