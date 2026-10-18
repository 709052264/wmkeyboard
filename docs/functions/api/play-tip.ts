/**
 * POST /api/play-tip — credits a tip made through Google Play billing.
 *
 * The Play build of the app cannot link to SupportKori (Play's Payments
 * policy), so it sells a few consumable "tip" products instead and, after
 * the purchase, sends `{ productId, purchaseToken, name, message }` here. Nothing in
 * that body is trusted: the token is looked up with Google through the Play
 * Developer API, and only an order Google says is paid reaches the list. A
 * made-up token gets a 400, so the list cannot be filled by anyone with curl.
 *
 * The entry joins the same KV namespace the SupportKori webhook writes, keyed
 * by the Play order id, so a retry (the app resends until it hears back)
 * writes the same key again rather than a second entry. Like the webhook, a
 * blank or "anonymous" name is not listed. The amount stored is the tier's
 * list price in US dollars from [PRICES], never a figure the app sends: Play
 * prices each tier per country, and an amount from the client could be made
 * up, so the website shows the tier's price rather than the local charge.
 *
 * Needs the PLAY_SERVICE_ACCOUNT secret; see wrangler.jsonc. Without it the
 * endpoint answers 503 and the app keeps the credit queued to try later.
 */
import { type Env, KEY_PREFIX, asMetadata, cleanMessage, cleanName, json } from './_supporters';

const PACKAGE = 'com.wasimaster.wmkeyboard';
const PRODUCT = /^tip_\d{1,3}$/;

/** Each tier's base price in Play Console, kept in step with TipTier.usd in the app. */
const PRICES: Record<string, number> = {
	tip_1: 0.99,
	tip_2: 1.99,
	tip_5: 4.99,
	tip_10: 9.99,
	tip_20: 19.99,
	tip_50: 49.99,
	tip_100: 99.99,
};
const SCOPE = 'https://www.googleapis.com/auth/androidpublisher';
const TOKEN_URL = 'https://oauth2.googleapis.com/token';

interface ServiceAccount {
	client_email: string;
	private_key: string;
}

/** The parts of Play's ProductPurchase this endpoint reads. */
interface ProductPurchase {
	purchaseState?: number;
	purchaseTimeMillis?: string;
	orderId?: string;
	/** Set only for an order outside the normal flow; 0 is a license tester's. */
	purchaseType?: number;
}

export const onRequestPost: PagesFunction<Env> = async ({ request, env }) => {
	if (!env.SUPPORTERS || !env.PLAY_SERVICE_ACCOUNT) return json({ status: 'not-configured' }, { status: 503 });

	let body: { productId?: unknown; purchaseToken?: unknown; name?: unknown; message?: unknown };
	try {
		body = await request.json();
	} catch {
		return json({ status: 'bad-request' }, { status: 400 });
	}
	const productId = typeof body.productId === 'string' ? body.productId : '';
	const token = typeof body.purchaseToken === 'string' ? body.purchaseToken : '';
	if (!PRODUCT.test(productId) || !(productId in PRICES) || token.length < 16 || token.length > 4096) {
		return json({ status: 'bad-request' }, { status: 400 });
	}

	const name = cleanName(typeof body.name === 'string' ? body.name : '');
	// Answered before Google is asked anything: there is nothing to write.
	if (name === null) return json({ status: 'anonymous' });

	let purchase: ProductPurchase;
	try {
		const access = await accessToken(env.PLAY_SERVICE_ACCOUNT);
		const url =
			`https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${PACKAGE}` +
			`/purchases/products/${encodeURIComponent(productId)}/tokens/${encodeURIComponent(token)}`;
		const response = await fetch(url, { headers: { Authorization: `Bearer ${access}` } });
		// Google answers 400 or 404 for a token it never issued for this product.
		if (response.status === 400 || response.status === 404) {
			return json({ status: 'unknown-purchase' }, { status: 400 });
		}
		if (!response.ok) throw new Error(`Play Developer API: HTTP ${response.status}`);
		purchase = await response.json();
	} catch (error) {
		console.error(error);
		// 502, not 4xx: the app keeps a 5xx credit and tries again later.
		return json({ status: 'play-unreachable' }, { status: 502 });
	}

	// 0 purchased, 1 cancelled, 2 pending. The app only sends a purchased one,
	// so anything else here is a refund or a cancellation that beat it.
	if (purchase.purchaseState !== 0 || !purchase.orderId) {
		return json({ status: 'not-paid' }, { status: 400 });
	}
	if (purchase.purchaseType === 0 && env.PLAY_TIPS_ALLOW_TEST !== 'true') {
		return json({ status: 'test' });
	}

	const at = isoFromMillis(purchase.purchaseTimeMillis);
	const supporter = asMetadata({
		name,
		kind: 'tip',
		at,
		amount: PRICES[productId],
		currency: 'USD',
		message: cleanMessage(body.message),
	});
	await env.SUPPORTERS.put(`${KEY_PREFIX}${at}:play-${purchase.orderId}`, '', { metadata: supporter });
	return json({ status: 'ok' });
};

/** A cached OAuth access token, reused until a minute before it expires. */
let cached: { token: string; expires: number } | undefined;

/** Trades a JWT signed with the service account's key for an access token. */
async function accessToken(secret: string): Promise<string> {
	const now = Math.floor(Date.now() / 1000);
	if (cached && cached.expires - 60 > now) return cached.token;

	const account = JSON.parse(secret) as ServiceAccount;
	const header = base64url(JSON.stringify({ alg: 'RS256', typ: 'JWT' }));
	const claims = base64url(
		JSON.stringify({ iss: account.client_email, scope: SCOPE, aud: TOKEN_URL, iat: now, exp: now + 3600 })
	);
	const key = await crypto.subtle.importKey(
		'pkcs8',
		pemBody(account.private_key),
		{ name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' },
		false,
		['sign']
	);
	const signature = await crypto.subtle.sign(
		'RSASSA-PKCS1-v1_5',
		key,
		new TextEncoder().encode(`${header}.${claims}`)
	);
	const assertion = `${header}.${claims}.${base64url(new Uint8Array(signature))}`;

	const response = await fetch(TOKEN_URL, {
		method: 'POST',
		headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
		body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion }),
	});
	if (!response.ok) throw new Error(`OAuth token: HTTP ${response.status}`);
	const grant = (await response.json()) as { access_token: string; expires_in: number };
	cached = { token: grant.access_token, expires: now + grant.expires_in };
	return grant.access_token;
}

/** The DER bytes inside a PEM block. */
function pemBody(pem: string): ArrayBuffer {
	const base64 = pem.replace(/-----[^-]+-----/g, '').replace(/\s+/g, '');
	const bytes = Uint8Array.from(atob(base64), (c) => c.charCodeAt(0));
	return bytes.buffer;
}

function base64url(input: string | Uint8Array): string {
	const bytes = typeof input === 'string' ? new TextEncoder().encode(input) : input;
	let binary = '';
	for (const byte of bytes) binary += String.fromCharCode(byte);
	return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function isoFromMillis(value: string | undefined): string {
	const date = new Date(Number(value));
	return Number.isNaN(date.getTime()) ? new Date().toISOString() : date.toISOString();
}
