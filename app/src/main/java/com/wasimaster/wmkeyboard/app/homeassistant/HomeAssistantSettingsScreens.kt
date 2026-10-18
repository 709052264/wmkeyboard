package com.wasimaster.wmkeyboard.app.homeassistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.app.BannerTone
import com.wasimaster.wmkeyboard.app.ChoiceDetail
import com.wasimaster.wmkeyboard.app.ChoiceSetting
import com.wasimaster.wmkeyboard.app.ControlSetting
import com.wasimaster.wmkeyboard.app.ExpandableCard
import com.wasimaster.wmkeyboard.app.LiveSettings
import com.wasimaster.wmkeyboard.app.NavRow
import com.wasimaster.wmkeyboard.app.ReorderableColumn
import com.wasimaster.wmkeyboard.app.SettingsGroup
import com.wasimaster.wmkeyboard.app.StateBanner
import com.wasimaster.wmkeyboard.app.StoredTextField
import com.wasimaster.wmkeyboard.app.TextFieldSetting
import com.wasimaster.wmkeyboard.app.ToggleSetting
import com.wasimaster.wmkeyboard.core.homeassistant.HaDomains
import com.wasimaster.wmkeyboard.core.homeassistant.HaFound
import com.wasimaster.wmkeyboard.core.homeassistant.HaHttpException
import com.wasimaster.wmkeyboard.core.homeassistant.HaInsertFormat
import com.wasimaster.wmkeyboard.core.homeassistant.HaPhase
import com.wasimaster.wmkeyboard.core.homeassistant.HaRest
import com.wasimaster.wmkeyboard.core.homeassistant.HaStateFormat
import com.wasimaster.wmkeyboard.core.homeassistant.HaUrl
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.VisibilityOff
import com.wasimaster.wmkeyboard.core.settings.HaLinkLifetime
import com.wasimaster.wmkeyboard.core.settings.HaOpenOn
import com.wasimaster.wmkeyboard.core.settings.HaTileSize
import com.wasimaster.wmkeyboard.core.settings.HomeAssistantSettings
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.ime.homeassistant.HaAndroidMdns
import com.wasimaster.wmkeyboard.ime.homeassistant.HomeAssistantHub
import com.wasimaster.wmkeyboard.ime.homeassistant.ToolHaHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Home Assistant tool's own page: the switch, the server and the sign-in,
 * then how the panel lays itself out, Assist, and how long the connection is
 * held. The hub is fed the settings here too, so the banner reads the real
 * connection even when the keyboard has not run since the last change.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
