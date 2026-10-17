/**
 * The few Cloudflare Pages types these functions use, so the editor and
 * `tsc` understand them without pulling in @cloudflare/workers-types. Pages
 * bundles the functions with esbuild, which ignores types either way.
 */
interface KVNamespaceListKey<M> {
	name: string;
	metadata?: M;
}

interface KVNamespaceListResult<M> {
	keys: KVNamespaceListKey<M>[];
	list_complete: boolean;
	cursor?: string;
}

interface KVNamespace {
	put(key: string, value: string, options?: { metadata?: unknown }): Promise<void>;
	list<M = unknown>(options?: { prefix?: string; cursor?: string }): Promise<KVNamespaceListResult<M>>;
}

type PagesFunction<Env = unknown> = (context: { request: Request; env: Env }) => Response | Promise<Response>;
