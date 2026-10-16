package com.wasimaster.wmkeyboard.app

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.hasSearchKey
import com.wasimaster.wmkeyboard.core.tools.AiBuiltInTool
import com.wasimaster.wmkeyboard.core.tools.AiToolGroup
import com.wasimaster.wmkeyboard.core.tools.AiTools
import com.wasimaster.wmkeyboard.core.tools.McpToolPolicy
import kotlinx.coroutines.launch

/** The route of [AiBuiltInToolSettings]. */
internal const val AI_BUILT_IN_TOOLS_ROUTE = "ai_tools"

/** What the settings call one of the keyboard's own AI tools, and what it says under it. */
internal class BuiltInToolText(@StringRes val title: Int, @StringRes val subtitle: Int)

/** Every tool in [AiTools.builtIns] has an entry; one without would have no row. */
internal val builtInToolText: Map<String, BuiltInToolText> = mapOf(
    AiTools.IMAGE_SEARCH to BuiltInToolText(
        R.string.toolai_ai_builtin_image_search_title,
        R.string.toolai_ai_builtin_image_search_subtitle,
    ),
    AiTools.WEATHER to BuiltInToolText(
        R.string.toolai_ai_builtin_weather_title,
        R.string.toolai_ai_builtin_weather_subtitle,
    ),
    AiTools.WIKIPEDIA to BuiltInToolText(
        R.string.toolai_ai_builtin_wikipedia_title,
        R.string.toolai_ai_builtin_wikipedia_subtitle,
    ),
    AiTools.DEFINE_WORD to BuiltInToolText(
        R.string.toolai_ai_builtin_define_word_title,
        R.string.toolai_ai_builtin_define_word_subtitle,
    ),
    AiTools.TRANSLATE to BuiltInToolText(
        R.string.toolai_ai_builtin_translate_title,
        R.string.toolai_ai_builtin_translate_subtitle,
    ),
    AiTools.CONVERT_CURRENCY to BuiltInToolText(
        R.string.toolai_ai_builtin_convert_currency_title,
        R.string.toolai_ai_builtin_convert_currency_subtitle,
    ),
    AiTools.CONVERT_UNITS to BuiltInToolText(
        R.string.toolai_ai_builtin_convert_units_title,
        R.string.toolai_ai_builtin_convert_units_subtitle,
    ),
    AiTools.CALCULATE to BuiltInToolText(
        R.string.toolai_ai_builtin_calculate_title,
        R.string.toolai_ai_builtin_calculate_subtitle,
    ),
)

/** Each group's heading and the "?" under it. */
internal val AiToolGroup.titleRes: Int
    @StringRes get() = when (this) {
        AiToolGroup.INFO -> R.string.toolai_ai_builtin_info_group_title
        AiToolGroup.DATA -> R.string.toolai_ai_builtin_data_group_title
        AiToolGroup.PICTURE -> R.string.toolai_ai_builtin_picture_group_title
        AiToolGroup.ACTION -> R.string.toolai_ai_builtin_action_group_title
    }

private val AiToolGroup.infoRes: Int
    @StringRes get() = when (this) {
        AiToolGroup.INFO -> R.string.toolai_ai_builtin_info_group_info
        AiToolGroup.DATA -> R.string.toolai_ai_builtin_data_group_info
        AiToolGroup.PICTURE -> R.string.toolai_ai_builtin_picture_group_info
        AiToolGroup.ACTION -> R.string.toolai_ai_builtin_action_group_info
    }

/**
 * The keyboard's own tools a model may use (#698), each with the same Run /
 * Ask / Off an MCP server's tools have, grouped by what they touch.
 */
@Composable
internal fun AiBuiltInToolSettings(
    repository: SettingsRepository,
    settings: LiveSettings,
) {
    val scope = rememberCoroutineScope()
    val searchReady = settings.watch { hasSearchKey(it) }
    for (group in AiToolGroup.entries) {
        val tools = AiTools.builtIns.filter { it.group == group && it.name in builtInToolText }
        if (tools.isEmpty()) continue
        SettingsGroup(stringResource(group.titleRes), info = stringResource(group.infoRes)) {
            for (tool in tools) {
                item(visible = tool.name != AiTools.IMAGE_SEARCH || searchReady, key = tool.name) {
                    val policy = settings.watch { AiTools.policyOf(tool, it.ai.toolPolicies) }
                    BuiltInToolRow(tool, policy) { picked ->
                        scope.launch { repository.setAiToolPolicy(tool.name, picked) }
                    }
                }
            }
        }
    }
}

/** How many of the keyboard's own tools a model is offered: the count on the row that opens the screen. */
internal fun builtInToolsOn(policies: Map<String, McpToolPolicy>): Int =
    AiTools.builtIns.count { AiTools.policyOf(it, policies) != McpToolPolicy.OFF }

@Composable
private fun BuiltInToolRow(tool: AiBuiltInTool, policy: McpToolPolicy, onPick: (McpToolPolicy) -> Unit) {
    val text = builtInToolText.getValue(tool.name)
    WmRow(
        title = stringResource(text.title),
        highlightKey = text.title,
        supporting = {
            Column {
                Text(stringResource(text.subtitle))
                ToolPolicyChoices(policy, onPick, Modifier.padding(top = 8.dp))
            }
        },
    )
}
