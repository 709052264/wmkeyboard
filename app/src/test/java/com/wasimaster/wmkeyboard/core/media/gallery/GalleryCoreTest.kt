package com.wasimaster.wmkeyboard.core.media.gallery

import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gallery tool's plain rules (#708): how albums are counted, how big a
 * sent copy is, what metadata is cleared, and the send history's order.
 */
class GalleryCoreTest {

    private fun row(
        bucket: String = "b1",
        name: String = "Camera",
        path: String = "DCIM/Camera/IMG_1.jpg",
        kind: GalleryKind = GalleryKind.IMAGE,
        favorite: Boolean = false,
        sent: Boolean = false,
    ) = AlbumTally.Row(1, kind, bucket, name, path, favorite, sent)

    @Test
    fun `recents leads even with an empty library`() {
        val albums = AlbumTally().albums(videos = true)
        assertEquals(listOf(AlbumKind.RECENTS), albums.map { it.kind })
        assertEquals(0, albums.single().count)
    }

    @Test
    fun `collections appear only when they hold something, in a fixed order`() {
        val tally = AlbumTally()
        tally.add(row(path = "Pictures/Screenshots/Screenshot_1.png", bucket = "s", name = "Screenshots"))
        tally.add(row(kind = GalleryKind.VIDEO, favorite = true))
        tally.add(row(kind = GalleryKind.GIF, bucket = "d", name = "Download", path = "Download/a.gif", sent = true))
        val kinds = tally.albums(videos = true).map { it.kind }
        assertEquals(
            listOf(
                AlbumKind.RECENTS, AlbumKind.SENT, AlbumKind.FAVORITES, AlbumKind.CAMERA,
                AlbumKind.SCREENSHOTS, AlbumKind.VIDEOS, AlbumKind.GIFS,
                AlbumKind.BUCKET, AlbumKind.BUCKET, AlbumKind.BUCKET,
            ),
            kinds,
        )
    }

    @Test
    fun `the videos collection is left out when videos are not listed`() {
        val tally = AlbumTally()
        tally.add(row(kind = GalleryKind.VIDEO))
        assertFalse(tally.albums(videos = false).any { it.kind == AlbumKind.VIDEOS })
    }

    @Test
    fun `folders sort biggest first and count every row`() {
        val tally = AlbumTally()
        repeat(2) { tally.add(row(bucket = "small", name = "Small", path = "Pictures/Small/x.jpg")) }
        repeat(5) { tally.add(row(bucket = "big", name = "Big", path = "Pictures/Big/x.jpg")) }
        val folders = tally.albums(videos = true).filter { it.kind == AlbumKind.BUCKET }
        assertEquals(listOf("Big", "Small"), folders.map { it.name })
        assertEquals(listOf(5, 2), folders.map { it.count })
        assertEquals(7, tally.albums(videos = true).first().count)
    }

    @Test
    fun `screenshots and camera are told apart by path, any case`() {
        assertTrue(AlbumTally.isScreenshot("Pictures/Screenshots/a.png"))
        assertTrue(AlbumTally.isScreenshot("DCIM/Screen_Shot_2026.png"))
        assertFalse(AlbumTally.isScreenshot("DCIM/Camera/IMG_1.jpg"))
        assertTrue(AlbumTally.isCamera("DCIM/Camera/IMG_1.jpg"))
        assertFalse(AlbumTally.isCamera("Pictures/WhatsApp/IMG_1.jpg"))
    }

    @Test
    fun `filters accept their own kind only`() {
        assertTrue(GalleryFilter.ALL.accepts(GalleryKind.VIDEO))
        assertTrue(GalleryFilter.PHOTOS.accepts(GalleryKind.IMAGE))
        assertFalse(GalleryFilter.PHOTOS.accepts(GalleryKind.GIF))
        assertTrue(GalleryFilter.GIFS.accepts(GalleryKind.GIF))
        assertFalse(GalleryFilter.VIDEOS.accepts(GalleryKind.IMAGE))
    }

    @Test
    fun `kind follows the mime type, and the media type for videos without one`() {
        assertEquals(GalleryKind.VIDEO, GalleryItem.kindOf("video/mp4", 0))
        assertEquals(GalleryKind.VIDEO, GalleryItem.kindOf("", GalleryItem.MEDIA_TYPE_VIDEO))
        assertEquals(GalleryKind.GIF, GalleryItem.kindOf("image/gif", 1))
        assertEquals(GalleryKind.IMAGE, GalleryItem.kindOf("image/jpeg", 1))
    }

    @Test
    fun `target size keeps the shape and never grows`() {
        assertEquals(2560 to 1920, MediaPrep.targetSize(4000, 3000, 2560))
        assertEquals(1440 to 2560, MediaPrep.targetSize(2250, 4000, 2560))
        assertEquals(800 to 600, MediaPrep.targetSize(800, 600, 1280))
        assertEquals(4000 to 3000, MediaPrep.targetSize(4000, 3000, 0))
    }

    @Test
    fun `sample size halves while the picture stays at least the target`() {
        assertEquals(1, MediaPrep.sampleSize(1000, 800, 800))
        assertEquals(2, MediaPrep.sampleSize(4000, 3000, 1280))
        assertEquals(4, MediaPrep.sampleSize(8000, 6000, 1280))
        assertEquals(1, MediaPrep.sampleSize(8000, 6000, 0))
    }

