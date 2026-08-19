/**
 * Məsləhətlər — what the month's figures say, measured against published
 * budgeting practice.
 *
 * Everything here is produced by [budgetAdvice], which is a set of rules that
 * either fire or do not. Nothing on this screen is generated text: the same
 * figures always produce the same page, which is what makes it something to
 * rely on rather than something to read.
 */
package az.spendly.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import az.spendly.domain.FinanceData
import az.spendly.domain.CategoryKind
import az.spendly.domain.MonthKey
import az.spendly.domain.formatAZN
import az.spendly.domain.formatMonth
import az.spendly.domain.formatSignedAZN
import az.spendly.domain.insights.Advice
import az.spendly.domain.insights.AdvicePriority
import az.spendly.domain.insights.METHODS
import az.spendly.domain.insights.ORIGIN_LABEL
import az.spendly.domain.insights.REVIEW_INTERVAL_MONTHS
import az.spendly.domain.insights.CLASSIFICATION_COVERAGE_MIN
import az.spendly.domain.insights.KIND_LABEL
import az.spendly.domain.insights.Reference503020
import az.spendly.domain.insights.FrameworkSplit
import az.spendly.domain.insights.SpendingSplit
import az.spendly.domain.insights.budgetAdvice
import az.spendly.domain.insights.classifySpending
import az.spendly.domain.insights.emergencyFund
import az.spendly.domain.insights.fiftyThirtyTwenty
import az.spendly.domain.insights.frameworkGaps
import az.spendly.domain.insights.fundPace
import az.spendly.domain.insights.spendingRigidity
import az.spendly.domain.insights.methodsNeedingReview
import az.spendly.domain.insights.needsReview
import az.spendly.domain.today
import az.spendly.ui.components.Micro
import az.spendly.ui.components.Panel
import az.spendly.ui.components.Pill
import az.spendly.ui.components.Swatch
import az.spendly.ui.theme.Radius
import az.spendly.ui.theme.spendlyColors
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun AdviceScreen(data: FinanceData, month: MonthKey, modifier: Modifier = Modifier) {
    val colors = spendlyColors
    val asOf = today()
    val report = remember(data, month, asOf) { budgetAdvice(data, month, asOf) }
    val stale = remember(asOf) { methodsNeedingReview(asOf) }
    val split = remember(data, month) { classifySpending(data, month) }
    val framework = remember(data, month) { fiftyThirtyTwenty(data, month) }
    var fundMonths by remember { mutableStateOf(3) }
    val fund = remember(data, month, fundMonths) { emergencyFund(data, month, fundMonths) }
    val rigidity = remember(split) { spendingRigidity(split) }
    val pace = remember(data, month, fund) {
        fund?.let { fundPace(data, month, it.target) }
    }
    val health = report.health

    val nothing = report.attention.isEmpty() && report.good.isEmpty() && report.review.isEmpty()

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = "${formatMonth(month)} · yalnız rəqəmlərin təsdiqlədiyi müşahidələr",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
            )
        }

        if (stale.isNotEmpty()) {
            item {
                Text(
                    text = "${stale.size} istinad $REVIEW_INTERVAL_MONTHS aydan çoxdur " +
                        "yoxlanılmayıb. Aşağıdakı Metodologiya bölməsində tarixlər göstərilib — " +
                        "mənbələr dəyişmiş ola bilər.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.text,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.sm))
                        .background(colors.negativeSoft)
                        .padding(12.dp),
                )
            }
        }

        /* --- budget health: figures, not a score ---------------------- */
        item {
            Panel(title = "Büdcə vəziyyəti", note = "bal deyil — hesablanmış göstəricilər") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Figure("Gəlir", formatAZN(health.income), Modifier.weight(1f))
                        Figure("Xərc", formatAZN(health.expenses), Modifier.weight(1f))
                        Figure(
                            label = "Qalan",
                            value = formatSignedAZN(health.remaining),
                            modifier = Modifier.weight(1f),
                            tone = when {
                                health.remaining < 0 -> colors.negative
                                health.remaining > 0 -> colors.positive
                                else -> null
                            },
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Figure(
                            label = "Qalan pulun payı",
                            value = health.retainedRate
                                ?.let { "${(it * 100).roundToInt()}%" } ?: "—",
                            modifier = Modifier.weight(1f),
                            hint = if (health.retainedRate == null) "gəlir qeyd edilməyib" else null,
                        )
                        Figure(
                            label = "Plandan fərq",
                            value = health.planVariance
                                ?.let { formatSignedAZN(it) } ?: "—",
                            modifier = Modifier.weight(1f),
                            tone = health.planVariance?.let {
                                if (it > 0) colors.negative else colors.positive
                            },
                            hint = if (health.planVariance == null) "plan qurulmayıb" else null,
                        )
                    }
                }

                health.spendingRatio?.let { ratio ->
                    Column(modifier = Modifier.padding(top = 12.dp)) {
                        Track(
                            value = min(ratio, 1.0),
                            color = if (ratio > 1) colors.negative else colors.series[0],
                        )
                        Text(
                            text = "Gəlirin ${(ratio * 100).roundToInt()}%-i xərclənib" +
                                if (ratio > 1) " — gəlirdən çox" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textMuted,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }

        /* --- what the spending is for -------------------------------- */
        item {
            Panel(
                title = "Ehtiyac və istək",
                note = if (split.total > 0) {
                    "${(split.coverage * 100).roundToInt()}% təsnif edilib"
                } else {
                    null
                },
            ) {
                if (split.hasCoverage) {
                    val order = listOf(
                        CategoryKind.ESSENTIAL,
                        CategoryKind.DEBT,
                        CategoryKind.DISCRETIONARY,
                        CategoryKind.SAVING,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(colors.track),
                    ) {
                        order.forEach { kind ->
                            val share = (split.of(kind) / split.total).toFloat()
                            if (share > 0f) {
                                Box(
                                    modifier = Modifier
                                        .weight(share)
                                        .fillMaxHeight()
                                        .background(kindColor(kind)),
                                )
                            }
                        }
                    }

                    Column(
                        modifier = Modifier.padding(top = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        order.forEach { kind ->
                            val amount = split.of(kind)
                            if (amount <= 0) return@forEach
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Swatch(kindColor(kind))
                                Text(
                                    text = KIND_LABEL.getValue(kind),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.textMuted,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = formatAZN(amount),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    color = colors.text,
                                )
                                Text(
                                    text = "${((amount / split.total) * 100).roundToInt()}%",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.textFaint,
                                )
                            }
                        }
                    }

                    rigidity?.let { room ->
                        Reading(
                            "Xərcinizin ${(room.rigidShare * 100).roundToInt()}%-i kirayə, " +
                                "ərzaq, kommunal və borc kimi asanlıqla kəsilməyən " +
                                "şeylərdir. Gəliriniz azalsa, rahat azalda biləcəyiniz " +
                                "hissə ${formatAZN(room.flexible)} — istəyə bağlı olan bu " +
                                "qədərdir.",
                        )
                    }

                    FrameworkNote(
                        "Bu bölgü mühakimə deyil — hansı kateqoriyanın zəruri olduğunu " +
                            "siz təyin edirsiniz.",
                    )
                } else {
                    Missing(split)
                }
            }
        }

        /* --- against a published reference ---------------------------- */
        item {
            Panel(title = "50/30/20 çərçivəsi", note = "istinad — qayda deyil") {
                if (framework != null) {
                    FrameworkRow(
                        label = "Zəruri (ehtiyac + borc)",
                        actual = framework.needsShare,
                        reference = Reference503020.NEEDS,
                        amount = framework.needs,
                    )
                    FrameworkRow(
                        label = "İstəyə bağlı",
                        actual = framework.wantsShare,
                        reference = Reference503020.WANTS,
                        amount = framework.wants,
                    )
                    FrameworkRow(
                        label = "Yığım və qalan",
                        actual = framework.savingsShare,
                        reference = Reference503020.SAVINGS,
                        amount = framework.savings,
                    )
                    FrameworkReading(framework)

                    FrameworkNote(
                        "CFPB bunu bir neçə büdcə qaydasından biri kimi öyrədir — hamıya " +
                            "uyğun gəlmir. Borc ödənişləri «zəruri» tərəfdə sayılır.",
                    )
                } else {
                    Missing(
                        split = split,
                        extra = if (split.total > 0 && split.hasCoverage) {
                            "Bu ay gəlir qeyd edilməyib."
                        } else {
                            null
                        },
                    )
                }
            }
        }

        /* --- a target, and only a target ------------------------------ */
        item {
            Panel(title = "Təcili ehtiyat fondu", note = "yalnız hədəf") {
                if (fund != null) {
                    Micro("Zəruri aylıq xərc (median)")
                    Text(
                        text = formatAZN(fund.essentialMonthly),
                        style = MaterialTheme.typography.headlineSmall,
                        color = colors.text,
                    )
                    Text(
                        text = "${fund.sampleMonths} aylıq məlumat əsasında",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textFaint,
                    )

                    Row(
                        modifier = Modifier.padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        listOf(3, 6, 12).forEach { option ->
                            val selected = fundMonths == option
                            Text(
                                text = "$option ay",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected) colors.onAccent else colors.textMuted,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(Radius.xs))
                                    .background(if (selected) colors.accent else colors.surfaceInset)
                                    .clickable { fundMonths = option }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Micro("Hədəf")
                        Text(
                            text = formatAZN(fund.target),
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.text,
                        )
                    }

                    Reading(
                        buildString {
                            append("Gəliriniz dayansa, bu məbləğ təxminən ")
                            append("${fund.months} ay əsas xərclərinizi qarşılayar.")
                            pace?.monthsAtRetained?.let {
                                append(" Bu ay qalan ${formatAZN(pace.retainedMonthly)} hər ")
                                append("ay qalsa, hədəfə ")
                                append(String.format(Locale.US, "%.1f", it))
                                append(" ayda çatarsınız.")
                            }
                            pace?.monthsAtSaving?.let {
                                append(" Yalnız yığıma qoyduğunuz ")
                                append("${formatAZN(pace.savingMonthly)} ilə isə ")
                                append("${it.roundToInt()} ay çəkər — «qalan» ilə «yığılan» ")
                                append("arasındakı fərq budur.")
                            }
                        },
                    )

                    FrameworkNote(
                        "CFPB vahid rəqəm vermir — məbləğ vəziyyətinizdən asılıdır. Tətbiq " +
                            "hesab qalığınızı görmür, ona görə hədəfə nə qədər yaxın " +
                            "olduğunuzu deyə bilmir.",
                    )
                } else {
                    Missing(
                        split = split,
                        extra = "Hesablama üçün ən azı 3 ayın təsnif edilmiş xərci lazımdır.",
                    )
                }
            }
        }

        if (nothing) {
            item {
                Panel(title = "Müşahidə yoxdur") {
                    Text(
                        text = "Bu ay üçün rəqəmlərin təsdiqlədiyi müşahidə yoxdur. " +
                            "Əməliyyat və plan əlavə etdikcə burada müşahidələr görünəcək.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textMuted,
                    )
                }
            }
        }

        item {
            Bucket(
                title = "Diqqət tələb edir",
                priority = AdvicePriority.ATTENTION,
                items = report.attention,
                empty = "Diqqət tələb edən hal aşkarlanmadı.",
            )
        }
        item {
            Bucket(
                title = "Yaxşı gedir",
                priority = AdvicePriority.GOOD,
                items = report.good,
                empty = "Bu ay üçün müsbət müşahidə yoxdur.",
            )
        }
        item {
            Bucket(
                title = "Nəzərdən keçirməyə dəyər",
                priority = AdvicePriority.REVIEW,
                items = report.review,
                empty = "Nəzərdən keçirilməli hal yoxdur.",
            )
        }

        /* --- what could not be said, and why -------------------------- */
        if (report.unavailable.isNotEmpty()) {
            item {
                Panel(
                    title = "Hələ hesablana bilməyənlər",
                    note = report.unavailable.size.toString(),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        report.unavailable.forEach { entry ->
                            Column {
                                Text(
                                    text = METHODS[entry.method]?.name.orEmpty(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = colors.text,
                                )
                                Text(
                                    text = entry.reason,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.textMuted,
                                )
                            }
                        }
                    }
                }
            }
        }

        item { MethodologyPanel(asOf) }
    }
}

