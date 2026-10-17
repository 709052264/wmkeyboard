package com.wasimaster.wmkeyboard.app

import android.content.Context
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.netlog.InternetPermission
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.tools.ToolHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.DateFormat
import java.time.Instant
import java.util.Date

/**
 * About › Supporters: the people who tipped or joined on SupportKori.
 *
 * The list the APK was built with (`assets/supporters.json`, refreshed by
 * `docs/scripts/sync-supporters.mjs`) shows straight away and is all an
 * offline phone, or the build with no internet permission, ever sees. The
 * button fetches the live list from the docs site, and only when pressed:
 * nothing goes out just because the screen was opened.
 */
private const val SUPPORTERS_URL = "$DOCS_URL/api/supporters"
private const val SUPPORTERS_ASSET = "supporters.json"
private const val SUPPORTKORI_URL = "https://www.supportkori.com/wasimaster"

internal data class Supporter(val name: String, val member: Boolean, val at: String)

private sealed interface RefreshState {
    data object Idle : RefreshState
    data object Checking : RefreshState
    data class Added(val count: Int) : RefreshState
    data object Failed : RefreshState
}

/** `{ supporters: [{ name, kind, at }] }`, the shape both the asset and the API use. */
internal fun parseSupporters(json: String): List<Supporter> {
    val array = JSONObject(json).optJSONArray("supporters") ?: return emptyList()
    return (0 until array.length()).mapNotNull { i ->
        val item = array.optJSONObject(i) ?: return@mapNotNull null
        val name = item.optString("name").trim()
        if (name.isEmpty()) return@mapNotNull null
        Supporter(name, member = item.optString("kind") == "member", at = item.optString("at"))
    }
}

private fun bundledSupporters(context: Context): List<Supporter> = runCatching {
    context.assets.open(SUPPORTERS_ASSET).use { parseSupporters(it.readBytes().decodeToString()) }
}.getOrDefault(emptyList())

private fun dateOf(iso: String): String = runCatching {
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date.from(Instant.parse(iso)))
}.getOrDefault("")

@Composable
internal fun SupportersScreen() {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var supporters by remember { mutableStateOf<List<Supporter>?>(null) }
    var state by remember { mutableStateOf<RefreshState>(RefreshState.Idle) }

    LaunchedEffect(Unit) {
        supporters = withContext(Dispatchers.IO) { bundledSupporters(context) }
    }

    SettingsGroup(info = stringResource(R.string.supporters_intro)) {
        item {
            NavRow(
                R.string.supporters_support_title,
                stringResource(R.string.supporters_support_subtitle),
            ) { uriHandler.openUri(SUPPORTKORI_URL) }
        }
    }

    val list = supporters ?: return
    SettingsGroup(stringResource(R.string.supporters_list_title)) {
        if (list.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.supporters_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                )
            }
        }
        list.forEach { supporter ->
            item {
                val kind = stringResource(
                    if (supporter.member) R.string.supporters_kind_member else R.string.supporters_kind_tip,
                )
                val date = dateOf(supporter.at)
                NavRow(
                    title = supporter.name,
                    subtitle = if (date.isEmpty()) kind else stringResource(R.string.supporters_row_subtitle, kind, date),
                    icon = null,
                    onClick = {},
                )
            }
        }
    }

    // A build without the internet permission has nothing to fetch with, so
    // it shows the list it shipped with and no button that can only fail.
    if (!InternetPermission.granted) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
        Button(
            enabled = state != RefreshState.Checking,
            onClick = {
                state = RefreshState.Checking
                scope.launch {
                    val fresh = withContext(Dispatchers.IO) {
                        runCatching {
                            parseSupporters(
                                ToolHttp.get(
                                    SUPPORTERS_URL,
                                    source = NetSource.SUPPORTERS,
                                    route = "/api/supporters",
                                ),
                            )
                        }.getOrNull()
                    }
                    if (fresh == null) {
                        state = RefreshState.Failed
                    } else {
                        val known = list.map { it.at to it.name }.toSet()
                        state = RefreshState.Added(fresh.count { (it.at to it.name) !in known })
                        supporters = fresh
                    }
                }
            },
        ) {
            Text(stringResource(R.string.supporters_check_button))
        }
        Spacer(Modifier.height(8.dp))
        val message = when (val s = state) {
            RefreshState.Idle -> stringResource(R.string.supporters_check_hint)
            RefreshState.Checking -> stringResource(R.string.supporters_checking)
            RefreshState.Failed -> stringResource(R.string.supporters_check_failed)
            is RefreshState.Added ->
                if (s.count == 0) stringResource(R.string.supporters_none_new)
                else pluralStringResource(R.plurals.supporters_added, s.count, s.count)
        }
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
