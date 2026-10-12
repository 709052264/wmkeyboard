package com.wasimaster.wmkeyboard.app

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.input.composer.CjkDictDownloadManager
import com.wasimaster.wmkeyboard.core.input.composer.CjkDictPack
import com.wasimaster.wmkeyboard.core.input.composer.CjkDictStore

/**
 * Asks, as a layout that converts is switched on, whether to fetch the pack it
 * converts through: the conversion counterpart of the Keyman rules prompt.
 *
 * Pinyin, Zhuyin, Cangjie, Stroke, Jyutping and romaji all type their reading
 * and offer nothing for it until their table is on the device, and the table is
 * too big to bundle. The keyboard puts a chip up for a missing pack too, but by
 * then the user is in a chat wondering why nothing converts; this is the moment
 * they are deciding to type in the language, so this is where it is asked.
 *
 * Returns what a toggle calls with the pack and the layout's name. A pack
 * already on disk, or not published yet, enables the layout without a word.
 */
@Composable
internal fun rememberCjkPackPrompt(): (CjkDictPack, String) -> Unit {
    val context = LocalContext.current
    val notifyDownload = rememberDownloadNotifier()
    var asking by remember { mutableStateOf<Pair<CjkDictPack, String>?>(null) }

    asking?.let { (pack, layoutName) ->
        val packName = stringResource(pack.displayNameRes)
        AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text(stringResource(R.string.languages_cjk_pack_prompt_title, packName)) },
            text = {
                Text(
                    stringResource(
                        R.string.languages_cjk_pack_prompt_body,
                        layoutName,
                        formatBytes(pack.sizeBytes),
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        asking = null
                        CjkDictDownloadManager.startAll(context.filesDir, listOf(pack))
                        notifyDownload(pack.id, packName, DownloadProgressFlows.cjkDict(context, pack.id))
                    },
                ) { Text(stringResource(R.string.languages_keyman_prompt_download)) }
            },
            dismissButton = {
                TextButton(onClick = { asking = null }) {
                    Text(stringResource(R.string.languages_keyman_prompt_later))
                }
            },
        )
    }

    return remember(context) {
        { pack, layoutName ->
            // A stat of one file: cheap enough for the tap that called this.
            // Nor asked while the pack is already on its way, from the same
            // switch flipped off and on again.
            val inFlight = CjkDictDownloadManager.states.value[pack.id] is CjkDictDownloadManager.DownloadStatus.Downloading
            if (pack.available && !inFlight && !CjkDictStore.isDownloaded(context.filesDir, pack)) {
                asking = pack to layoutName
            }
        }
    }
}