/* ------------------------------------------------------------------ */

/** Colour stands for a kind here, the same way it stands for a category
 *  elsewhere: one hue, one meaning. */
@Composable
private fun kindColor(kind: CategoryKind): Color = when (kind) {
    CategoryKind.ESSENTIAL -> spendlyColors.series[0]
    CategoryKind.DEBT -> spendlyColors.series[2]
    CategoryKind.DISCRETIONARY -> spendlyColors.series[3]
    CategoryKind.SAVING -> spendlyColors.series[1]
}

/**
 * Why a framework is not shown.
 *
 * Saying "not enough data" and stopping is a dead end; naming the categories
 * that are unclassified turns it into something the user can act on in one
 * tap from the Büdcə screen.
 *
 * The coverage sentence appears only when coverage is what is actually
 * missing. Telling somebody who has classified everything that they need to
 * classify 90% of it sends them to do work that will not help — the real
 * reason is in `extra`.
 */
@Composable
private fun Missing(split: SpendingSplit, extra: String? = null) {
    val colors = spendlyColors

    if (split.total <= 0) {
        Text(
            text = "Bu ay üçün xərc qeydə alınmayıb.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textMuted,
        )
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!split.hasCoverage) {
            Text(
                text = "Xərclərin ${(split.coverage * 100).roundToInt()}%-i təsnif edilib — " +
                    "hesablama üçün ən azı " +
                    "${(CLASSIFICATION_COVERAGE_MIN * 100).roundToInt()}% lazımdır.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
            )
            if (split.missing.isNotEmpty()) {
                Text(
                    text = "Təsnif edilməyib: ${split.missing.take(5).joinToString(", ")}" +
                        if (split.missing.size > 5) " və daha ${split.missing.size - 5}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.text,
                )
                Text(
                    text = "Büdcə → Kateqoriyalar bölməsində hər birinin növünü seçin.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textFaint,
                )
            }
        }
        if (extra != null) {
            Text(
                text = extra,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
            )
        }
    }
}

