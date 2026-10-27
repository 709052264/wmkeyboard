package com.wasimaster.wmkeyboard.docshots

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.app.DocsShotActivity
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.LayoutFile
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.theme.ThemeAnimation
import com.wasimaster.wmkeyboard.core.theme.ThemeCodec
import com.wasimaster.wmkeyboard.core.theme.ThemeSpec
import com.wasimaster.wmkeyboard.core.theme.withExtractedImages
import com.wasimaster.wmkeyboard.core.theme.withFreshIds
import com.wasimaster.wmkeyboard.core.util.readCapped
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * The community addon repository's full pass: one submitted theme or layout,
 * checked by the app's own importer and drawn on the real keyboard.
 *
 *   ./gradlew :app:testEnDebugUnitTest -Pwmkb.docShots=true \
 *     --tests 'com.wasimaster.wmkeyboard.docshots.AddonShots' \
 *     -Pwmkb.addonShots.in=<dir> -Pwmkb.addonShots.out=<dir>
 *
 * The in directory holds `request.json`, `{"type":"theme"|"layout","id":…,"file":…}`,
 * beside the file it names. The out directory gets `<id>.png` (a theme also
 * `<id>-landscape.png` when it has a landscape image; a layout `<id>-dark.png`)
 * and always `<id>.report.json`, `{"ok","refusal","warnings","images"}`, which
 * the bot reads whatever happened: a refused file and a render that threw both
 * still write one.
 *
 * Validation is the importer itself, not a copy of its rules: a theme takes
 * the steps the Themes screen's Import button takes, a layout those of the
 * layout editor's. Whatever the app would refuse is refused here with the
 * message the app would show. The render is [KeyboardShots]' boot, the theme
 * worn or the layout showing, with a few words typed so the strip has
 * suggestions in it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w393dp-h851dp-normal-long-notround-any-440dpi-keyshidden-nonav")
class AddonShots {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val input = File(System.getProperty("wmkb.addonShots.in").orEmpty())
    private val out = File(System.getProperty("wmkb.addonShots.out").orEmpty().ifBlank { "build/addonshots" })

    private val all = SemanticsMatcher("any") { true }

    @Test
    fun render() {
        // The docs run picks up every class in this package; with no request it has nothing to do.
        assumeTrue(input.path.isNotBlank())
        val app = ApplicationProvider.getApplicationContext<Application>()
        grantInternet(app)
        androidx.test.espresso.IdlingPolicies.setMasterPolicyTimeout(8, TimeUnit.SECONDS)
        androidx.test.espresso.IdlingPolicies.setIdlingResourceTimeout(8, TimeUnit.SECONDS)
        out.mkdirs()

        val request = Json.parseToJsonElement(File(input, "request.json").readText()).jsonObject
        val type = request.getValue("type").jsonPrimitive.content
        val id = request.getValue("id").jsonPrimitive.content
        val file = File(input, request.getValue("file").jsonPrimitive.content)
        require(id.matches(Regex("[a-z0-9][a-z0-9-]*"))) { "id \"$id\" is not a slug" }

        var refusal: String? = null
        val warnings = mutableListOf<String>()
        val images = mutableListOf<String>()
        try {
            val repo = SettingsRepository(app)
            runBlocking {
                repo.clearAllPreferences()
                repo.setOnboardingDone(true)
            }
            when (type) {
                "theme" -> {
                    val theme = runBlocking { importTheme(app, repo, file) }
                    if (theme == null) {
                        refusal = app.getString(R.string.theme_import_wrong_file_error)
                    } else {
                        warnings += themeWarnings(theme)
                        shoot("$id.png", dark = theme.dark, landscape = false)
                        images += "$id.png"
                        if (theme.backgroundImageLandscape != null) {
                            shoot("$id-landscape.png", dark = theme.dark, landscape = true)
                            images += "$id-landscape.png"
                        }
                    }
                }
                "layout" -> {
                    val notes = runBlocking { importLayout(app, repo, file) }
                    if (notes == null) {
                        refusal = app.getString(R.string.layout_editor_import_wrong_file_error)
                    } else {
                        warnings += notes
                        shoot("$id.png", dark = false, landscape = false)
                        images += "$id.png"
                        shoot("$id-dark.png", dark = true, landscape = false)
                        images += "$id-dark.png"
                    }
                }
                else -> refusal = "Unknown addon type \"$type\"."
            }
        } catch (failure: Throwable) {
            // The bot shows this as the reason; the stack goes beside it for whoever fixes the harness.
            refusal = refusal ?: "The render failed: $failure"
            File(out, "$id.fail.txt").writeText("$failure\n" + failure.stackTrace.take(30).joinToString("\n"))
        } finally {
            writeReport(id, refusal, warnings, images)
            resetFakes()
        }
    }

    /**
     * The Themes screen's Import, step for step: read under the import cap,
     * decode, remint the ids, write the base64 images out, store and wear it.
     * Null where that screen would say the file is not a theme.
     */
    private suspend fun importTheme(app: Application, repo: SettingsRepository, file: File): ThemeSpec? {
        val text = runCatching { file.inputStream().use { it.readCapped() } }.getOrNull()?.decodeToString()
        val parsed = text?.let { ThemeCodec.decode(it) } ?: return null
        val id = "custom_${System.currentTimeMillis()}"
        val stored = parsed.withFreshIds(id)
            .withExtractedImages(File(app.filesDir, "theme_images").apply { mkdirs() })
        repo.upsertCustomTheme(stored)
        repo.setKeyboardThemeId(id)
        return stored
    }

