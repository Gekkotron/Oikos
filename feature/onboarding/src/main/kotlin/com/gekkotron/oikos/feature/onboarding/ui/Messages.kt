package com.gekkotron.oikos.feature.onboarding.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.gekkotron.oikos.data.PayloadError
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.feature.onboarding.R
import com.gekkotron.oikos.feature.onboarding.TestFailure

@Composable
internal fun payloadErrorText(error: PayloadError): String = when (error) {
    PayloadError.NotOikosCode -> stringResource(R.string.scan_error_not_oikos)
    is PayloadError.UnsupportedVersion -> stringResource(R.string.scan_error_version, error.version)
    is PayloadError.InvalidField -> stringResource(R.string.scan_error_field, error.field)
}

/** Shown when saving the profile failed; announced like every other error. */
@Composable
internal fun SaveFailedText() {
    Text(
        stringResource(R.string.save_failed),
        style = OikosTheme.type.body,
        color = OikosTheme.colors.warning,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
internal fun testFailureText(failure: TestFailure): String = when (failure) {
    TestFailure.Unreachable -> stringResource(R.string.connect_error_unreachable)
    TestFailure.Rejected -> stringResource(R.string.connect_error_rejected)
    TestFailure.Tls -> stringResource(R.string.connect_error_tls)
    TestFailure.KeyRejected -> stringResource(R.string.connect_error_key_rejected)
    TestFailure.ClockSkew -> stringResource(R.string.connect_error_clock)
    is TestFailure.Other -> stringResource(R.string.connect_error_other, failure.message)
}