@Suppress("detekt.LongMethod", "detekt.CyclomaticComplexMethod")
internal fun HomeAssistantToolSettings(
    repository: SettingsRepository,
    settings: LiveSettings,
    onNavigate: (String) -> Unit,
) {
    val defaults = HomeAssistantSettings()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val hub by HomeAssistantHub.state.collectAsState()
    val signIn by HaSignIn.status.collectAsState()
    val ha = settings.watch { it.homeAssistant }
    LaunchedEffect(ha) {
        HomeAssistantHub.attach(context)
        HomeAssistantHub.applySettings(ha)
    }
    // The page is one of the reasons the socket may be up, so the banner is live.
    DisposableEffect(Unit) {
        HomeAssistantHub.attach(context)
        HomeAssistantHub.hold(HomeAssistantHub.Reason.SETTINGS)
        onDispose { HomeAssistantHub.release(HomeAssistantHub.Reason.SETTINGS) }
    }
    val on = ha.enabled
    val instanceName = hub.instance?.name ?: ha.instanceName.ifBlank { "Home" }

    when {
        signIn is HaSignIn.Status.Working -> StateBanner(stringResource(R.string.ha_banner_working))
        signIn is HaSignIn.Status.Failed -> StateBanner(
            stringResource(R.string.ha_banner_failed, (signIn as HaSignIn.Status.Failed).message),
            action = stringResource(R.string.ha_banner_action_dismiss),
            tone = BannerTone.WARNING,
        ) { HaSignIn.clearStatus() }
        !on -> StateBanner(stringResource(R.string.ha_banner_off))
        !ha.hasServer -> StateBanner(stringResource(R.string.ha_banner_no_server), tone = BannerTone.WARNING)
        !ha.hasToken -> StateBanner(stringResource(R.string.ha_banner_not_signed_in), tone = BannerTone.WARNING)
        hub.phase == HaPhase.TOKEN_REJECTED -> StateBanner(
            stringResource(R.string.ha_banner_token_rejected),
            action = stringResource(R.string.ha_banner_action_sign_in),
            tone = BannerTone.WARNING,
        ) { HaUrl.parse(ha.internalUrl.ifBlank { ha.externalUrl })?.let { HaSignIn.start(context, repository, scope, it) } }
        hub.phase == HaPhase.UNREACHABLE -> StateBanner(
            stringResource(R.string.ha_banner_unreachable, instanceName),
            action = stringResource(R.string.ha_banner_action_retry),
            tone = BannerTone.WARNING,
        ) { HomeAssistantHub.refresh() }
        hub.phase == HaPhase.LIVE || hub.phase == HaPhase.REST_FALLBACK -> StateBanner(
            hub.instance?.version?.let { stringResource(R.string.ha_banner_connected_version, instanceName, it) }
                ?: stringResource(R.string.ha_banner_connected, instanceName),
        )
        signIn is HaSignIn.Status.SignedIn -> StateBanner(
            stringResource(R.string.ha_banner_signed_in, (signIn as HaSignIn.Status.SignedIn).instanceName),
            action = stringResource(R.string.ha_banner_action_dismiss),
        ) { HaSignIn.clearStatus() }
        else -> StateBanner(stringResource(R.string.ha_banner_signed_in, instanceName))
    }

    SettingsGroup(stringResource(R.string.ha_group_connection), info = stringResource(R.string.ha_group_connection_info)) {
        item {
            ToggleSetting(R.string.ha_enabled_title, stringResource(R.string.ha_enabled_subtitle), on, default = defaults.enabled) {
                scope.launch { repository.setHaEnabled(it) }
            }
        }
        item(visible = on) {
            TextFieldSetting(
                label = stringResource(R.string.ha_internal_url_label),
                value = settings.watch { it.homeAssistant.internalUrl },
                hint = stringResource(R.string.ha_internal_url_hint),
            ) { repository.setHaInternalUrl(it) }
        }
        item(visible = on && !ha.hasToken) { FoundInstances(repository) }
        item(visible = on) {
            TextFieldSetting(
                label = stringResource(R.string.ha_external_url_label),
                value = settings.watch { it.homeAssistant.externalUrl },
                hint = stringResource(R.string.ha_external_url_hint),
            ) { repository.setHaExternalUrl(it) }
        }
        item(visible = on && ha.externalUrl.isNotBlank()) {
            ToggleSetting(
                R.string.ha_prefer_internal_title, stringResource(R.string.ha_prefer_internal_subtitle),
                settings.watch { it.homeAssistant.preferInternal }, default = defaults.preferInternal,
            ) { scope.launch { repository.setHaPreferInternal(it) } }
        }
        item(visible = on && ha.hasServer) { SignInRows(repository, ha) }
    }
    if (!on) return

    SettingsGroup(stringResource(R.string.ha_group_panel)) {
        item {
            val count = settings.watch { it.homeAssistant.favourites.size }
            NavRow(
                title = R.string.ha_favourites_title,
                subtitle = if (count == 0) stringResource(R.string.ha_favourites_subtitle_none) else stringResource(R.string.ha_favourites_subtitle, count),
            ) { onNavigate(ENTITIES_ROUTE) }
        }
        item {
            ChoiceSetting(
                R.string.ha_open_on_title,
                options = listOf(
                    HaOpenOn.FAVOURITES to stringResource(R.string.ha_open_on_favourites),
                    HaOpenOn.LAST_TAB to stringResource(R.string.ha_open_on_last),
                ),
                selected = settings.watch { it.homeAssistant.openOn },
                default = defaults.openOn,
            ) { scope.launch { repository.setHaOpenOn(it) } }
        }
        item {
            ToggleSetting(
                R.string.ha_areas_tabs_title, stringResource(R.string.ha_areas_tabs_subtitle),
                settings.watch { it.homeAssistant.areasAsTabs }, default = defaults.areasAsTabs,
            ) { scope.launch { repository.setHaAreasAsTabs(it) } }
        }
        item {
            ChoiceSetting(
                R.string.ha_tile_size_title,
                options = listOf(
                    HaTileSize.COMFORTABLE to stringResource(R.string.ha_tile_comfortable),
                    HaTileSize.COMPACT to stringResource(R.string.ha_tile_compact),
                ),
                selected = settings.watch { it.homeAssistant.tileSize },
                default = defaults.tileSize,
            ) { scope.launch { repository.setHaTileSize(it) } }
        }
        item {
            ToggleSetting(
                R.string.ha_confirm_risky_title, stringResource(R.string.ha_confirm_risky_subtitle),
                settings.watch { it.homeAssistant.confirmRisky }, default = defaults.confirmRisky,
            ) { scope.launch { repository.setHaConfirmRisky(it) } }
        }
        item {
            ChoiceSetting(
                R.string.ha_insert_format_title,
                options = listOf(
                    HaInsertFormat.VALUE to stringResource(R.string.ha_insert_value),
                    HaInsertFormat.VALUE_UNIT to stringResource(R.string.ha_insert_value_unit),
                    HaInsertFormat.NAME_VALUE to stringResource(R.string.ha_insert_name_value),
                ),
                selected = settings.watch { HaInsertFormat.of(it.homeAssistant.insertFormat) },
                default = HaInsertFormat.of(defaults.insertFormat),
            ) { scope.launch { repository.setHaInsertFormat(it.id) } }
        }
    }

    SettingsGroup(stringResource(R.string.ha_group_assist), info = stringResource(R.string.ha_group_assist_info)) {
        item {
            ToggleSetting(
                R.string.ha_assist_enabled_title, stringResource(R.string.ha_assist_enabled_subtitle),
                settings.watch { it.homeAssistant.assistEnabled }, default = defaults.assistEnabled,
            ) { scope.launch { repository.setHaAssistEnabled(it) } }
        }
        item(visible = ha.assistEnabled) {
            val agents = hub.agents
            val selected = settings.watch { it.homeAssistant.assistAgentId }
            ChoiceSetting(
                R.string.ha_assist_agent_title,
                subtitle = stringResource(R.string.ha_assist_agent_subtitle),
                options = listOf("" to stringResource(R.string.ha_assist_agent_default)) + agents.map { it.id to it.name },
                selected = if (agents.any { it.id == selected }) selected else "",
                default = "",
            ) { scope.launch { repository.setHaAssistAgent(it) } }
        }
        item(visible = ha.assistEnabled) {
            ToggleSetting(
                R.string.ha_assist_language_title, stringResource(R.string.ha_assist_language_subtitle),
                settings.watch { it.homeAssistant.assistFollowsKeyboardLanguage }, default = defaults.assistFollowsKeyboardLanguage,
            ) { scope.launch { repository.setHaAssistFollowsLanguage(it) } }
        }
    }

    SettingsGroup(stringResource(R.string.ha_group_link)) {
        item {
            val details = mapOf(
                HaLinkLifetime.PANEL to stringResource(R.string.ha_lifetime_panel_detail),
                HaLinkLifetime.KEYBOARD to stringResource(R.string.ha_lifetime_keyboard_detail),
            )
            ChoiceSetting(
                R.string.ha_lifetime_title,
                info = stringResource(R.string.ha_lifetime_info),
                options = listOf(
                    HaLinkLifetime.PANEL to stringResource(R.string.ha_lifetime_panel),
                    HaLinkLifetime.KEYBOARD to stringResource(R.string.ha_lifetime_keyboard),
                ),
                selected = settings.watch { it.homeAssistant.lifetime },
                default = defaults.lifetime,
                detail = { choice -> details[choice]?.let { ChoiceDetail(it) } },
            ) { scope.launch { repository.setHaLifetime(it) } }
        }
        item {
            ToggleSetting(
                R.string.ha_cache_title, stringResource(R.string.ha_cache_subtitle),
                settings.watch { it.homeAssistant.cacheStates }, default = defaults.cacheStates,
            ) { scope.launch { repository.setHaCacheStates(it) } }
        }
    }

    SettingsGroup(stringResource(R.string.ha_group_also)) {
        item {
            NavRow(title = R.string.ha_ai_tools_title, subtitle = stringResource(R.string.ha_ai_tools_subtitle)) {
                onNavigate("tool/${ToolbarTool.AI.name}")
            }
        }
        item {
            ToggleSetting(
                R.string.ha_smart_chip_title, stringResource(R.string.ha_smart_chip_subtitle),
                settings.watch { it.homeAssistant.smartChip }, default = defaults.smartChip,
            ) { scope.launch { repository.setHaSmartChip(it) } }
        }
    }
}

