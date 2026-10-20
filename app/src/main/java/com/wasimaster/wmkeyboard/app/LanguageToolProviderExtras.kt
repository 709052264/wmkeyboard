package com.wasimaster.wmkeyboard.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.grammar.LanguageToolServer
import com.wasimaster.wmkeyboard.core.providers.ProviderCredential

/**
 * The two LanguageTool options the provider editor's plain fields cannot
 * hold, kept in the credential's `extra`: the picky level, and the writer's
 * first language. The username sits with the key, above, as every kind with
 * a [com.wasimaster.wmkeyboard.core.providers.CredentialSpec.username] has it.
 */
@Composable
internal fun LanguageToolExtras(draft: ProviderCredential, onDraft: (ProviderCredential) -> Unit) {
    fun withExtra(key: String, value: String?): ProviderCredential =
        draft.copy(extra = if (value.isNullOrBlank()) draft.extra - key else draft.extra + (key to value))

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.providers_languagetool_picky_title), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.providers_languagetool_picky_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = draft.extra[LanguageToolServer.PICKY] == "true",
            onCheckedChange = { on -> onDraft(withExtra(LanguageToolServer.PICKY, if (on) "true" else null)) },
        )
    }
    StoredTextField(
        label = stringResource(R.string.providers_languagetool_mother_tongue_label),
        value = draft.extra[LanguageToolServer.MOTHER_TONGUE].orEmpty(),
        supporting = stringResource(R.string.providers_languagetool_mother_tongue_hint),
    ) { onDraft(withExtra(LanguageToolServer.MOTHER_TONGUE, it.trim())) }
}
