package com.spekterm.android.ui

import com.spekterm.android.R
import com.spekterm.android.service.AsideReason
import com.spekterm.android.service.RelayGate
import com.spekterm.android.service.Unreachability.Outcome
import com.spekterm.android.service.Unreachability.RelayResult
import com.spekterm.android.service.Unreachability.RelayRow
import com.spekterm.android.service.Unreachability.Reason
import com.spekterm.android.service.Unreachability.Report

/**
 * The sentences of `scan-connect-zh` D6's table: what is wrong, and what the user can do about it. Shared by the
 * status screen and the pairing screen.
 */
object Reasons {
    fun said(report: Report, port: Int): UiText = when (report.reason) {
        Reason.NO_NETWORK -> UiText.Res(R.string.reason_no_network)
        Reason.NOT_THE_DESKTOP ->
            if (report.throughRelay) UiText.Res(R.string.reason_not_the_desktop_relay) else UiText.Res(R.string.reason_not_the_desktop, report.address.orEmpty())
        Reason.DESKTOP_OFFLINE -> UiText.Res(R.string.reason_desktop_offline, report.address.orEmpty())
        Reason.REFUSED -> UiText.Res(R.string.reason_refused, report.address.orEmpty(), port)
        Reason.OFF_NETWORK -> UiText.Res(R.string.reason_off_network)
        Reason.NO_ANSWER -> UiText.Res(R.string.reason_no_answer)
        Reason.UNREACHABLE -> UiText.Res(R.string.reason_unreachable)
    }

    /** The advice after [said]; [port] fills the ones that name it. Empty for the generic reason. */
    fun advice(report: Report, port: Int): List<UiText> = when (report.reason) {
        Reason.NO_NETWORK -> listOf(UiText.Res(R.string.reason_no_network_advice))
        Reason.NOT_THE_DESKTOP -> listOf(UiText.Res(R.string.reason_not_the_desktop_advice))
        Reason.REFUSED -> listOf(UiText.Res(R.string.reason_refused_advice))
        Reason.DESKTOP_OFFLINE -> listOf(UiText.Res(R.string.reason_desktop_offline_advice))
        Reason.OFF_NETWORK -> listOf(UiText.Res(R.string.reason_off_network_advice))
        Reason.NO_ANSWER -> listOfNotNull(
            UiText.Res(R.string.reason_no_answer_advice, port),
            UiText.Res(R.string.reason_tailscale_hint).takeIf { report.tailscaleHint },
        )
        Reason.UNREACHABLE -> emptyList()
    }

    /** The ongoing notification's short form while waiting to retry. */
    fun short(report: Report): UiText = when (report.reason) {
        Reason.NO_NETWORK -> UiText.Res(R.string.notification_ongoing_no_network)
        Reason.REFUSED -> UiText.Res(R.string.notification_ongoing_refused)
        Reason.NOT_THE_DESKTOP -> UiText.Res(R.string.notification_ongoing_not_the_desktop)
        else -> UiText.Res(R.string.notification_ongoing_unreachable)
    }

    /** One address's outcome; `null` is an attempt still under way. */
    fun outcome(outcome: Outcome?): UiText = UiText.Res(
        when (outcome) {
            null -> R.string.outcome_trying
            Outcome.TIMED_OUT -> R.string.outcome_timed_out
            Outcome.REFUSED -> R.string.outcome_refused
            Outcome.NO_ROUTE -> R.string.outcome_no_route
            Outcome.NAME_NOT_FOUND -> R.string.outcome_name_not_found
            Outcome.CERTIFICATE -> R.string.outcome_certificate
            Outcome.FAILED -> R.string.outcome_failed
            Outcome.NOT_TRIED -> R.string.outcome_not_tried
        },
    )

    /** The relay's row after the addresses: what it came to, or why it was left out (`phone-relay` D6). */
    fun relay(row: RelayRow, time: (Long) -> String): UiText = when (val out = row.leftOut) {
        is RelayGate.LeftOut.Aside -> UiText.Res(
            when (out.why) {
                AsideReason.RELAY_OFF -> R.string.relay_left_out_off
                AsideReason.UNSUPPORTED -> R.string.relay_left_out_unsupported
                AsideReason.FAULT -> R.string.relay_left_out_fault
            },
        )
        is RelayGate.LeftOut.Until -> UiText.Res(R.string.relay_left_out_until, time(out.at))
        RelayGate.LeftOut.ThisNetwork -> UiText.Res(R.string.relay_left_out_network)
        null -> when {
            !row.started -> UiText.Res(R.string.outcome_not_tried)
            else -> relayResult(row.result)
        }
    }

    private fun relayResult(result: RelayResult?): UiText = UiText.Res(
        when (result) {
            null -> R.string.outcome_trying
            RelayResult.TIMED_OUT -> R.string.outcome_timed_out
            RelayResult.REFUSED -> R.string.outcome_refused
            RelayResult.NO_ROUTE -> R.string.outcome_no_route
            RelayResult.NAME_NOT_FOUND -> R.string.outcome_name_not_found
            RelayResult.FAILED -> R.string.outcome_failed
            RelayResult.DESKTOP_CERTIFICATE -> R.string.relay_outcome_desktop_certificate
            RelayResult.DESKTOP_OFFLINE -> R.string.relay_outcome_desktop_offline
            RelayResult.DESKTOP_REFUSED -> R.string.relay_outcome_desktop_refused
            RelayResult.BUSY -> R.string.relay_outcome_busy
            RelayResult.RATE_LIMITED -> R.string.relay_outcome_rate_limited
            RelayResult.RELAY_OFF -> R.string.relay_outcome_relay_off
            RelayResult.GRANT_INVALID -> R.string.relay_outcome_grant_invalid
            RelayResult.CERTIFICATE -> R.string.relay_outcome_certificate
            RelayResult.CERTIFICATE_CHANGED -> R.string.relay_outcome_certificate_changed
        },
    )
}