/** One line of the reference split: what it is here, against what the
 *  framework suggests, with the reference drawn on the bar. */
@Composable
private fun FrameworkRow(label: String, actual: Double, reference: Double, amount: Double) {
    val colors = spendlyColors
    Column(modifier = Modifier.padding(bottom = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${(actual * 100).roundToInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.text,
            )
            Text(
                text = " / ${(reference * 100).roundToInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textFaint,
            )
        }
        Box(modifier = Modifier.padding(vertical = 6.dp)) {
            /* One colour for all three rows. Colouring "over the reference" as
               a warning would be wrong on the savings row, where over is the
               good direction — the mark says where the reference is, and the
               reading below says what the distance means. */
            Track(
                value = actual.coerceIn(0.0, 1.0),
                color = colors.series[0],
                reference = reference,
            )
        }
        Text(
            text = formatAZN(amount),
            style = MaterialTheme.typography.bodySmall,
            color = colors.textFaint,
        )
    }
}

/**
 * What matching, or missing, the reference actually means.
 *
 * The three percentages on their own leave the reader to work out whether
 * being over on one and under on another is good or bad. It is arithmetic, so
 * the screen can say it: the framework is a means, and the share retained is
 * the end it is aiming at.
 */
@Composable
private fun FrameworkReading(framework: FrameworkSplit) {
    val gaps = frameworkGaps(framework)
    val needs = gaps.needs.roundToInt()
    val savings = gaps.savings.roundToInt()
    val retained = (framework.savingsShare * 100).roundToInt()

    Reading(
        when {
            needs > 0 && savings >= 0 ->
                "Zəruri xərcləriniz istinaddan $needs bənd yuxarıdır, amma buna " +
                    "baxmayaraq gəlirinizin $retained%-i qalır — istinadın gözlədiyi " +
                    "20%-dən çox. Yəni sabit xərclərinizin böyüklüyünü istəyə bağlı " +
                    "xərcləri aşağı saxlamaqla bağlayırsınız."

            needs > 0 ->
                "Zəruri xərcləriniz istinaddan $needs bənd yuxarıdır, və qalan pay da " +
                    "istinaddan aşağıdır. Sabit xərclər gəlirin böyük hissəsini tutduğu " +
                    "üçün yığıma az yer qalır."

            else ->
                "Üç payın da istinada yaxındır. Gəlirinizin $retained%-i xərclənmədən " +
                    "qalır."
        },
    )
}

