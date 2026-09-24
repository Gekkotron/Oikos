package com.gekkotron.oikos.feature.onboarding.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.gekkotron.oikos.data.PayloadError
import com.gekkotron.oikos.feature.onboarding.R
import com.gekkotron.oikos.feature.onboarding.TestFailure

@Composable
internal fun payloadErrorText(error: PayloadError): String = when (error) {
    PayloadError.NotOikosCode -> stringResource(R.string.scan_error_not_oikos)
    is PayloadError.UnsupportedVersion -> stringResource(R.string.scan_error_version, error.version)
    is PayloadError.InvalidField -> stringResource(R.string.scan_error_field, error.field)
}

@Composable
internal fun testFailureText(failure: TestFailure): String = when (failure) {
    TestFailure.Unreachable -> stringResource(R.string.connect_error_unreachable)
    TestFailure.Rejected -> stringResource(R.string.connect_error_rejected)
    TestFailure.Tls -> stringResource(R.string.connect_error_tls)
    is TestFailure.Other -> stringResource(R.string.connect_error_other, failure.message)
}
