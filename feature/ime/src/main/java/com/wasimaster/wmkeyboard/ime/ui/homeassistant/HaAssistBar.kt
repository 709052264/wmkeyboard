package com.wasimaster.wmkeyboard.ime.ui.homeassistant

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Assistant
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Send
import com.wasimaster.wmkeyboard.core.theme.FrostSurface
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.homeassistant.AssistUi
import com.wasimaster.wmkeyboard.ime.homeassistant.HaAction
import com.wasimaster.wmkeyboard.ime.ui.LocalKbTheme
import com.wasimaster.wmkeyboard.ime.ui.cardShape
import com.wasimaster.wmkeyboard.ime.ui.chipBorder
import com.wasimaster.wmkeyboard.ime.ui.clock.PillButton
import com.wasimaster.wmkeyboard.ime.ui.frostGlass

/**
 * The typed command bar at the bottom of the panel: a pill that takes the
 * keys when tapped, a reply that slides up over it, and a way to put the reply
 * into the field. While the pill is being typed into, the header carries the
 * text; this is the resting face and the reply.
 */
@Composable
internal fun HaAssistBar(assist: AssistUi, typing: Boolean, animate: Boolean, act: (HaAction) -> Unit) {
    val kb = LocalKbTheme.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
        val reply = assist.reply
        AnimatedVisibility(
            visible = reply != null || assist.error != null || assist.pending,
            enter = if (animate) slideInVertically(tween(180)) { it / 3 } + fadeIn(tween(180)) + expandVertically(tween(180)) else fadeIn(tween(0)),
            exit = if (animate) shrinkVertically(tween(140)) + fadeOut(tween(120)) else fadeOut(tween(0)),
        ) {
            ReplyBubble(assist, animate, act)
        }
        if (!typing) {
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(kb.cardShape())
                    .frostGlass(kb, kb.cardShape(), FrostSurface.CHIPS)
                    .background(kb.chip)
                    .chipBorder(kb, kb.cardShape())
                    .clickable { act(HaAction.AssistOpen) }
                    .padding(start = 12.dp, end = 6.dp, top = 7.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Assistant, contentDescription = null, tint = kb.accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    text = assist.query.ifEmpty { stringResource(R.string.ime_ha_assist_hint) },
                    color = if (assist.query.isEmpty()) kb.secondaryText else kb.suggestionText,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (assist.query.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(kb.accent)
                            .clickable { act(HaAction.AssistSend) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.Send, contentDescription = stringResource(R.string.ime_ha_assist_send_desc), tint = kb.toolCircleActiveIcon, modifier = Modifier.size(15.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ReplyBubble(assist: AssistUi, animate: Boolean, act: (HaAction) -> Unit) {
    val kb = LocalKbTheme.current
    val shape = kb.cardShape()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .frostGlass(kb, shape, FrostSurface.CARDS)
            .background(kb.accent.copy(alpha = 0.14f))
            .chipBorder(kb, shape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                assist.asked,
                color = kb.secondaryText,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Outlined.Close,
                contentDescription = stringResource(R.string.ime_ha_cancel),
                tint = kb.secondaryText,
                modifier = Modifier.size(16.dp).clickable { act(HaAction.AssistClear) },
            )
        }
        Spacer(Modifier.height(4.dp))
        when {
            assist.pending -> ThinkingDots(animate)
            assist.error != null -> Text(assist.error, color = kb.suggestionText, fontSize = 13.sp)
            assist.reply != null -> {
                val speech = assist.reply.speech.ifBlank { stringResource(R.string.ime_ha_assist_nothing) }
                Text(speech, color = kb.suggestionText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                if (assist.reply.speech.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PillButton(stringResource(R.string.ime_ha_assist_insert), height = 30.dp) { act(HaAction.AssistInsertReply) }
                    }
                }
            }
        }
    }
}

/** Three dots that breathe in turn while Assist thinks; the only infinite animation here, and only while pending. */
@Composable
private fun ThinkingDots(animate: Boolean) {
    val kb = LocalKbTheme.current
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        if (!animate) {
            Text(stringResource(R.string.ime_ha_assist_thinking), color = kb.secondaryText, fontSize = 12.sp)
            return
        }
        val transition = rememberInfiniteTransition(label = "haDots")
        repeat(3) { i ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(600, delayMillis = i * 150), RepeatMode.Reverse),
                label = "dot$i",
            )
            Box(Modifier.size(7.dp).alpha(alpha).clip(CircleShape).background(kb.accent))
        }
        Spacer(Modifier.width(4.dp))
        Text(stringResource(R.string.ime_ha_assist_thinking), color = kb.secondaryText, fontSize = 12.sp)
    }
}