/** Instances announced on the LAN, as chips that fill the address in. Browses only while nothing is signed in. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FoundInstances(repository: SettingsRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val found = remember { mutableStateOf<List<HaFound>>(emptyList()) }
    DisposableEffect(Unit) {
        val mdns = HaAndroidMdns(context)
        mdns.start { f ->
            scope.launch {
                found.value = (found.value.filterNot { it.uuid != null && it.uuid == f.uuid || it.host == f.host } + f).filterNot { it.isLandingPage }
            }
        }
        onDispose { mdns.stop() }
    }
    ControlSetting(
        title = stringResource(R.string.ha_find_title),
        subtitle = if (found.value.isEmpty()) stringResource(R.string.ha_find_searching) else stringResource(R.string.ha_find_subtitle),
    ) {
        if (found.value.isEmpty()) return@ControlSetting
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            found.value.forEach { f ->
                val internal = f.internal ?: return@forEach
                FilterChip(
                    selected = false,
                    onClick = {
                        scope.launch {
                            repository.setHaInternalUrl(internal.base)
                            f.external?.let { repository.setHaExternalUrl(it.base) }
                        }
                    },
                    label = { Text("${f.name} · ${internal.host}") },
                )
            }
        }
    }
}

/** Sign in with the browser, or paste a token; and the test and sign-out buttons once signed in. */
@Composable
private fun SignInRows(repository: SettingsRepository, ha: HomeAssistantSettings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val base = HaUrl.parse(ha.internalUrl.ifBlank { ha.externalUrl })
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var token by remember { mutableStateOf("") }
    var tokenResult by remember { mutableStateOf<String?>(null) }

    ControlSetting(
        title = stringResource(R.string.ha_sign_in_title),
        subtitle = when {
            !ha.hasToken -> stringResource(R.string.ha_sign_in_subtitle)
            ha.longLivedToken -> stringResource(R.string.ha_signed_in_token)
            else -> stringResource(R.string.ha_signed_in_browser)
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            Button(enabled = base != null, onClick = { base?.let { HaSignIn.start(context, repository, scope, it) } }) {
                Text(stringResource(if (ha.hasToken) R.string.ha_sign_in_again_button else R.string.ha_sign_in_button))
            }
            if (ha.hasToken) {
                OutlinedButton(
                    enabled = !testing && base != null,
                    onClick = {
                        testing = true
                        testResult = null
                        scope.launch {
                            val outcome = runCatching {
                                withContext(Dispatchers.IO) {
                                    HaRest(ToolHaHttp(context.cacheDir), { base }, { ha.accessToken }).config()
                                }
                            }
                            testing = false
                            testResult = outcome.fold(
                                onSuccess = { true to context.getString(R.string.ha_test_ok, it.name) },
                                onFailure = { f ->
                                    val why = (f as? HaHttpException)?.let { "HTTP ${it.status}" } ?: f.message.orEmpty()
                                    false to context.getString(R.string.ha_test_failed, why)
                                },
                            )
                        }
                    },
                ) {
                    if (testing) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.ha_test_button))
                }
                TextButton(onClick = {
                    scope.launch {
                        val b = base
                        if (b != null && !ha.longLivedToken && ha.refreshToken.isNotBlank()) {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    com.wasimaster.wmkeyboard.core.homeassistant.HaAuth.revoke(
                                        ToolHaHttp(context.cacheDir), b,
                                        com.wasimaster.wmkeyboard.core.homeassistant.HaTokens(ha.accessToken, ha.refreshToken),
                                    )
                                }
                            }
                        }
                        repository.clearHaTokens()
                    }
                }) { Text(stringResource(R.string.ha_sign_out_button)) }
            }
        }
        testResult?.let { (ok, text) ->
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = if (ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
    if (!ha.hasToken || ha.longLivedToken) {
        ExpandableCard(
            title = stringResource(R.string.ha_token_fold_title),
            subtitle = null,
            initiallyExpanded = false,
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                StoredTextField(
                    label = stringResource(R.string.ha_token_label),
                    value = token,
                    supporting = stringResource(R.string.ha_token_supporting),
                    password = true,
                    keyboardType = KeyboardType.Password,
                    multiLine = true,
                ) { token = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Button(
                        enabled = token.isNotBlank() && base != null,
                        onClick = {
                            scope.launch {
                                val b = base ?: return@launch
                                tokenResult = HaSignIn.useToken(context, repository, b, token).fold(
                                    onSuccess = {
                                        token = ""
                                        context.getString(R.string.ha_token_ok, it)
                                    },
                                    onFailure = { f ->
                                        val why = (f as? HaHttpException)?.let { "HTTP ${it.status}" } ?: f.message.orEmpty()
                                        context.getString(R.string.ha_token_failed, why)
                                    },
                                )
                            }
                        },
                    ) { Text(stringResource(R.string.ha_token_use_button)) }
                }
                tokenResult?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
            }
        }
    }
}

