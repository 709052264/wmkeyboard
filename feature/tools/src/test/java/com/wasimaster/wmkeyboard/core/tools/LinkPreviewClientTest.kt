package com.wasimaster.wmkeyboard.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Metadata extraction from a page's `<head>` (no network in these tests). */
class LinkPreviewClientTest {

    @Test fun readsOpenGraphTags() {
        val preview = LinkPreviewClient.parse(
            """
            <html><head>
            <meta property="og:title" content="A Great Article">
            <meta property="og:description" content="Why it is great.">
            <meta property="og:site_name" content="Example News">
            <meta property="og:image" content="https://example.com/a.png">
            <title>ignored when og:title exists</title>
            </head></html>
            """.trimIndent()
        )
        assertEquals("A Great Article", preview.title)
        assertEquals("Why it is great.", preview.description)
        assertEquals("Example News", preview.siteName)
        assertEquals("https://example.com/a.png", preview.imageUrl)
    }

    @Test fun handlesContentBeforeProperty() {
        val preview = LinkPreviewClient.parse(
            """<meta content="Reversed Order" property="og:title">"""
        )
        assertEquals("Reversed Order", preview.title)
    }

    @Test fun fallsBackToTitleAndMetaDescription() {
        val preview = LinkPreviewClient.parse(
            """
            <html><head>
            <title>Plain Old Title</title>
            <meta name="description" content="A plain description.">
            </head></html>
            """.trimIndent()
        )
        assertEquals("Plain Old Title", preview.title)
        assertEquals("A plain description.", preview.description)
    }

    @Test fun fallsBackToTwitterCardTags() {
        val preview = LinkPreviewClient.parse(
            """<meta name="twitter:title" content="Card Title">"""
        )
        assertEquals("Card Title", preview.title)
    }

    @Test fun blankTagsFallThroughToTheNextSource() {
        // rust-lang.org's head, cut down: empty Open Graph and Twitter titles,
        // the real one in <title>.
        val preview = LinkPreviewClient.parse(
            """
            <meta name="twitter:title" content="">
            <meta property="og:title" content=" " />
            <meta property="og:description" content="">
            <meta name="description" content="Kept description.">
            <title>Kept</title>
            """.trimIndent()
        )
        assertEquals("Kept", preview.title)
        assertEquals("Kept description.", preview.description)
    }

    @Test fun decodesEntitiesAndCollapsesWhitespace() {
        val preview = LinkPreviewClient.parse(
            "<title>Tom &amp; Jerry&#39;s\n   &quot;Best&quot; Day</title>"
        )
        assertEquals("Tom & Jerry's \"Best\" Day", preview.title)
    }

    @Test fun emptyHeadIsAnEmptyPreview() {
        val preview = LinkPreviewClient.parse("<html><head></head><body>hi</body></html>")
        assertTrue(preview.isEmpty)
    }

    @Test fun longValuesAreTruncated() {
        val preview = LinkPreviewClient.parse("<title>${"x".repeat(500)}</title>")
        assertEquals(140, preview.title.length)
    }
}
