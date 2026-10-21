package com.wasimaster.wmkeyboard.core.upscale

import com.wasimaster.wmkeyboard.core.imagegen.ImageGenModels
import java.io.File

/** What a picture is, which decides the network that enlarges it. */
enum class UpscaleKind {
    /** Stickers, drawings, cartoons, screenshots of text: flat colour and hard lines. */
    ART,

    /** Camera pictures: texture, noise, soft focus. */
    PHOTO,
}

/**
 * The upscaler's networks: one per [UpscaleKind], each a pinned `.mnn` file
 * from the data repository's `models` release (`<sha256>-<name>`), exported by
 * its `scripts/export_upscalers_mnn.py` from nagadomi's own waifu2x weights.
 * They run on the sticker generator's MNN library (`libwmimagegen`, the
 * `imagegen` gate), so the upscaler brings no native code of its own.
 *
 * Both are waifu2x's UpConv7 (MIT): six 3x3 convolutions at the small
 * picture's size and one transposed convolution to twice it, trained to undo
 * light JPEG noise as they enlarge ("noise1"). The art network is trained on
 * illustrations, the photo network on photographs. ×4 is two passes.
 *
 * Chosen by measurement (2026-10-21, Apple M2, MNN 3.6.1 CPU, 4 threads)
 * against Real-ESRGAN's compact SRVGG networks (realesr-animevideov3,
 * realesr-general-x4v3), its RRDB anime network and waifu2x's CUNet and
 * Swin-UNet: the most faithful of all of them on stickers, line art and
 * photos (OpenMoji stickers ×4 with transparency 38.9 dB against 30.4 dB for
 * the next fastest, realesr-animevideov3), and the fastest at photo sizes
 * (1080p ×2 in 2.7 s against 11.8 s), with no invented texture. MNN's
 * conversions of CUNet and Swin-UNet draw wrong pixels; RRDB takes 25 times
 * as long. MNN also draws wrong pixels in float16 here, so float32 it is.
 *
 * Every file is checked against its SHA-256 before it is used: a network that
 * loads but is not the expected one would draw nonsense rather than fail.
 */
object UpscaleModels {

    enum class Model(
        val id: String,
        val kind: UpscaleKind,
        val part: ImageGenModels.Part,
    ) {
        ART(
            id = "waifu2x-upconv7-art",
            kind = UpscaleKind.ART,
            part = ImageGenModels.Part(
                "sr-waifu2x-upconv7-art.mnn",
                2_212_756L,
                "a01167275376f18e224a17cf24e2f1dcec5bb636d21d7bf460abd5451631dd84",
            ),
        ),
        PHOTO(
            id = "waifu2x-upconv7-photo",
            kind = UpscaleKind.PHOTO,
            part = ImageGenModels.Part(
                "sr-waifu2x-upconv7-photo.mnn",
                2_212_756L,
                "7a306a9e37f86d3a48ec9d17724771b4229ebaf64f694c8534ca7e162be5704d",
            ),
        ),
        ;

        companion object {
            fun of(kind: UpscaleKind): Model = entries.first { it.kind == kind }

            fun byId(id: String): Model? = entries.firstOrNull { it.id == id }
        }
    }

    /** What one pass enlarges by. */
    const val SCALE = 2

    /**
     * Pixels of the small picture the network reads around each tile and
     * eats: unpadded convolutions shrink the input by this much on every
     * side, so a tile read with this margin comes back exactly twice its size.
     */
    const val CONTEXT = 7

    /**
     * The tile's side, in pixels of the small picture. Per pixel a 128 tile
     * costs what a 256 one does (1.04 against 1.07 µs on the M2), and MNN's
     * working memory for it is 82 MB rather than 188.
     */
    const val TILE = 128

    /** The network's input and output, as the export names them. */
    const val INPUT = "image"
    const val OUTPUT = "upscaled"

    /** The factors on offer. */
    val FACTORS = intArrayOf(2, 4)

    /** Beside the sticker generator's files, so its storage row counts these too. */
    fun dir(noBackupFilesDir: File): File = File(ImageGenModels.root(noBackupFilesDir), "upscale")

    fun file(noBackupFilesDir: File, model: Model): File = File(dir(noBackupFilesDir), model.part.fileName)

    fun isDownloaded(noBackupFilesDir: File, model: Model): Boolean =
        file(noBackupFilesDir, model).let { it.isFile && it.length() == model.part.bytes }
}
