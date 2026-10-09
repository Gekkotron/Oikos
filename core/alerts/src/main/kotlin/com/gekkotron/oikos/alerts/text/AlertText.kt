package com.gekkotron.oikos.alerts.text

import android.content.res.Resources
import com.gekkotron.oikos.alerts.Alert
import com.gekkotron.oikos.alerts.AlertLoc
import com.gekkotron.oikos.alerts.R
import java.util.Locale

/**
 * The body an alert shows (localized alerts spec §5.2): the sentence its [AlertLoc] names, in [Resources]'
 * language, or the server's English [Alert.body] whenever the hint is missing, unknown or incomplete.
 */
object AlertText {
    /** Raw Frigate label → (noun, "detected" sentence). Exact, lower-case match only. */
    internal val FRIGATE_LABELS: Map<String, Pair<Int, Int>> = mapOf(
        "person" to (R.string.alert_frigate_person to R.string.alert_frigate_person_detected),
        "car" to (R.string.alert_frigate_car to R.string.alert_frigate_car_detected),
        "truck" to (R.string.alert_frigate_truck to R.string.alert_frigate_truck_detected),
        "bus" to (R.string.alert_frigate_bus to R.string.alert_frigate_bus_detected),
        "motorcycle" to (R.string.alert_frigate_motorcycle to R.string.alert_frigate_motorcycle_detected),
        "bicycle" to (R.string.alert_frigate_bicycle to R.string.alert_frigate_bicycle_detected),
        "dog" to (R.string.alert_frigate_dog to R.string.alert_frigate_dog_detected),
        "cat" to (R.string.alert_frigate_cat to R.string.alert_frigate_cat_detected),
        "bird" to (R.string.alert_frigate_bird to R.string.alert_frigate_bird_detected),
        "horse" to (R.string.alert_frigate_horse to R.string.alert_frigate_horse_detected),
        "package" to (R.string.alert_frigate_package to R.string.alert_frigate_package_detected),
        "face" to (R.string.alert_frigate_face to R.string.alert_frigate_face_detected),
        "license_plate" to (R.string.alert_frigate_license_plate to R.string.alert_frigate_license_plate_detected),
        "amazon" to (R.string.alert_frigate_amazon to R.string.alert_frigate_amazon_detected),
        "dhl" to (R.string.alert_frigate_dhl to R.string.alert_frigate_dhl_detected),
        "dpd" to (R.string.alert_frigate_dpd to R.string.alert_frigate_dpd_detected),
        "fedex" to (R.string.alert_frigate_fedex to R.string.alert_frigate_fedex_detected),
        "gls" to (R.string.alert_frigate_gls to R.string.alert_frigate_gls_detected),
        "ups" to (R.string.alert_frigate_ups to R.string.alert_frigate_ups_detected),
        "usps" to (R.string.alert_frigate_usps to R.string.alert_frigate_usps_detected),
    )

    fun body(alert: Alert, resources: Resources): String =
        alert.loc?.let { render(it, resources) } ?: alert.body

    private fun render(loc: AlertLoc, resources: Resources): String? = when (loc.key) {
        "frigate.object" -> frigateObject(loc.args, resources)
        else -> null
    }

    private fun frigateObject(args: Map<String, String>, resources: Resources): String? {
        val (noun, detected) = FRIGATE_LABELS[args["label"]] ?: return null
        val zone = args["zone"]?.takeIf { it.isNotBlank() }
        val who = args["who"]?.takeIf { it.isNotBlank() }
        val subject = when {
            who != null -> resources.getString(R.string.alert_frigate_who, who, resources.getString(noun).lowercase(locale(resources)))
            zone != null -> resources.getString(noun)
            else -> return resources.getString(detected)
        }
        return if (zone != null) resources.getString(R.string.alert_frigate_in, subject, zone) else subject
    }

    private fun locale(resources: Resources): Locale = resources.configuration.locales[0] ?: Locale.getDefault()
}
