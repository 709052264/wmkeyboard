package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ArrowDropDown
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Check
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Dns
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PhoneAndroid
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Public
import com.wasimaster.wmkeyboard.core.providers.ProviderLogos
import com.wasimaster.wmkeyboard.core.providers.ProviderTool
import com.wasimaster.wmkeyboard.core.providers.ProviderType
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.ProviderChoices
import com.wasimaster.wmkeyboard.core.settings.ResolvedProvider
import com.wasimaster.wmkeyboard.core.settings.providerChoices
import com.wasimaster.wmkeyboard.core.theme.FrostSurface
import com.wasimaster.wmkeyboard.core.ui.ScrollRail
import com.wasimaster.wmkeyboard.core.ui.rememberScrollRailState
import com.wasimaster.wmkeyboard.ime.ProviderPicks
import com.wasimaster.wmkeyboard.ime.R

/**
 * The chooser a panel shows when its tool's provider list is in pick mode
 * ("Choose in the panel"): a chip naming the provider in use, and under it
 * the providers that are on. Picking one asks it alone from then on; the
 * service hears the change through [ProviderPicks] and runs the panel's
 * last request again.
 */

/** [tool]'s chooser for the panel, or null when its list is not in pick mode or has one row on. */
@Composable
internal fun rememberProviderChoices(tool: ProviderTool, settings: KeyboardSettings): ProviderChoices? {
    val picks by ProviderPicks.picks.collectAsState()
    val pick = picks[tool]
    return remember(settings, pick) { settings.providerChoices(tool, pick.orEmpty())?.takeIf { it.shown } }
}

/** The chip and its menu. Draws nothing unless the list asks the panel to choose. */
@Composable
internal fun ProviderChooser(
    tool: ProviderTool,
    settings: KeyboardSettings,
    modifier: Modifier = Modifier,
    /** Over a camera picture: white on a dark wash, not the theme's chip. */
    overPhoto: Boolean = false,
) {
    val choices = rememberProviderChoices(tool, settings) ?: return
    ProviderChooser(choices, onPick = { ProviderPicks.choose(tool, it) }, modifier = modifier, overPhoto = overPhoto)
}

@Composable
internal fun ProviderChooser(
    choices: ProviderChoices,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    overPhoto: Boolean = false,
    focused: Boolean = false,
) {
    val kb = LocalKbTheme.current
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val chosen = choices.rows.firstOrNull { it.key == choices.chosen } ?: choices.rows.first()
    val shape = kb.chipShape()
    val text = if (overPhoto) Color.White else kb.chipText
    Box(modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(shape)
                .then(
                    if (overPhoto) {
                        Modifier.background(Color.Black.copy(alpha = 0.45f))
                    } else {
                        Modifier
                            .frostGlass(kb, shape, FrostSurface.CHIPS)
                            .background(kb.chip)
                            .chipBorder(kb, shape)
                    },
                )
                .focusRing(focused, shape)
                .clickable { open = true }
                .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        ) {
            ProviderGlyph(chosen.type, if (overPhoto) Color.White else kb.toolbarIcon, 14.dp)
            Spacer(Modifier.width(5.dp))
            Text(
                providerName(context, chosen),
                color = text,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 140.dp),
            )
            Icon(
                Icons.Outlined.ArrowDropDown,
                contentDescription = stringResource(R.string.ime_provider_choose_desc),
                modifier = Modifier.size(18.dp),
                tint = if (overPhoto) Color.White else kb.toolbarIcon,
            )
        }
        if (open) {
            ProviderMenu(choices.rows, choices.chosen, onPick = {
                open = false
                onPick(it)
            }) { open = false }
        }
    }
}

@Composable
private fun ProviderMenu(
    rows: List<ResolvedProvider>,
    chosen: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val context = LocalContext.current
    val scroll = rememberScrollState()
    Popup(onDismissRequest = onDismiss) {
        ScrollRail(
            state = rememberScrollRailState(scroll),
            modifier = Modifier
                .widthIn(min = 180.dp, max = 260.dp)
                .heightIn(max = 260.dp)
                .clip(kb.menuShape())
                .background(kb.popup)
                .popupBorder(kb, kb.menuShape())
                .padding(vertical = 4.dp),
            fadeColor = kb.popup,
            colors = kbRailColors(kb),
        ) {
            for (row in rows) {
                val selected = row.key == chosen
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(row.key) }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                ) {
                    ProviderGlyph(row.type, if (selected) kb.accent else kb.popupText, 18.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        providerName(context, row),
                        color = if (selected) kb.accent else kb.popupText,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 14.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (selected) {
                        Icon(Icons.Outlined.Check, contentDescription = null, tint = kb.accent, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

/** A provider's logo where the app carries one, else a glyph for what kind of thing it is. */
@Composable
internal fun ProviderGlyph(type: ProviderType?, tint: Color, size: Dp) {
    val logo = type?.let { ProviderLogos.of(it.id) }
    val plate = logo?.backdrop
    if (logo != null && plate != null) {
        // A picture that vanishes on a light or a dark keyboard sits on its own plate, as in settings.
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(size).background(Color(plate), RoundedCornerShape(size / 4)),
        ) {
            Icon(painterResource(logo.res), contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(size * 0.72f))
        }
        return
    }
    if (logo != null) {
        Icon(
            painterResource(logo.res),
            contentDescription = null,
            // A one-colour mark takes the text's colour; a brand's own colours stay.
            tint = if (logo.monochrome) tint else Color.Unspecified,
            modifier = Modifier.size(size),
        )
        return
    }
    Icon(
        when {
            type?.onDevice == true -> Icons.Outlined.PhoneAndroid
            type?.selfHosted == true -> Icons.Outlined.Dns
            else -> Icons.Outlined.Public
        },
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(size),
    )
}

/** The name the user gave a server, else its kind's. */
internal fun providerName(context: android.content.Context, row: ResolvedProvider): String =
    row.customName.ifEmpty { row.type?.let { context.getString(it.labelRes) } ?: row.typeId }
