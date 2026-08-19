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
import az.spendly.domain.MonthKey
import az.spendly.domain.formatAZN
import az.spendly.domain.formatMonth
import az.spendly.domain.formatSignedAZN
import az.spendly.domain.insights.Advice
import az.spendly.domain.insights.AdvicePriority
import az.spendly.domain.insights.METHODS
import az.spendly.domain.insights.ORIGIN_LABEL
import az.spendly.domain.insights.REVIEW_INTERVAL_MONTHS
import az.spendly.domain.insights.budgetAdvice
import az.spendly.domain.insights.methodsNeedingReview
import az.spendly.domain.insights.needsReview
import az.spendly.domain.today
import az.spendly.ui.components.Micro
import az.spendly.ui.components.Panel
import az.spendly.ui.components.Pill
import az.spendly.ui.theme.Radius
import az.spendly.ui.theme.spendlyColors
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun AdviceScreen(data: FinanceData, month: MonthKey, modifier: Modifier = Modifier) {
    val colors = spendlyColors
    val asOf = today()
    val report = remember(data, month, asOf) { budgetAdvice(data, month, asOf) }
    val stale = remember(asOf) { methodsNeedingReview(asOf) }
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
