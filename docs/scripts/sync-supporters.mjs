#!/usr/bin/env node
/**
 * Refreshes the two baked-in copies of the supporters list from the live
 * /api/supporters:
 *
 *   docs/src/data/supporters.json      the docs build's fallback when offline
 *   app/src/main/assets/supporters.json what the app shows before (or without)
 *                                       a network check, including the build
 *                                       with no internet permission
 *
 * Run it before a release so the APK carries an up-to-date list:
 *
 *   node scripts/sync-supporters.mjs
 */
import { writeFile } from 'node:fs/promises';
import { fetchSupporters } from '../src/lib/supporters.mjs';

const body = await fetchSupporters({ timeoutMs: 20_000 });
const text = JSON.stringify({ updated: body.updated, supporters: body.supporters }, null, 2) + '\n';
for (const target of ['../src/data/supporters.json', '../../app/src/main/assets/supporters.json']) {
	const url = new URL(target, import.meta.url);
	await writeFile(url, text);
	console.log(`${body.supporters.length} supporters → ${url.pathname}`);
}
