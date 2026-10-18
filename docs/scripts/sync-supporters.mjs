#!/usr/bin/env node
/**
 * Refreshes the two baked-in copies of the supporters list from the live
 * /api/supporters:
 *
 *   docs/src/data/supporters.json      the docs build's fallback when offline
 *   app/src/main/assets/supporters.json what the app shows before (or without)
 *                                       a network check, including the build
 *                                       with no internet permission; names,
 *                                       kinds and dates only
 *
 * Run it before a release so the APK carries an up-to-date list:
 *
 *   node scripts/sync-supporters.mjs
 */
import { writeFile } from 'node:fs/promises';
import { fetchSupporters } from '../src/lib/supporters.mjs';

const body = await fetchSupporters({ timeoutMs: 20_000 });
// The website shows amounts and messages; the app shows names only, so its
// copy carries nothing else.
const names = body.supporters.map(({ name, kind, at }) => ({ name, kind, at }));
const targets = [
	['../src/data/supporters.json', body.supporters],
	['../../app/src/main/assets/supporters.json', names],
];
for (const [target, supporters] of targets) {
	const url = new URL(target, import.meta.url);
	await writeFile(url, JSON.stringify({ updated: body.updated, supporters }, null, 2) + '\n');
	console.log(`${supporters.length} supporters → ${url.pathname}`);
}
