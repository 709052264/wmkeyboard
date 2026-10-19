package com.wasimaster.wmkeyboard.app.kdeconnect

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.kdeconnect.KdePairCode

/**
 * Asks before a `wmkeyboard://pair` link pairs this phone (#702).
 *
 * The link is the whole credential: the computer it names accepts the pair
 * without a prompt of its own, and a paired computer can type into the field
 * in use, read what is copied and send files. Any web page or app can open
 * such a link, naming any address, so the link alone is not the user's say-so.
 * The keyboard's own QR scanner pairs without this dialog, because pointing
 * it at a code already is.
 */
@Composable
internal fun PairLinkDialog(code: KdePairCode, onPair: () -> Unit, onDismiss: () -> Unit) {
    val address = "${code.host}:${code.port}"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.kdeconnect_pair_link_title, code.name.ifBlank { code.host })) },
        text = { Text(stringResource(R.string.kdeconnect_pair_link_body, address)) },
        confirmButton = {
            TextButton(onClick = onPair) { Text(stringResource(R.string.kdeconnect_device_pair)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.kdeconnect_pairing_cancel)) }
        },
    )
}
