package com.spekterm.android.pairing

import com.spekterm.android.pairing.PairingLink.Refusal
import com.spekterm.android.pairing.PairingLink.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingLinkTest {
    private val fp = "AB".repeat(32)
    private val code = "ABCDEFGHIJKLMNOPQRSTUVWXY2"

    /** Built the way the desktop builds it: `URLSearchParams` in spekterm's `remote-service.ts` `pairingLink`. */
    private val desktopLink = "spekterm://pair?v=1&a=192.168.50.22%2C100.106.81.33%2Cfd7a%3A115c%3Aa1e0%3A%3A6432%3A5121" +
        "&p=47615&fp=$fp&c=$code&n=kewang+desk"

    private fun ok(text: String): PairingLink = (PairingLink.parse(text) as Result.Ok).link
    private fun refusal(text: String): Refusal = (PairingLink.parse(text) as Result.Refused).reason

    private fun link(v: String = "1", a: String = "192.168.50.22", p: String = "47615", f: String = fp, c: String = code) =
        "spekterm://pair?v=$v&a=$a&p=$p&fp=$f&c=$c&n=desk"

    @Test
    fun readsTheDesktopsLink() {
        val link = ok(desktopLink)
        assertEquals(listOf("192.168.50.22", "100.106.81.33", "fd7a:115c:a1e0::6432:5121"), link.addresses)
        assertEquals(47615, link.port)
        assertEquals(fp, link.fingerprint)
        assertEquals(code, link.code)
        assertEquals("kewang desk", link.name)
    }

    @Test
    fun splitsAnEncodedAddressListInOrder() {
        val link = ok(link(a = "192.168.50.22%2C100.106.81.33%2Cfd7a%3A115c%3Aa1e0%3A%3A1"))
        assertEquals(listOf("192.168.50.22", "100.106.81.33", "fd7a:115c:a1e0::1"), link.addresses)
    }

    @Test
    fun acceptsHostNamesAndBracketedIpv6() {
        assertEquals(listOf("desk.tailnet.ts.net", "fd7a::1"), ok(link(a = "desk.tailnet.ts.net,%5Bfd7a::1%5D")).addresses)
    }

    @Test
    fun normalizesTheFingerprintAndCode() {
        val colons = fp.chunked(2).joinToString(":").lowercase()
        val link = ok(link(f = colons, c = code.lowercase()))
        assertEquals(fp, link.fingerprint)
        assertEquals(code, link.code)
    }

    @Test
    fun refusesAnotherVersion() {
        assertEquals(Refusal.UNSUPPORTED_VERSION, refusal(link(v = "2")))
    }

    @Test
    fun refusesAMalformedFingerprint() {
        assertEquals(Refusal.BAD_FINGERPRINT, refusal(link(f = fp.drop(1))))
        assertEquals(Refusal.BAD_FINGERPRINT, refusal(link(f = "G" + fp.drop(1))))
    }

    @Test
    fun refusesABadPort() {
        assertEquals(Refusal.BAD_PORT, refusal(link(p = "0")))
        assertEquals(Refusal.BAD_PORT, refusal(link(p = "65536")))
        assertEquals(Refusal.BAD_PORT, refusal(link(p = "x")))
    }

    @Test
    fun refusesABadCode() {
        assertEquals(Refusal.BAD_CODE, refusal(link(c = code.drop(1))))
        assertEquals(Refusal.BAD_CODE, refusal(link(c = code.dropLast(1) + "1")))
    }

    @Test
    fun refusesALinkWithNoAddress() {
        assertEquals(Refusal.NO_ADDRESS, refusal(link(a = "")))
        assertEquals(Refusal.NO_ADDRESS, refusal(link(a = "%2C")))
    }

    @Test
    fun refusesAnAddressThatCannotBeConnectedTo() {
        // One bad address anywhere in the list: it would crash or fail every later reconnect through it.
        assertEquals(Refusal.BAD_ADDRESS, refusal(link(a = "192.168.1.5,foo%20bar")))
        assertEquals(Refusal.BAD_ADDRESS, refusal(link(a = "fe80::1%25wlan0")))
    }

    @Test
    fun refusesOtherLinks() {
        assertEquals(Refusal.NOT_A_PAIRING_LINK, refusal("https://example.com/?v=1"))
        assertEquals(Refusal.NOT_A_PAIRING_LINK, refusal("spekterm://open?v=1"))
        assertEquals(Refusal.NOT_A_PAIRING_LINK, refusal("not a link at all"))
        assertEquals(Refusal.NOT_A_PAIRING_LINK, refusal("spekterm://pair"))
    }

    @Test
    fun toleratesSurroundingWhitespaceFromAPaste() {
        assertEquals(47615, ok("  $desktopLink\n").port)
    }

    @Test
    fun neverPrintsTheCode() {
        assert(!ok(desktopLink).toString().contains(code))
    }

    @Test
    fun recognizesPairingLinksForTheScanner() {
        assertTrue(PairingLink.isPairingLink(desktopLink))
        assertTrue(PairingLink.isPairingLink("SPEKTERM://PAIR?v=2"))
        assertTrue(PairingLink.isPairingLink("  $desktopLink\n"))
        assertFalse(PairingLink.isPairingLink("https://example.org"))
        assertFalse(PairingLink.isPairingLink("spekterm://other?v=1"))
        assertFalse(PairingLink.isPairingLink("hello there"))
    }
}