/** A panel's reading: what its figures mean, in a sentence. */
@Composable
private fun Reading(text: String) {
    val colors = spendlyColors
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = colors.text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(Radius.sm))
            .background(colors.surfaceInset)
            .padding(12.dp),
    )
}

/** The sentence that says what a framework is and is not. */
@Composable
private fun FrameworkNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = spendlyColors.textFaint,
        modifier = Modifier.padding(top = 10.dp),
    )
}

@Composable
private fun Figure(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    tone: Color? = null,
    hint: String? = null,
) {
    val colors = spendlyColors
    Column(modifier = modifier) {
        Micro(label)
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = tone ?: colors.text,
        )
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textFaint,
            )
        }
    }
}

@Composable
private fun Bucket(
    title: String,
    priority: AdvicePriority,
    items: List<Advice>,
    empty: String,
) {
    val colors = spendlyColors
    val accent = when (priority) {
        AdvicePriority.ATTENTION -> colors.negative
        AdvicePriority.GOOD -> colors.positive
        AdvicePriority.REVIEW -> colors.series[3]
    }

    Panel(title = title, note = items.size.takeIf { it > 0 }?.toString()) {
        if (items.isEmpty()) {
            Text(
                text = empty,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textFaint,
            )
            return@Panel
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (item in items) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(if (item.suggestion != null) 76.dp else 52.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(accent),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = item.fact,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.text,
                        )

                        item.meter?.let { meter ->
                            Column(modifier = Modifier.padding(vertical = 2.dp)) {
                                Track(
                                    value = meter.value,
                                    color = accent,
                                    reference = meter.reference,
                                )
                                Text(
                                    text = meter.label,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.textFaint,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }

                        item.suggestion?.let { suggestion ->
                            Text(
                                text = suggestion,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textMuted,
                            )
                        }

                        Text(
                            text = METHODS[item.method]?.name.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textFaint,
                        )
                    }
                }
            }
        }
    }
}

