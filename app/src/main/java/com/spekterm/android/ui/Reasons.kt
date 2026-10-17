package com.spekterm.android.ui

import com.spekterm.android.R
import com.spekterm.android.service.Unreachability.Outcome
import com.spekterm.android.service.Unreachability.Reason
import com.spekterm.android.service.Unreachability.Report

/**
 * The sentences of `scan-connect-zh` D6's table: what is wrong, and what the user can do about it. Shared by the
 * status screen and the pairing screen.
 */
object Reasons {
    fun said(report: Report, port: Int): UiText = when (report.reason) {
        Reason.NO_NETWORK -> UiText.Res(R.string.reason_no_network)
        Reason.NOT_THE_DESKTOP -> UiText.Res(R.string.reason_not_the_desktop, report.address.orEmpty())
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
}