/**
 * Favourites and rooms: the Favourites tab's tiles in their order, every
 * entity by room with a star to add it, the hidden ones to bring back, and
 * whole kinds to leave out.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
@Suppress("detekt.LongMethod", "detekt.CyclomaticComplexMethod")
internal fun HaEntitiesScreen(repository: SettingsRepository, settings: LiveSettings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hub by HomeAssistantHub.state.collectAsState()
    val ha = settings.watch { it.homeAssistant }
    LaunchedEffect(ha) {
        HomeAssistantHub.attach(context)
        HomeAssistantHub.applySettings(ha)
    }
    DisposableEffect(Unit) {
        HomeAssistantHub.attach(context)
        HomeAssistantHub.hold(HomeAssistantHub.Reason.SETTINGS)
        onDispose { HomeAssistantHub.release(HomeAssistantHub.Reason.SETTINGS) }
    }
    val format = remember { HaStateFormat() }
    val registry = hub.registry
    var query by remember { mutableStateOf("") }
    val all = remember(hub.entities, query, ha.hiddenDomains) {
        hub.entities.values
            .filter { HaDomains.isListed(it.domain) && !registry.isHidden(it.entityId) && it.domain !in ha.hiddenDomains }
            .filter { query.isBlank() || registry.nameOf(it).contains(query, ignoreCase = true) || it.entityId.contains(query, ignoreCase = true) }
            .sortedBy { registry.nameOf(it) }
    }
    if (hub.entities.isEmpty()) {
        StateBanner(stringResource(R.string.ha_entities_empty), tone = BannerTone.WARNING)
        return
    }

    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        label = { Text(stringResource(R.string.ha_entities_search_hint)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )

    SettingsGroup(stringResource(R.string.ha_entities_favourites_group), info = stringResource(R.string.ha_entities_favourites_info)) {
        item {
            val favourites = ha.favourites.filter { hub.entities.containsKey(it) }
            if (favourites.isEmpty()) {
                Text(
                    stringResource(R.string.ha_entities_favourites_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            } else {
                ReorderableColumn(
                    items = favourites,
                    label = { id -> hub.entities[id]?.let { registry.nameOf(it) } ?: id },
                    onReorder = { next -> scope.launch { repository.updateHaFavourites { next } } },
                    onDelete = { id -> scope.launch { repository.updateHaFavourites { it - id } } },
                    keepLast = false,
                )
            }
        }
    }

    val byArea = all.groupBy { registry.areaOf(it.entityId)?.name ?: "" }
    val areaOrder = registry.orderedAreas().map { it.name }.filter { it in byArea } + listOf("").filter { it in byArea }
    for (area in areaOrder) {
        val entities = byArea[area] ?: continue
        SettingsGroup(area.ifEmpty { stringResource(R.string.ha_entities_area_other) }) {
            for (e in entities) {
                item(key = e.entityId) {
                    val favourite = e.entityId in ha.favourites
                    val hidden = e.entityId in ha.hidden
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            ToggleSetting(
                                title = registry.nameOf(e),
                                subtitle = format.state(e),
                                checked = favourite,
                                enabled = !hidden,
                            ) { on -> scope.launch { repository.updateHaFavourites { if (on) it + e.entityId else it - e.entityId } } }
                        }
                        androidx.compose.material3.IconButton(onClick = { scope.launch { repository.updateHaHidden { if (hidden) it - e.entityId else it + e.entityId } } }) {
                            Icon(
                                Icons.Outlined.VisibilityOff,
                                contentDescription = if (hidden) stringResource(R.string.ha_entities_unhide, registry.nameOf(e)) else stringResource(R.string.ha_entities_hide),
                                tint = if (hidden) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    val domains = hub.entities.values.map { it.domain }.filter { HaDomains.isListed(it) }.distinct().sorted()
    if (domains.isNotEmpty()) {
        SettingsGroup(stringResource(R.string.ha_entities_domains_group), info = stringResource(R.string.ha_entities_domains_info)) {
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    domains.forEach { domain ->
                        val off = domain in ha.hiddenDomains
                        FilterChip(
                            selected = off,
                            onClick = { scope.launch { repository.setHaHiddenDomains(if (off) ha.hiddenDomains - domain else ha.hiddenDomains + domain) } },
                            label = { Text(domain.replace('_', ' ')) },
                            leadingIcon = if (off) ({ Icon(Icons.Outlined.VisibilityOff, contentDescription = null, modifier = Modifier.size(16.dp)) }) else null,
                        )
                    }
                }
            }
        }
    }
}

internal const val ENTITIES_ROUTE = "homeassistant/entities"
