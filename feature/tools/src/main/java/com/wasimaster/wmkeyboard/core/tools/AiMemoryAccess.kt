package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.aimemory.AiMemoryStore

/**
 * A chat's hold on the AI memory, handed to [AiToolRunner] when that chat may
 * change it: the store `remember` and `forget` work on, and the
 * [com.wasimaster.wmkeyboard.core.aimemory.AiMemory.source] its saves are
 * filed under (`chat:<conversation id>`).
 *
 * Whether a run gets one is decided by whoever builds the runner, from the
 * memory switches, the model and the field; see `AiMemoryRecall` in the
 * keyboard module.
 */
class AiMemoryAccess(val store: AiMemoryStore, val source: String)
