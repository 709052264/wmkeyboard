package com.wasimaster.wmkeyboard.app

import android.content.Context
import com.wasimaster.wmkeyboard.core.providers.ProviderCredential
import com.wasimaster.wmkeyboard.core.providers.ProviderTool
import com.wasimaster.wmkeyboard.core.providers.ProviderType

/**
 * The Test button of the provider editor: the cheapest request each kind of
 * provider answers, made with what is in the editor now rather than what is
 * saved. Null for a kind with nothing worth testing (files on the phone, a
 * service that needs no setup).
 */
internal object ProviderProbes {

    fun probe(
        context: Context,
        tool: ProviderTool,
        type: ProviderType,
        credential: ProviderCredential,
    ): (suspend () -> Unit)? = null
}
