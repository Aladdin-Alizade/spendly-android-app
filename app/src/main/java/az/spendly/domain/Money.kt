/**
 * Money handling. All amounts are AZN and are rounded to 2 decimals at every
 * boundary so that repeated addition can never drift (0.1 + 0.2 problems).
 */
package az.spendly.domain

import java.util.Locale
import kotlin.math.abs
import kotlin.math.round

/** Round to 2 decimals, half-away-from-zero, avoiding float representation error. */
fun round2(value: Double): Double {
    if (value.isNaN() || value.isInfinite()) return 0.0
    val scaled = round(abs(value) * 100.0 + 1e-9) / 100.0
    return if (value < 0) -scaled else scaled
}

/** Sum a list of amounts without accumulating float error. */
fun sumOf(values: List<Double>): Double = round2(values.sum())

/** `1,250.00 ₼` — mirrors the sheet's `#,##0.00 [$₼-42C]` cell format. */
fun formatAZN(value: Double): String {
    val rounded = round2(value)
    // Guard against "-0.00".
    val safe = if (rounded == 0.0) 0.0 else rounded
    return String.format(Locale.US, "%,.2f ₼", safe)
}

/** Same as [formatAZN] but with an explicit leading `+` for positive values. */
fun formatSignedAZN(value: Double): String {
    val rounded = round2(value)
    return if (rounded > 0) "+${formatAZN(rounded)}" else formatAZN(rounded)
}

/**
 * Parse user input into an amount.
 * Accepts `1234.56`, `1 234,56`, `1,234.56`. Returns null when unparseable.
 */
fun parseAmount(input: String): Double? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null

    // Strip spaces and the currency mark, then normalise the decimal separator.
    var normalised = trimmed.replace(Regex("[\\s ₼]"), "")
    val lastComma = normalised.lastIndexOf(',')
    val lastDot = normalised.lastIndexOf('.')
    normalised = if (lastComma > -1 && lastComma > lastDot) {
        normalised.replace(".", "").replace(",", ".")
    } else {
        normalised.replace(",", "")
    }

    if (!Regex("^-?\\d*\\.?\\d*$").matches(normalised) ||
        normalised.isEmpty() ||
        normalised == "." ||
        normalised == "-"
    ) {
        return null
    }

    val value = normalised.toDoubleOrNull() ?: return null
    return if (value.isFinite()) round2(value) else null
}
