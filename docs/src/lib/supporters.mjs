/**
 * The supporters list, from the docs site's own /api/supporters (see
 * functions/api/supporters.ts), which the SupportKori webhook fills.
 */
import { SITE_URL } from '../site.mjs';

export const SUPPORTERS_URL = new URL('/api/supporters', SITE_URL).href;

/** `{ updated, supporters: [{ name, kind, at }] }`, newest first. Throws offline. */
export async function fetchSupporters({ timeoutMs = 10_000 } = {}) {
	const response = await fetch(SUPPORTERS_URL, { signal: AbortSignal.timeout(timeoutMs) });
	if (!response.ok) throw new Error(`${SUPPORTERS_URL}: HTTP ${response.status}`);
	const body = await response.json();
	if (!Array.isArray(body?.supporters)) throw new Error(`${SUPPORTERS_URL}: no supporters array`);
	return body;
}
