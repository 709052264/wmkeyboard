package com.wasimaster.wmkeyboard.core.voice.asr4all

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * The `metadata.json` an asr4all bundle ships beside its program: the
 * vocabularies, the streaming tiers and their second-pass methods. Shapes are
 * not in it on purpose; they come from the program ([Asr4AllRunner.shape]).
 */
class Asr4AllMetadata(
    /** Acoustic vocabulary: BPE pieces, a leading space marking a word start. */
    val vocab: List<String>,
    val blankId: Int,
    /** Audio samples per acoustic frame (640 = 40 ms at 16 kHz). */
    val samplesPerFrame: Int,
    val tiers: List<Tier>,
    val defaultTier: String,
    /** The punctuation, casing and correction pass, when the bundle has one. */
    val pcec: Pcec?,
) {
    /** One latency point: a streaming method, and the second-pass methods that go with it. */
    class Tier(val name: String, val pcecMethod: String?, val pcecFlush: String?, val pcecCaches: Int)

    /** The second pass's vocabulary and the control slots it writes between words. */
    class Pcec(
        val vocab: List<String>,
        val blankId: Int,
        val cap: Int,
        val allCaps: Int,
        val period: Int,
        val comma: Int,
        val question: Int,
        /** Token id that fills an input block past its real tokens. */
        val padIn: Long,
        /** Pause bucket for a model trained with the pause channel blanked out. */
        val pauseBlackout: Long,
        /** Whether the real pause before each token is fed, or [pauseBlackout] throughout. */
        val realPause: Boolean,
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): Asr4AllMetadata {
            val root = json.parseToJsonElement(text).jsonObject
            fun JsonObject.strings(key: String) =
                getValue(key).jsonArray.map { it.jsonPrimitive.contentOrNull.orEmpty() }
            val tiers = root.getValue("tiers").jsonArray.map { element ->
                val tier = element.jsonObject
                val pcec = tier["pcec"]?.let { runCatching { it.jsonObject }.getOrNull() }
                Tier(
                    name = tier.getValue("name").jsonPrimitive.content,
                    pcecMethod = pcec?.get("name")?.jsonPrimitive?.contentOrNull,
                    pcecFlush = pcec?.get("flush")?.jsonPrimitive?.contentOrNull,
                    pcecCaches = pcec?.get("cache_tensors")?.jsonPrimitive?.intOrNull ?: 2,
                )
            }
            val pcec = root["pcec"]?.let { runCatching { it.jsonObject }.getOrNull() }?.let { p ->
                val ctrl = p.getValue("ctrl").jsonObject
                Pcec(
                    vocab = p.strings("vocab"),
                    blankId = p.getValue("blank_id").jsonPrimitive.int,
                    cap = ctrl.getValue("cap").jsonPrimitive.int,
                    allCaps = ctrl.getValue("allcaps").jsonPrimitive.int,
                    period = ctrl.getValue("period").jsonPrimitive.int,
                    comma = ctrl.getValue("comma").jsonPrimitive.int,
                    question = ctrl.getValue("question").jsonPrimitive.int,
                    padIn = p.getValue("pad_in").jsonPrimitive.int.toLong(),
                    pauseBlackout = p.getValue("pause_blackout").jsonPrimitive.int.toLong(),
                    realPause = p["real_pause"]?.jsonPrimitive?.booleanOrNull ?: false,
                )
            }
            return Asr4AllMetadata(
                vocab = root.strings("vocab"),
                blankId = root.getValue("blank_id").jsonPrimitive.int,
                samplesPerFrame = root.getValue("samples_per_frame").jsonPrimitive.int,
                tiers = tiers,
                defaultTier = root.getValue("default_tier").jsonPrimitive.content,
                pcec = pcec,
            )
        }
    }
}