    @Test
    fun `every EXIF orientation turns and mirrors the way the standard says`() {
        assertEquals(MediaPrep.Orientation(0), MediaPrep.rotationOf(ExifInterface.ORIENTATION_NORMAL))
        assertEquals(MediaPrep.Orientation(90), MediaPrep.rotationOf(ExifInterface.ORIENTATION_ROTATE_90))
        assertEquals(MediaPrep.Orientation(180), MediaPrep.rotationOf(ExifInterface.ORIENTATION_ROTATE_180))
        assertEquals(MediaPrep.Orientation(270), MediaPrep.rotationOf(ExifInterface.ORIENTATION_ROTATE_270))
        assertEquals(MediaPrep.Orientation(0, true), MediaPrep.rotationOf(ExifInterface.ORIENTATION_FLIP_HORIZONTAL))
        assertEquals(MediaPrep.Orientation(90, true), MediaPrep.rotationOf(ExifInterface.ORIENTATION_TRANSPOSE))
        assertEquals(MediaPrep.Orientation(0), MediaPrep.rotationOf(ExifInterface.ORIENTATION_UNDEFINED))
    }

    @Test
    fun `location clearing takes every GPS tag and nothing that keeps the picture upright`() {
        val location = MediaPrep.tagsToClear(all = false)
        assertTrue(ExifInterface.TAG_GPS_LATITUDE in location)
        assertTrue(ExifInterface.TAG_GPS_LONGITUDE in location)
        assertTrue(location.all { it.startsWith("GPS") })
        val all = MediaPrep.tagsToClear(all = true)
        assertTrue(ExifInterface.TAG_MAKE in all)
        assertTrue(ExifInterface.TAG_BODY_SERIAL_NUMBER in all)
        assertFalse(ExifInterface.TAG_ORIENTATION in all)
        assertFalse(ExifInterface.TAG_COLOR_SPACE in all)
    }

    @Test
    fun `only the formats ExifInterface can write keep their bytes`() {
        assertTrue(MediaPrep.canRewriteExif("image/jpeg"))
        assertTrue(MediaPrep.canRewriteExif("image/webp"))
        assertFalse(MediaPrep.canRewriteExif("image/heic"))
    }

    @Test
    fun `copies get a random name unless asked to keep the photo's own, made safe`() {
        val item = item(name = "Holiday: beach/1.jpg")
        val random = MediaPrep.fileName(item, "jpg", MediaPrep.Options(randomName = true))
        assertTrue(random.matches(Regex("IMG_[0-9a-f]{10}\\.jpg")))
        val kept = MediaPrep.fileName(item, "jpg", MediaPrep.Options(randomName = false))
        assertTrue(kept, kept.startsWith("Holiday_ beach_1_"))
        assertTrue(kept.endsWith(".jpg"))
        val video = MediaPrep.fileName(item(kind = GalleryKind.VIDEO), "mp4", MediaPrep.Options())
        assertTrue(video.startsWith("VID_"))
    }

    @Test
    fun `size estimate shrinks with the quality and never exceeds the file`() {
        val photo = item(width = 4000, height = 3000, size = 5_000_000)
        val hd = MediaPrep.estimateBytes(photo, GalleryQuality.HD)
        val small = MediaPrep.estimateBytes(photo, GalleryQuality.SMALL)
        assertTrue(small < hd)
        assertTrue(hd <= photo.size)
        assertEquals(photo.size, MediaPrep.estimateBytes(photo, GalleryQuality.ORIGINAL))
        val video = item(kind = GalleryKind.VIDEO, size = 9_000_000)
        assertEquals(video.size, MediaPrep.estimateBytes(video, GalleryQuality.SMALL))
    }

    @Test
    fun `the sent history puts the latest send first without repeats`() {
        assertEquals(listOf(3L, 2L, 1L), GallerySentHistory.merge(emptyList(), listOf(1, 2, 3)))
        // 5 went last, so it leads; 2 went before it; the rest keep their order.
        assertEquals(listOf(5L, 2L, 9L, 1L), GallerySentHistory.merge(listOf(9, 2, 1), listOf(2, 5)))
        val long = (1L..300L).toList()
        assertEquals(GallerySentHistory.MAX, GallerySentHistory.merge(long, listOf(1000L)).size)
    }

    @Test
    fun `quality names round-trip and unknown ones are null`() {
        GalleryQuality.entries.forEach { assertEquals(it, GalleryQuality.of(it.name)) }
        assertEquals(null, GalleryQuality.of("HUGE"))
    }

    @Test
    fun `shown size swaps for a sideways photo`() {
        val sideways = item(width = 4000, height = 3000, orientation = 90)
        assertEquals(3000, sideways.shownWidth)
        assertEquals(4000, sideways.shownHeight)
        assertEquals(0.75f, sideways.aspect, 0.001f)
    }

    private fun item(
        name: String = "IMG_1.jpg",
        kind: GalleryKind = GalleryKind.IMAGE,
        width: Int = 100,
        height: Int = 100,
        size: Long = 1000,
        orientation: Int = 0,
    ) = GalleryItem(
        id = 1, mime = if (kind == GalleryKind.VIDEO) "video/mp4" else "image/jpeg",
        kind = kind, name = name, width = width, height = height, orientation = orientation, size = size,
        durationMs = 0, dateMs = 0, bucketId = "", bucketName = "", favorite = false,
    )
}
