/**
 * The dashboard, read top to bottom:
 *
 *   where I stand · what the plan has left · how money came and went
 *   how it moved over time · what changed
 *   where it went · against the plan
 *   what was unexpected · what recurs
 *   when it happened · the big ones
 *
 * Each panel is shown only when the data can support it, so an empty month is
 * a short page rather than a wall of zeroes.
 */
package az.spendly.ui.screens

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
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import az.spendly.domain.CategoryRow
import az.spendly.domain.FinanceData
import az.spendly.domain.Insight
import az.spendly.domain.InsightTone
import az.spendly.domain.MonthKey
import az.spendly.domain.PERIODS
import az.spendly.domain.PeriodId
import az.spendly.domain.Transaction
import az.spendly.domain.TransactionType
import az.spendly.domain.UnexpectedReason
import az.spendly.domain.categoryBreakdown
import az.spendly.domain.comparisonLabel
import az.spendly.domain.dailyActivity
import az.spendly.domain.depositedFromIncome
import az.spendly.domain.expectedSplit
import az.spendly.domain.flowBuckets
import az.spendly.domain.formatAZN
import az.spendly.domain.formatDayShort
import az.spendly.domain.formatMonth
import az.spendly.domain.formatSignedAZN
import az.spendly.domain.formatWeekdayShort
import az.spendly.domain.frequentExpenses
import az.spendly.domain.incomeSources
import az.spendly.domain.insights
import az.spendly.domain.isSingleMonth
import az.spendly.domain.largestTransactions
import az.spendly.domain.monthOf
import az.spendly.domain.previousPeriod
import az.spendly.domain.recurringCommitments
import az.spendly.domain.resolvePeriod
import az.spendly.domain.round2
import az.spendly.domain.savingsBalance
import az.spendly.domain.spendableBalance
import az.spendly.domain.spendingPace
import az.spendly.domain.summarisePeriod
import az.spendly.domain.today
import az.spendly.domain.totalHoldings
import az.spendly.domain.transactionsInPeriod
import az.spendly.domain.weekdayOf
import az.spendly.domain.weekdayPattern
import az.spendly.ui.charts.DayStrip
import az.spendly.ui.charts.FlowChart
import az.spendly.ui.charts.IncomeBars
import az.spendly.ui.charts.PlanBars
import az.spendly.ui.charts.RankedBars
import az.spendly.ui.charts.Sparkline
import az.spendly.ui.charts.SpendRing
import az.spendly.ui.charts.WeekdayBars
import az.spendly.ui.charts.rememberCategoryColors
import az.spendly.ui.charts.ringSlices
import az.spendly.ui.components.EmptyState
import az.spendly.ui.components.Meter
import az.spendly.ui.components.Micro
import az.spendly.ui.components.MoneyRow
import az.spendly.ui.components.Panel
import az.spendly.ui.components.Pill
import az.spendly.ui.components.PillTone
import az.spendly.ui.components.RowCard
import az.spendly.ui.components.RowDivider
import az.spendly.ui.components.Swatch
import az.spendly.ui.dialogs.DetailDialog
import az.spendly.ui.theme.Radius
import az.spendly.ui.theme.spendlyColors
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun DashboardScreen(
    data: FinanceData,
    month: MonthKey,
    onSelectTransaction: (Transaction) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = spendlyColors
    var periodId by remember { mutableStateOf(PeriodId.MONTH) }
    var drill by remember { mutableStateOf<Drill?>(null) }

    val period = remember(periodId, month) { resolvePeriod(periodId, month) }

    val summary = remember(data, period) { summarisePeriod(data, period) }
    val prior = remember(data, period) { summarisePeriod(data, previousPeriod(period)) }
    val balance = remember(data, period) { spendableBalance(data, period.months.last()) }
    val priorBalance = remember(data, period) {
        spendableBalance(data, previousPeriod(period).months.last())
    }
    val saved = remember(data, period) {
        savingsBalance(data.savingsEntries, period.months.last())
    }
    val holdings = remember(data, period) { totalHoldings(data, period.months.last()) }
    val categories = remember(data, period) { categoryBreakdown(data, period) }
    val split = remember(data, period) { expectedSplit(data, period) }
    val buckets = remember(data, period) { flowBuckets(data, period) }
    val facts = remember(data, period) { insights(data, period) }
    val largest = remember(data, period) { largestTransactions(data, period, 5) }
    val income = remember(data, period) { incomeSources(data, period) }
    val weekdays = remember(data, period) { weekdayPattern(data, period) }
    val frequent = remember(data, period) { frequentExpenses(data, period, 5) }
    val pace = remember(data, period) {
        if (period.months.size == 1) spendingPace(data, period.months.first(), today()) else null
    }
    val periodTransactions = remember(data, period) { transactionsInPeriod(data.transactions, period) }
    // Movements in this period, and what of them left the spendable side.
    val periodEntries = remember(data, period) {
        data.savingsEntries.filter { monthOf(it.date) in period.months }
    }
    val deposited = remember(data, period) {
        round2(period.months.sumOf { depositedFromIncome(data.savingsEntries, it) })
    }

    val spent = categories.filter { it.actual > 0 }
    // Colours come from the ranked breakdown, so a category is the same colour
    // in the ring, the ranking and the plan comparison.
    val colorOf = rememberCategoryColors(categories.map { it.category })
    // A month whose only record is a savings movement is not an empty month:
    // the balance moved, and saying "nothing here" next to that reads as a bug.
    val hasActivity = periodTransactions.isNotEmpty() || periodEntries.isNotEmpty()
    val hasComparison = prior.transactionCount > 0
    val budgetLeft = round2(summary.plannedExpenses - summary.expenses)

    fun openCategory(category: String) {
        drill = Drill(
            title = category,
            subtitle = period.label,
            transactions = periodTransactions.filter {
                it.type == TransactionType.EXPENSE && it.category == category
            },
        )
    }

    fun openWeekday(weekday: Int) {
        drill = Drill(
            title = formatWeekdayShort(weekday),
            subtitle = period.label,
            transactions = periodTransactions.filter {
                it.type == TransactionType.EXPENSE && weekdayOf(it.date) == weekday
            },
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = if (isSingleMonth(period)) {
                        formatMonth(period.months.first())
                    } else {
                        "${formatMonth(period.months.first())} — ${formatMonth(period.months.last())}"
                    } + " · ${periodTransactions.size} əməliyyat" +
                        if (periodEntries.isNotEmpty()) {
                            " · ${periodEntries.size} yığım hərəkəti"
                        } else {
                            ""
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                )

                /* Five ranges share the width evenly instead of being laid out
                   at whatever width each word wants — at their own widths the
                   last one ran off the side of the screen and could not be
                   tapped. The web app gives them `flex: 1` on a phone for the
                   same reason. */
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (option in PERIODS) {
                        val selected = option.id == periodId
                        BasicText(
                            text = option.short,
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = if (selected) colors.onAccent else colors.textMuted,
                                fontWeight = if (selected) {
                                    FontWeight.SemiBold
                                } else {
                                    FontWeight.Normal
                                },
                                textAlign = TextAlign.Center,
                            ),
                            maxLines = 1,
                            // The longest of the five decides the size, so none
                            // of them is cut off inside its own chip.
                            autoSize = TextAutoSize.StepBased(
                                minFontSize = 9.sp,
                                maxFontSize = MaterialTheme.typography.bodySmall.fontSize,
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(Radius.xs))
                                .background(if (selected) colors.accent else colors.surface)
                                .clickable { periodId = option.id }
                                .padding(horizontal = 4.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }

        /* --- where I stand -------------------------------------------- */
        item {
            Panel(
                title = "Balans",
                note = if (saved > 0) "xərcləyə bilən" else null,
            ) {
                Text(
                    text = formatAZN(balance),
                    style = MaterialTheme.typography.displaySmall,
                    color = if (balance < 0) colors.negative else colors.text,
                )

                // Money in a pot is money you have, so a balance that excludes
                // it needs the rest said next to it or it reads as a loss.
                if (saved > 0) {
                    Text(
                        text = "yığım ${formatAZN(saved)} · cəmi ${formatAZN(holdings)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textMuted,
                    )
                }
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Delta(round2(balance - priorBalance), hasComparison)
                    Text(
                        text = comparisonLabel(period),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textMuted,
                    )
                }
                if (buckets.size > 1) {
                    Column(modifier = Modifier.padding(top = 12.dp)) {
                        Sparkline(buckets.map { it.balance })
                        Text(
                            text = if (isSingleMonth(period)) {
                                "Ay ərzində balans, həftəlik"
                            } else {
                                "Dövr ərzində balans, aylıq"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textFaint,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }

        /* --- what the plan has left ------------------------------------ */
        item {
            if (summary.plannedExpenses > 0) {
                Panel(
                    title = "Büdcə",
                    note = "${((summary.expenses / summary.plannedExpenses) * 100).roundToInt()}% istifadə olunub",
                ) {
                    SpendRing(
                        slices = ringSlices(spent, colorOf),
                        spent = summary.expenses,
                        planned = summary.plannedExpenses,
                        onSelect = ::openCategory,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = "planlaşdırılan ${formatAZN(summary.plannedExpenses)} məbləğdən",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textMuted,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "${formatSignedAZN(budgetLeft)} qalıq",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (budgetLeft < 0) colors.negative else colors.text,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            } else {
                Panel(title = "Büdcə") {
                    EmptyState(
                        title = "Bu dövr üçün plan yoxdur",
                        body = "Xərcləri planla müqayisə etmək üçün Büdcə səhifəsində " +
                            "planlaşdırılan məbləğləri təyin edin.",
                    )
                }
            }
        }

        /* --- how money came and went ----------------------------------- */
        item {
            Panel(title = "Pul dövriyyəsi") {
                val peak = maxOf(summary.income, summary.expenses)
                CashflowRow(
                    label = "Daxil olan",
                    value = summary.income,
                    max = peak,
                    color = colors.positive,
                    note = if (hasComparison) {
                        "${formatSignedAZN(round2(summary.income - prior.income))} ${comparisonLabel(period)}"
                    } else {
                        "planlaşdırılan ${formatAZN(summary.plannedIncome)}"
                    },
                )
                CashflowRow(
                    label = "Xərclənən",
                    value = summary.expenses,
                    max = peak,
                    color = colors.series[0],
                    note = when {
                        summary.plannedExpenses > 0 && summary.expenses > summary.plannedExpenses ->
                            "plandan ${formatAZN(summary.expenses - summary.plannedExpenses)} artıq"
                        summary.plannedExpenses > 0 ->
                            "plandan ${formatAZN(summary.plannedExpenses - summary.expenses)} az"
                        else ->
                            "${periodTransactions.count { it.type == TransactionType.EXPENSE }} əməliyyat"
                    },
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Micro("Qalan")
                        Text(
                            text = formatSignedAZN(summary.remainder),
                            style = MaterialTheme.typography.headlineSmall,
                            maxLines = 1,
                            softWrap = false,
                            color = when {
                                summary.remainder < 0 -> colors.negative
                                summary.remainder > 0 -> colors.positive
                                else -> colors.text
                            },
                        )
                    }
                    Pill(
                        text = summary.savingsRate
                            ?.let { "gəlirin ${(it * 100).roundToInt()}%-i" }
                            ?: "Gəlir qeydə alınmayıb",
                    )
                }

                // "Qalan" is income minus spending, and a deposit is neither —
                // so this figure still holds money the balance above has
                // already moved into a pot. Two right answers to two different
                // questions, which only confuse each other when nobody says so.
                if (deposited > 0) {
                    Text(
                        text = "bunun ${formatAZN(deposited)} hissəsi yığım qabına keçib — " +
                            "balansda yox, qabdadır",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textMuted,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }

        if (!hasActivity) {
            item {
                RowCard {
                    EmptyState(
                        title = (if (isSingleMonth(period)) {
                            formatMonth(period.months.first())
                        } else {
                            period.label.lowercase()
                        }) + " üçün qeyd yoxdur",
                        body = "Gəlir və ya xərcinizi əlavə edin — bu səhifə doldurulacaq.",
                        action = { Button(onClick = onAdd) { Text("Əməliyyat əlavə et") } },
                    )
                }
            }
        }

        /* --- how money moved ------------------------------------------- */
        if (hasActivity) {
            item {
                Panel(
                    title = "Pul axını",
                    note = if (isSingleMonth(period)) "həftəlik" else "aylıq",
                ) {
                    FlowChart(buckets)
                }
            }
        }

        /* --- what changed ---------------------------------------------- */
        if (facts.isNotEmpty()) {
            item {
                // The note carries the comparison for every line under it, so
                // none of them has to repeat which period it is measured against.
                Panel(title = "Nə dəyişdi", note = comparisonLabel(period)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        facts.forEach { InsightLine(it) }
                    }
                }
            }
        }

        /* --- where it went --------------------------------------------- */
        if (spent.isNotEmpty()) {
            item {
                Panel(title = "Pul hara getdi", note = "${spent.size} kateqoriya", flush = true) {
                    RankedBars(rows = spent, colorOf = colorOf, onSelect = ::openCategory)
                }
            }
        }

        /* --- against the plan ------------------------------------------ */
        if (summary.plannedExpenses > 0) {
            item {
                Panel(
                    title = "Plan və faktiki",
                    note = "${formatAZN(summary.expenses)} / ${formatAZN(summary.plannedExpenses)}",
                    flush = true,
                ) {
                    PlanBars(
                        rows = categories.filter { it.planned > 0 || it.actual > 0 },
                        colorOf = colorOf,
                        onSelect = ::openCategory,
                    )
                }
            }
        }

        /* --- what came in ---------------------------------------------- */
        if (income.isNotEmpty()) {
            item {
                Panel(title = "Gəlir mənbələri", note = formatAZN(summary.income)) {
                    IncomeBars(income)
                }
            }
        }

        /* --- what was not in the plan ----------------------------------- */
        if (summary.expenses > 0 && summary.plannedExpenses > 0) {
            item {
                Panel(title = "Gözlənilən və gözlənilməz") {
                    // Two shares of one width, so the bar always reads as the
                    // whole of what was spent.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(colors.track),
                    ) {
                        val expectedShare = (split.expected / summary.expenses).toFloat()
                        if (expectedShare > 0f) {
                            Box(
                                modifier = Modifier
                                    .weight(expectedShare)
                                    .fillMaxHeight()
                                    .background(colors.series[0]),
                            )
                        }
                        if (expectedShare < 1f) {
                            Box(
                                modifier = Modifier
                                    .weight(1f - expectedShare)
                                    .fillMaxHeight()
                                    .background(colors.negative),
                            )
                        }
                    }

                    /* Stacked, not side by side: half a phone's width is not
                       enough for a sum in manat with a sentence under it, and
                       the web app stacks these on a phone too. */
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        SplitLegend(
                            label = "Gözlənilən",
                            value = formatAZN(split.expected),
                            note = "Planla əhatə olunub",
                            color = colors.series[0],
                            valueColor = colors.text,
                        )
                        SplitLegend(
                            label = "Gözlənilməz",
                            value = formatAZN(split.unexpected),
                            note = "Plandan artıq və ya planlaşdırılmamış",
                            color = colors.negative,
                            valueColor = if (split.unexpected > 0) colors.negative else colors.text,
                        )
                    }

                    if (split.items.isNotEmpty()) {
                        Column(
                            modifier = Modifier.padding(top = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            split.items.take(4).forEach { item ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { openCategory(item.category) },
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Text(
                                        text = formatAZN(item.amount),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = colors.text,
                                    )
                                    Text(
                                        text = if (item.reason == UnexpectedReason.NO_PLAN) {
                                            "${item.category} — bu dövr üçün plan yoxdur"
                                        } else {
                                            "${item.category} — planlaşdırılan " +
                                                "${formatAZN(item.planned)} məbləğdən artıq"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.textMuted,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        /* --- how fast the month is going --------------------------------- */
        if (pace != null && pace.spent > 0) {
            item {
                Panel(title = "Xərc tempi", note = "${pace.elapsed}/${pace.days} gün") {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = formatAZN(pace.perDay),
                            style = MaterialTheme.typography.headlineSmall,
                            color = colors.text,
                        )
                        Text(
                            text = " gündə",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textMuted,
                            modifier = Modifier.padding(bottom = 3.dp),
                        )
                    }
                    Text(
                        text = formatAZN(pace.spent) + if (pace.complete) {
                            " — ${pace.days} günün ortalaması"
                        } else {
                            " — ${pace.elapsed} gündə"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textMuted,
                    )
                    Box(modifier = Modifier.padding(top = 10.dp)) {
                        Meter(pace.elapsed.toDouble(), pace.days.toDouble())
                    }
                    if (pace.planned > 0) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                text = if (pace.complete) "Ay üzrə cəmi" else "Bu templə ayın sonuna",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textMuted,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = formatAZN(pace.atThisRate),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false,
                                color = if (pace.atThisRate > pace.planned) {
                                    colors.negative
                                } else {
                                    colors.positive
                                },
                            )
                        }
                        Text(
                            text = "${formatAZN(pace.planned)} planlaşdırılıb",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textFaint,
                        )
                    }
                }
            }
        }

        /* --- when it happened -------------------------------------------- */
        if (hasActivity && isSingleMonth(period)) {
            item {
                Panel(title = "Günlük hərəkət", note = formatMonth(period.months.first())) {
                    DayStrip(
                        days = dailyActivity(data, period.months.first()),
                        onSelect = { transactions ->
                            drill = Drill(
                                title = formatDayShort(transactions.first().date),
                                subtitle = "${transactions.size} qeyd",
                                transactions = transactions,
                            )
                        },
                    )
                }
            }
        }

        /* --- which days carry the spending -------------------------------- */
        if (summary.expenses > 0) {
            item {
                Panel(title = "Həftənin günləri", note = "xərclər") {
                    WeekdayBars(rows = weekdays, onSelect = ::openWeekday)
                }
            }
        }

        /* --- recurring commitments ---------------------------------------- */
        if (isSingleMonth(period)) {
            val recurring = recurringCommitments(data, period.months.first())
                .filter { it.planned > 0 }
            if (recurring.isNotEmpty()) {
                item {
                    val shown = recurring.take(6)
                    val hidden = recurring.size - shown.size
                    val missing = recurring.count { it.matched.isEmpty() }

                    Panel(
                        title = "Təkrarlanan",
                        note = if (missing == 0) {
                            "hamısı əməliyyatla uyğunlaşdı"
                        } else {
                            "$missing uyğun əməliyyat tapılmadı"
                        },
                        flush = true,
                    ) {
                        shown.forEachIndexed { index, item ->
                            if (index > 0) RowDivider()
                            MoneyRow(
                                title = item.description,
                                meta = item.category,
                                // What was planned is left off the amount here.
                                // A description, a status mark and two sums on
                                // one row is one thing too many for a phone,
                                // and the planned figure is on the Büdcə
                                // screen — the web app drops it here as well.
                                amount = if (item.matched.isNotEmpty()) {
                                    formatAZN(item.actual)
                                } else {
                                    "—"
                                },
                                trailing = {
                                    Pill(
                                        text = if (item.matched.isNotEmpty()) {
                                            "Qeyd olunub"
                                        } else {
                                            "Uyğunluq yoxdur"
                                        },
                                        tone = if (item.matched.isNotEmpty()) {
                                            PillTone.POSITIVE
                                        } else {
                                            PillTone.NEUTRAL
                                        },
                                    )
                                },
                            )
                        }
                        if (hidden > 0) {
                            Text(
                                text = "büdcədə daha $hidden təkrarlanan sətir",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textFaint,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                }
            }
        }

        /* --- what repeats -------------------------------------------------- */
        if (frequent.isNotEmpty()) {
            item {
                Panel(title = "Ən çox təkrarlanan", flush = true) {
                    frequent.forEachIndexed { index, item ->
                        if (index > 0) RowDivider()
                        MoneyRow(
                            title = item.description,
                            meta = item.category,
                            amount = "−${formatAZN(item.total)}",
                            trailing = { Pill("${item.count}×") },
                            onClick = {
                                drill = Drill(
                                    title = item.description,
                                    subtitle = period.label,
                                    transactions = periodTransactions.filter {
                                        it.type == TransactionType.EXPENSE &&
                                            it.description.trim().lowercase() ==
                                            item.description.trim().lowercase()
                                    },
                                )
                            },
                        )
                    }
                }
            }
        }

        /* --- biggest single expenses ---------------------------------------- */
        if (largest.isNotEmpty()) {
            item {
                Panel(title = "Ən böyük xərclər", flush = true) {
                    largest.forEachIndexed { index, transaction ->
                        if (index > 0) RowDivider()
                        MoneyRow(
                            leading = formatDayShort(transaction.date),
                            title = transaction.description,
                            meta = transaction.category,
                            amount = "−${formatAZN(transaction.amount)}",
                            onClick = { onSelectTransaction(transaction) },
                        )
                    }
                }
            }
        }

        /* --- this period against the one before it ---------------------------- */
        if (hasComparison) {
            item {
                Panel(title = "Müqayisə", note = comparisonLabel(period)) {
                    CompareRow("Daxil olan", summary.income, prior.income)
                    CompareRow("Xərclənən", summary.expenses, prior.expenses, invert = true)
                    CompareRow("Qalan", summary.remainder, prior.remainder, signed = true)
                    CompareRow(
                        "Əməliyyat",
                        summary.transactionCount.toDouble(),
                        prior.transactionCount.toDouble(),
                        count = true,
                    )
                }
            }
        }

        /* --- the plan on its own terms ---------------------------------------- */
        if (summary.plannedIncome > 0 || summary.plannedExpenses > 0) {
            item {
                Panel(title = "Plan") {
                    PlanFigure("Planlaşdırılan gəlir", formatAZN(summary.plannedIncome))
                    PlanFigure("Planlaşdırılan xərc", formatAZN(summary.plannedExpenses))
                    PlanFigure(
                        label = "Planlaşdırılan qalıq",
                        value = formatSignedAZN(summary.plannedRemainder),
                        valueColor = if (summary.plannedRemainder < 0) {
                            colors.negative
                        } else {
                            colors.positive
                        },
                        strong = true,
                    )
                    Text(
                        text = (if (summary.plannedRemainder < 0) {
                            "Bu plan qazandığından çoxunu xərcləyir."
                        } else {
                            "Plan üzrə gəlir xərcdən çoxdur."
                        }) + " Faktiki fərq: ${formatSignedAZN(summary.difference)}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textMuted,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }

    drill?.let { open ->
        DetailDialog(
            title = open.title,
            subtitle = open.subtitle,
            transactions = open.transactions,
            onSelect = { transaction ->
                drill = null
                onSelectTransaction(transaction)
            },
            onDismiss = { drill = null },
        )
    }
}

private data class Drill(
    val title: String,
    val subtitle: String?,
    val transactions: List<Transaction>,
)

/** One direction of the period's cashflow: a figure, its share of the larger
 *  side, and one line of context. */
@Composable
private fun CashflowRow(
    label: String,
    value: Double,
    max: Double,
    color: Color,
    note: String,
) {
    val colors = spendlyColors
    Column(modifier = Modifier.padding(bottom = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatAZN(value),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.text,
                maxLines = 1,
                softWrap = false,
            )
        }
        Box(modifier = Modifier.padding(vertical = 6.dp)) {
            Meter(value, max, color = color)
        }
        Text(
            text = note,
            style = MaterialTheme.typography.bodySmall,
            color = colors.textFaint,
        )
    }
}

/**
 * One line of the period-over-period comparison: now and the move against
 * before. [invert] is for figures where up is the unwelcome direction.
 *
 * The earlier figure itself is not on the line. Four columns of money do not
 * fit the width of a phone: the label was squeezed to a letter a line and the
 * move became a blob. The move is the thing this panel is for, and it carries
 * the comparison on its own — which is why the web app drops the same column
 * once the window is phone-width.
 */
@Composable
private fun CompareRow(
    label: String,
    now: Double,
    before: Double,
    signed: Boolean = false,
    invert: Boolean = false,
    count: Boolean = false,
) {
    val colors = spendlyColors
    val move = round2(now - before)
    val format: (Double) -> String = { value ->
        when {
            count -> value.roundToInt().toString()
            signed -> formatSignedAZN(value)
            else -> formatAZN(value)
        }
    }
    val good = if (invert) move < 0 else move > 0

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = format(now),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.text,
            maxLines = 1,
            softWrap = false,
        )
        if (move == 0.0) {
            Pill("—")
        } else {
            Pill(
                text = "${if (move > 0) "↑" else "↓"} " +
                    if (count) abs(move).roundToInt().toString() else formatAZN(abs(move)),
                tone = if (good) PillTone.POSITIVE else PillTone.NEGATIVE,
            )
        }
    }
}

@Composable
private fun PlanFigure(
    label: String,
    value: String,
    valueColor: Color? = null,
    strong: Boolean = false,
) {
    val colors = spendlyColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Medium,
            color = valueColor ?: colors.text,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun SplitLegend(
    label: String,
    value: String,
    note: String,
    color: Color,
    valueColor: Color,
    modifier: Modifier = Modifier,
) {
    val colors = spendlyColors
    Column(modifier = modifier) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Swatch(color)
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = valueColor,
        )
        Text(
            text = note,
            style = MaterialTheme.typography.bodySmall,
            color = colors.textFaint,
        )
    }
}

@Composable
private fun InsightLine(insight: Insight) {
    val colors = spendlyColors
    val accent = when (insight.tone) {
        InsightTone.POSITIVE -> colors.positive
        InsightTone.ATTENTION -> colors.negative
        InsightTone.NEUTRAL -> colors.textFaint
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(34.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(accent),
        )
        Text(
            text = insight.text,
            style = MaterialTheme.typography.bodySmall,
            color = colors.text,
        )
    }
}

/** A change against the comparable previous period, or nothing when there is
 *  no earlier data to compare against. */
@Composable
private fun Delta(value: Double, enabled: Boolean) {
    when {
        !enabled -> Pill("Əvvəlki məlumat yoxdur")
        value == 0.0 -> Pill("Dəyişməyib")
        else -> Pill(
            text = "${if (value > 0) "↑" else "↓"} ${formatAZN(abs(value))}",
            tone = if (value > 0) PillTone.POSITIVE else PillTone.NEGATIVE,
        )
    }
}
