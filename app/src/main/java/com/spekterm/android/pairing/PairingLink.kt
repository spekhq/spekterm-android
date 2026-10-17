package com.spekterm.android.pairing

import java.net.URI
import okhttp3.HttpUrl
import java.net.URLDecoder

/**
 * The fields of a desktop's pairing link (`docs/remote-protocol.md` section 1):
 * `spekterm://pair?v=1&a=<addresses>&p=<port>&fp=<fingerprint>&c=<code>&n=<name>`.
 *
 * A link is either read whole or refused; a refused link never opens a connection (`device-pairing`).
 */
data class PairingLink(
    /** In the link's order. IPv6 literals without brackets, host names as given. */
    val addresses: List<String>,
    val port: Int,
    /** 64 upper-case hex digits: SHA-256 of the desktop certificate's DER. */
    val fingerprint: String,
    /** 26 upper-case base32 characters. */
    val code: String,
    val name: String,
) {
    override fun toString(): String =
        "PairingLink(addresses=$addresses, port=$port, fingerprint=$fingerprint, code=<redacted>, name=$name)"

    sealed interface Result {
        data class Ok(val link: PairingLink) : Result
        data class Refused(val reason: Refusal) : Result
    }

    enum class Refusal {
        /** Not a `spekterm://pair` link, or not parseable at all. */
        NOT_A_PAIRING_LINK,
        /** `v` is not 1: the link comes from a newer desktop. */
        UNSUPPORTED_VERSION,
        NO_ADDRESS,
        /** An address that cannot be connected to as given (a space, a scoped IPv6 literal, …). */
        BAD_ADDRESS,
        BAD_PORT,
        BAD_FINGERPRINT,
        BAD_CODE,
    }

    companion object {
        private val HEX64 = Regex("^[0-9A-F]{64}$")
        private val BASE32_26 = Regex("^[A-Z2-7]{26}$")

        /** `AB:CD:…` or `abcd…` → `ABCD…`, so the dialog's form and the link's form compare equal. */
        fun normalizeFingerprint(value: String): String = value.replace(":", "").trim().uppercase()

        /**
         * Whether [text] is meant as a pairing link — `spekterm://pair`, as [parse] reads the scheme and host — so a
         * scanner can stop on it even when [parse] then refuses it (`scan-connect-zh` D3).
         */
        fun isPairingLink(text: String): Boolean {
            val uri = try {
                URI(text.trim())
            } catch (_: Exception) {
                return false
            }
            return uri.scheme?.equals("spekterm", ignoreCase = true) == true && uri.rawAuthority?.lowercase() == "pair"
        }

        fun parse(text: String): Result {
            val uri = try {
                URI(text.trim())
            } catch (_: Exception) {
                return Result.Refused(Refusal.NOT_A_PAIRING_LINK)
            }
            if (uri.scheme?.equals("spekterm", ignoreCase = true) != true || uri.rawAuthority?.lowercase() != "pair") {
                return Result.Refused(Refusal.NOT_A_PAIRING_LINK)
            }
            val params = query(uri.rawQuery ?: return Result.Refused(Refusal.NOT_A_PAIRING_LINK))

            if (params["v"] != "1") return Result.Refused(Refusal.UNSUPPORTED_VERSION)

            val given = (params["a"] ?: "").split(',').filter { it.isNotBlank() }
            if (given.isEmpty()) return Result.Refused(Refusal.NO_ADDRESS)
            val addresses = given.map { normalize(it) ?: return Result.Refused(Refusal.BAD_ADDRESS) }

            val port = params["p"]?.toIntOrNull()
            if (port == null || port !in 1..65535) return Result.Refused(Refusal.BAD_PORT)

            val fingerprint = normalizeFingerprint(params["fp"] ?: "")
            if (!HEX64.matches(fingerprint)) return Result.Refused(Refusal.BAD_FINGERPRINT)

            val code = (params["c"] ?: "").trim().uppercase()
            if (!BASE32_26.matches(code)) return Result.Refused(Refusal.BAD_CODE)

            return Result.Ok(PairingLink(addresses, port, fingerprint, code, params["n"] ?: ""))
        }

        /**
         * An address as the app keeps it — trimmed, an IPv6 literal without its brackets — or `null` when it is blank
         * or cannot be connected to as given. Shared by the link and the desktop's address list, so one address
         * written both ways is one address.
         */
        fun normalize(address: String): String? =
            address.trim().removePrefix("[").removeSuffix("]").takeIf { it.isNotEmpty() && usable(it) }

        /** Whether OkHttp can build a URL with [address] as its host; one it cannot would fail every connection. */
        private fun usable(address: String): Boolean = try {
            HttpUrl.Builder().scheme("https").host(address).build()
            true
        } catch (_: IllegalArgumentException) {
            false
        }

        /** Decodes `k=v&…`; a comma arrives as `%2C` until decoded, so splitting `a` happens after this. */
        private fun query(raw: String): Map<String, String> = raw.split('&').filter { it.isNotEmpty() }.associate { pair ->
            val eq = pair.indexOf('=')
            val key = if (eq < 0) pair else pair.substring(0, eq)
            val value = if (eq < 0) "" else pair.substring(eq + 1)
            decode(key) to decode(value)
        }

        /** Form decoding, matching the desktop's `URLSearchParams` (a space arrives as `+`). */
        private fun decode(s: String): String = URLDecoder.decode(s, "UTF-8")
    }
}