/** A bar with an optional reference mark, for the figure a rule judged by. */
@Composable
private fun Track(value: Double, color: Color, reference: Double? = null) {
    val colors = spendlyColors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(colors.track),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(value.coerceIn(0.0, 1.0).toFloat())
                .height(8.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(color),
        )
        if (reference != null) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.fillMaxWidth(min(reference, 1.0).toFloat()))
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(8.dp)
                        .background(colors.text),
                )
            }
        }
    }
}

/** Where each rule's reference comes from, and when it was last checked. */
@Composable
private fun MethodologyPanel(asOf: String) {
    val colors = spendlyColors
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }

    Panel(title = "Metodologiya") {
        Text(
            text = "Bu səhifə maliyyə məsləhəti deyil. Hesablamalar sizin öz rəqəmlərinizdir; " +
                "çərçivələr aşağıdakı mənbələrdən götürülüb və istinad kimi göstərilir.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textMuted,
        )

        TextButton(onClick = { open = !open }) {
            Text(if (open) "Gizlət" else "Mənbələri göstər")
        }

        if (open) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                METHODS.forEach { (_, method) ->
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = method.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = colors.text,
                                modifier = Modifier.weight(1f),
                            )
                            Pill(ORIGIN_LABEL[method.origin].orEmpty())
                        }
                        Text(
                            text = method.note,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textMuted,
                        )
                        Text(
                            text = method.source,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (method.url != null) colors.accent else colors.textFaint,
                            modifier = if (method.url != null) {
                                Modifier.clickable {
                                    // Opening a source is the one place this app
                                    // hands off to a browser, so it is explicit.
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, method.url.toUri()),
                                    )
                                }
                            } else {
                                Modifier
                            },
                        )
                        Text(
                            text = "yoxlanılıb ${method.reviewedOn}" +
                                if (needsReview(method, asOf)) " — yenilənməlidir" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (needsReview(method, asOf)) {
                                colors.negative
                            } else {
                                colors.textFaint
                            },
                        )
                    }
                }
            }
        }
    }
}
