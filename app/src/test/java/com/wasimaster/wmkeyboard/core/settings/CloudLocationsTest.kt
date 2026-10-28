package com.wasimaster.wmkeyboard.core.settings

import com.wasimaster.wmkeyboard.core.settings.sink.BoxSink
import com.wasimaster.wmkeyboard.core.settings.sink.MegaSink
import com.wasimaster.wmkeyboard.core.settings.sink.NasSink
import com.wasimaster.wmkeyboard.core.settings.sink.NfsSink
import com.wasimaster.wmkeyboard.core.settings.sink.PCloudSink
import com.wasimaster.wmkeyboard.core.settings.sink.QnapSink
import com.wasimaster.wmkeyboard.core.settings.sink.SinkError
import com.wasimaster.wmkeyboard.core.settings.sink.SynologySink
import com.wasimaster.wmkeyboard.core.settings.sink.nfs.NfsStatusException
import com.wasimaster.wmkeyboard.core.settings.sink.nfs.RpcDeniedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Box, pCloud, Synology, QNAP, NFS, MEGA, iCloud Drive and Proton Drive
 * locations: what is stored, and what each service's failures mean. The
 * crypto behind MEGA, iCloud and Proton is in CloudCryptoTest.
 */
class CloudLocationsTest {

    @Test
    fun `every cloud section survives the stored list`() {
        val locations = listOf(
            BackupLocation(id = "aaaaaaa1", type = BackupDestination.BOX, refreshToken = "r", box = BoxConfig("A/B")),
            BackupLocation(id = "aaaaaaa2", type = BackupDestination.PCLOUD, pcloud = PCloudConfig(PCloudRegion.EU, "me@x", "tok", "P")),
            BackupLocation(
                id = "aaaaaaa3", type = BackupDestination.SYNOLOGY,
                nas = NasConfig("nas.local:5001", "u", "p", "/home/Drive/WM", "did", ownCertificate = true, certPin = "pin"),
            ),
            BackupLocation(id = "aaaaaaa4", type = BackupDestination.QNAP, nas = NasConfig("qnap", "u", "p", "/Public/WM")),
            BackupLocation(id = "aaaaaaa5", type = BackupDestination.NFS, nfs = NfsConfig("srv", "/srv/nfs", "wm", 501, 20, 2049, 635)),
            BackupLocation(id = "aaaaaaa6", type = BackupDestination.MEGA, mega = MegaConfig("me@x", "sid", "key", "M")),
            BackupLocation(
                id = "aaaaaaa7", type = BackupDestination.ICLOUD,
                icloud = ICloudConfig("me@icloud.com", "pw", "trust", "a=b; c=d", "cid", 1234L, "I"),
            ),
            BackupLocation(id = "aaaaaaa8", type = BackupDestination.PROTON, proton = ProtonConfig("me@proton.me", "uid", "acc", "ref", "salted", "P")),
        )
        assertEquals(locations, BackupLocation.decodeList(BackupLocation.encodeList(locations)))
        assertTrue(locations.all { it.configured })
    }

    @Test
    fun `a section written before a field existed gets its default`() {
        val decoded = BackupLocation.decodeList("""[{"id":"12345678","type":"nfs","nfs":{"host":"h","export":"/e"}}]""").single()
        assertEquals(NfsConfig.DEFAULT_ID, decoded.nfs.uid)
        assertEquals(0, decoded.nfs.port)
        assertTrue(decoded.configured)
    }

    @Test
    fun `signed-out accounts are not configured`() {
        assertFalse(BackupLocation(id = "1", type = BackupDestination.MEGA, mega = MegaConfig(email = "me@x")).configured)
        assertFalse(BackupLocation(id = "1", type = BackupDestination.PROTON, proton = ProtonConfig(user = "me")).configured)
        assertFalse(BackupLocation(id = "1", type = BackupDestination.ICLOUD, icloud = ICloudConfig("me", "pw")).configured)
        assertFalse(BackupLocation(id = "1", type = BackupDestination.BOX).configured)
        assertTrue(BackupDestination.PROTON.signsIn)
        assertFalse(BackupDestination.NFS.signsIn)
        assertFalse(BackupDestination.SYNOLOGY.signsIn)
    }

