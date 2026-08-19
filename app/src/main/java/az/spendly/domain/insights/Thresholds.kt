/**
 * Every number the advice engine judges by, in one place.
 *
 * Each is marked either **framework** — it comes from a published methodology
 * and is cited — or **app rule** — it is a product decision about when
 * something is worth a line on screen. Mixing the two silently is how an
 * arbitrary cutoff ends up looking like established guidance.
 */
package az.spendly.domain.insights

enum class ThresholdBasis { FRAMEWORK, APP_RULE }

data class Threshold(val value: Double, val basis: ThresholdBasis, val why: String)

private fun framework(value: Double, why: String) =
    Threshold(value, ThresholdBasis.FRAMEWORK, why)

private fun appRule(value: Double, why: String) =
    Threshold(value, ThresholdBasis.APP_RULE, why)

object Thresholds {
    /** Below this share, a variance is noise rather than a pattern. Matches
     *  the MATERIAL_CHANGE the dashboard's insights already use. */
    val materialRatio = appRule(0.1, "Mövcud panelin 10% həddi ilə eyni")

    /** Below this, the manat amount is too small to be worth a sentence. */
    val materialAmount = appRule(5.0, "Kiçik məbləğlər siyahını doldurmasın")

    /**
     * Robust outlier cutoff. Leys et al. (2013) recommend 2.5 as a reasonable
     * default, applied to the median absolute deviation rather than the
     * standard deviation — the mean and SD are themselves dragged by the
     * outlier being looked for.
     */
    val anomalyScore = framework(2.5, "Leys et al. (2013), J. Exp. Soc. Psych.")

    /** Median and MAD need a run of months behind them to mean anything. */
    val anomalyMinMonths = appRule(4.0, "İki nöqtə üzərində median mənasızdır")

    /** A category counts as repeatedly over plan at this many of the last four. */
    val repeatedOverruns = appRule(3.0, "Son 4 ayın 3-ü — təsadüf deyil, vərdiş")
    val repeatedWindow = appRule(4.0, "Baxılan ay sayı")

    /** Change against the previous three-month average worth reporting. */
    val trendRatio = appRule(0.15, "Aylıq dalğalanmadan yuxarı")
    val trendMinMonths = appRule(4.0, "3 aylıq baza + cari ay")

    /** A single category taking at least this share of spending is worth naming. */
    val concentrationShare = appRule(0.35, "Xərcin üçdə birindən çoxu bir yerdə")

    /** Unplanned spending worth surfacing, as a share of the month's expenses. */
    val unexpectedShare = appRule(0.2, "Xərcin beşdə birindən çoxu plandan kənar")

    /** Standing commitments taking at least this share of income. */
    val recurringShare = appRule(0.5, "Gəlirin yarısı öhdəliklərə bağlıdır")

    /** Lifestyle inflation needs two comparable three-month blocks. */
    val lifestyleMinMonths = appRule(6.0, "Müqayisə üçün 3 + 3 ay")
    val lifestyleGap = appRule(0.1, "Xərc artımı gəlir artımını bu qədər ötəndə")
}
