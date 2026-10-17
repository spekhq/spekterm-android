package com.spekterm.android.ui

import com.spekterm.android.R
import com.spekterm.android.pairing.PairingLink
import com.spekterm.android.store.Pairing
import org.junit.Assert.assertEquals
import org.junit.Test

/** `device-pairing` "Open a pairing link from another app" (`scan-connect-zh` D4). */
class PairOfferTest {
    private val fp = "AB".repeat(32)
    private fun link(fingerprint: String, name: String = "kewang-7420") = (PairingLink.parse(
        "spekterm://pair?v=1&a=192.0.2.10&p=47616&fp=$fingerprint&c=ABCDEFGHIJKLMNOPQRSTUVWXY2&n=$name",
    ) as PairingLink.Result.Ok).link
    private val current = Pairing("kewang-7420", listOf("192.168.0.199"), 47616, fp.lowercase(), "d", "s")

    @Test
    fun kindsByFingerprintNotName() {
        assertEquals(PairOffer.Kind.NEW, PairOffer.kind(link(fp), null))
        assertEquals(PairOffer.Kind.SAME_DESKTOP, PairOffer.kind(link(fp), current))
        assertEquals(PairOffer.Kind.OTHER_DESKTOP, PairOffer.kind(link("CD".repeat(32)), current))
    }

    @Test
    fun anotherDesktopIsNotTitledByTheNameItGives() {
        val other = link("CD".repeat(32))
        assertEquals(UiText.Res(R.string.pair_offer_title_other), PairOffer.title(other, current))
        assertEquals(UiText.Res(R.string.pair_offer_different, "kewang-7420", "kewang-7420"), PairOffer.lines(other, current).first())
        assertEquals(UiText.Res(R.string.pair_offer_title_same, "kewang-7420"), PairOffer.title(link(fp), current))
        assertEquals(UiText.Res(R.string.pair_offer_title, "kewang-7420"), PairOffer.title(link(fp), null))
    }
}