    @Test
    fun `a NAS address gets https and the vendor port`() {
        assertEquals("https://nas.local:5001/", NasSink.baseUrl("nas.local", 5001).toString())
        assertEquals("https://nas.local:8443/", NasSink.baseUrl("nas.local:8443", 5001).toString())
        assertEquals("https://192.168.1.2/", NasSink.baseUrl("https://192.168.1.2/", 443).toString())
        assertEquals("http://100.64.1.2:5000/", NasSink.baseUrl("http://100.64.1.2:5000", 5001).toString())
        assertNull(NasSink.baseUrl("  ", 443))
    }

    @Test
    fun `QNAP login fields come out of its XML`() {
        val xml = "<QDocRoot><authPassed><![CDATA[1]]></authPassed><authSid><![CDATA[abc123]]></authSid>" +
            "<need_2sv>0</need_2sv><qtoken><![CDATA[tok]]></qtoken></QDocRoot>"
        assertEquals("1", QnapSink.tag(xml, "authPassed"))
        assertEquals("abc123", QnapSink.tag(xml, "authSid"))
        assertEquals("0", QnapSink.tag(xml, "need_2sv"))
        assertEquals("tok", QnapSink.tag(xml, "qtoken"))
        assertNull(QnapSink.tag(xml, "missing"))
    }

    @Test
    fun `each service's failures mean the right thing`() {
        assertEquals(SinkError.OUT_OF_SPACE, BoxSink.statusError(403, """{"code":"storage_limit_exceeded"}"""))
        assertEquals(SinkError.PERMISSION_LOST, BoxSink.statusError(401))
        assertEquals(SinkError.TARGET_MISSING, BoxSink.statusError(404))

        assertEquals(SinkError.PERMISSION_LOST, PCloudSink.resultError(2094))
        assertEquals(SinkError.OUT_OF_SPACE, PCloudSink.resultError(2008))
        assertEquals(SinkError.TARGET_MISSING, PCloudSink.resultError(2005))

        assertEquals(SinkError.PERMISSION_LOST, SynologySink.authError(400))
        assertEquals(SinkError.OUT_OF_SPACE, SynologySink.fileError(416))
        assertEquals(SinkError.TARGET_MISSING, SynologySink.fileError(408))

        assertEquals(SinkError.OUT_OF_SPACE, QnapSink.statusError(9))
        assertEquals(SinkError.TARGET_MISSING, QnapSink.statusError(5))
        assertEquals(SinkError.PERMISSION_LOST, QnapSink.statusError(4))

        assertEquals(SinkError.PERMISSION_LOST, MegaSink.errorOf(-15))
        assertEquals(SinkError.OUT_OF_SPACE, MegaSink.errorOf(-17))
        assertEquals(SinkError.TARGET_MISSING, MegaSink.errorOf(-9))

        assertEquals(SinkError.PERMISSION_LOST, NfsSink.errorOf(RpcDeniedException(true, "auth")))
        assertEquals(SinkError.OUT_OF_SPACE, NfsSink.errorOf(NfsStatusException(28, "write")))
        assertEquals(SinkError.TARGET_MISSING, NfsSink.errorOf(NfsStatusException(2, "lookup")))
        assertEquals(SinkError.PERMISSION_LOST, NfsSink.errorOf(NfsStatusException(30, "create")))
    }

    @Test
    fun `pCloud and Box times parse with their offsets`() {
        assertEquals(1363890697000L, PCloudSink.parseRfc1123("Thu, 21 Mar 2013 18:31:37 +0000"))
        assertEquals(1355338423000L, BoxSink.parseOffsetTime("2012-12-12T10:53:43-08:00"))
    }
}