    /**
     * The layout editor's Import, then "Import and turn on", then the layout
     * picked. The repair notes come back as the editor would list them; null
     * where it would say the file is not a layout.
     */
    private suspend fun importLayout(app: Application, repo: SettingsRepository, file: File): List<String>? {
        val text = runCatching { file.inputStream().use { it.readCapped() } }.getOrNull()?.decodeToString()
        val imported = text?.let { LayoutFile.decode(it) } ?: return null
        val id = "custom_${System.currentTimeMillis()}"
        repo.upsertCustomLayout(imported.layout.copy(id = id))
        repo.setEnabledLayoutIds(listOf("builtin_qwerty", id))
        repo.setActiveLayoutId(id)
        return imported.repairNotes.map { "Repaired on import: " + it.format(app.resources) }
    }

    /** What the runner draws with its defaults, since nothing is downloaded or installed here. */
    private fun themeWarnings(theme: ThemeSpec): List<String> = buildList {
        val fonts = listOfNotNull(theme.fontId) + theme.scriptFontIds.values
        for (font in fonts.distinct()) {
            when {
                font.startsWith("google:") ->
                    add("Font \"${font.removePrefix("google:")}\" is a Google font; the preview draws the default font in its place.")
                font.startsWith("installed:") ->
                    add("Font \"${font.removePrefix("installed:")}\" is an installed font addon; the preview draws the default font in its place.")
            }
        }
        if (theme.soundStyle == "CUSTOM" && !theme.soundCustomId.isNullOrBlank()) {
            add("Sound \"${theme.soundCustomId}\" is an installed sound addon; on a phone without it the global sound plays.")
        }
        if (theme.animation != ThemeAnimation.NONE) {
            add("The theme is animated (${theme.animation.name}); the preview is one still frame.")
        }
    }

    /**
     * One capture of the keyboard as it stands in settings, docked under the
     * host's field after a few typed words, the way [KeyboardShots.capture]
     * takes its shots.
     */
    private fun shoot(name: String, dark: Boolean, landscape: Boolean) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        RuntimeEnvironment.setQualifiers(if (dark) "+night" else "+notnight")
        RuntimeEnvironment.setQualifiers(if (landscape) "+land" else "+port")
        compose.mainClock.autoAdvance = true
        val intent = Intent(app, DocsShotActivity::class.java)
            .putExtra("mode", "field")
            .putExtra("kind", "text")
            .putExtra("action", "none")
            .putExtra("hint", "Message")
            .putExtra("theme", if (dark) "dark" else "light")
        ActivityScenario.launch<DocsShotActivity>(intent).use { scenario ->
            val controller = Robolectric.buildService(ShotKeyboard::class.java)
            try {
                settle(first = true)
                runCatching { compose.onAllNodes(hasSetTextAction()).onFirst().performClick() }
                var field: android.view.View? = null
                scenario.onActivity { field = it.window.decorView.findFocus() }
                compose.waitForIdle()
                val info = EditorInfo().apply { packageName = "com.example.messages" }
                // The focused field's cursor blinks forever; time moves only on a tick from here.
                compose.mainClock.autoAdvance = false
                val service = controller.create().get()
                service.ic = field?.onCreateInputConnection(info)
                service.info = info
                service.onStartInput(info, false)
                val view = service.onCreateInputView()
                service.onStartInputView(info, false)
                scenario.onActivity { activity ->
                    (view.parent as? ViewGroup)?.removeView(view)
                    (activity.window.decorView as ViewGroup).addView(
                        view,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            Gravity.BOTTOM,
                        ),
                    )
                }
                settle(first = true)
                for (c in PHRASE) {
                    service.onKey(if (c == ' ') Key(label = " ", action = KeyAction.Space) else Key(label = c.toString()))
                    tick()
                }
                settle()
                captureScreenRoboImage(File(out, name).path)
            } finally {
                runCatching { controller.destroy() }
            }
        }
    }

    private fun settle(first: Boolean = false) {
        val deadline = System.currentTimeMillis() + if (first) 20_000 else 8_000
        var last = -1
        var stable = 0
        while (stable < 4 && System.currentTimeMillis() < deadline) {
            tick()
            val count = runCatching { compose.onAllNodes(all).fetchSemanticsNodes().size }.getOrDefault(0)
            if (count > 3 && count == last) stable++ else stable = 0
            last = count
            Thread.sleep(120)
        }
    }

    private fun tick() {
        compose.mainClock.advanceTimeBy(100)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
    }

    private fun writeReport(id: String, refusal: String?, warnings: List<String>, images: List<String>) {
        val report = buildJsonObject {
            put("ok", refusal == null)
            put("refusal", refusal?.let(::JsonPrimitive) ?: JsonNull)
            put("warnings", buildJsonArray { warnings.forEach { add(JsonPrimitive(it)) } })
            put("images", buildJsonArray { images.forEach { add(JsonPrimitive(it)) } })
        }
        File(out, "$id.report.json").writeText(report.toString() + "\n")
    }

    private companion object {
        /** Stops mid-word, so the strip shows completions rather than what follows a space. */
        const val PHRASE = "See you tomo"
    }
}
