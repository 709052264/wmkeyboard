/**
 * /media-kit/wm-keyboard-media-kit.zip: every logo, screenshot and banner on
 * the media kit page in one download. Built at build time from the same list
 * the page reads (src/lib/media-kit.mjs), so the two can't disagree.
 */

import type { APIRoute } from 'astro';

import { mediaKitZip } from '../../lib/media-kit.mjs';

export const GET: APIRoute = async () =>
	new Response(await mediaKitZip(), {
		headers: { 'Content-Type': 'application/zip' },
	});
