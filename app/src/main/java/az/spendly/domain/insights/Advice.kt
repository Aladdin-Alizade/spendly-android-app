/**
 * The advice engine.
 *
 * Every sentence on the Məsləhətlər screen is produced here, by a rule that
 * either fires or does not. There is no model in the loop and nothing is
 * generated: given the same figures the output is the same, every time, which
 * is what makes it testable.
 *
 * Three kinds of statement are kept apart on purpose:
 *   fact       — arithmetic on the user's own numbers
 *   suggestion — something worth looking at, phrased as a suggestion
 *   framework  — a published reference, named and sourced where used
 *
 * A rule that cannot be supported by the data stays silent, and says why in
 * [AdviceReport.unavailable] rather than lowering its own standard.
 */
package az.spendly.domain.insights

import az.spendly.domain.DateKey
import az.spendly.domain.FinanceData
import az.spendly.domain.MonthKey
import az.spendly.domain.TransactionType
import az.spendly.domain.actualExpenses
import az.spendly.domain.actualIncome
import az.spendly.domain.formatAZN
import az.spendly.domain.formatMonth
import az.spendly.domain.monthOf
import az.spendly.domain.plannedExpenses
import az.spendly.domain.plannedIncomeOf
import az.spendly.domain.round2
import az.spendly.domain.shiftMonth
import az.spendly.domain.sumOf
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class AdvicePriority { ATTENTION, GOOD, REVIEW }

data class AdviceMeter(
    /** Primary quantity, 0..1 of the bar. */
    val value: Double,
    /** Optional reference mark, 0..1. */
    val reference: Double? = null,
    val label: String,
)

data class Advice(
    val id: String,
    val priority: AdvicePriority,
    val method: MethodId,
    /** Arithmetic on the user's numbers. Never a judgement. */
    val fact: String,
    /** Phrased as something to consider, never as an instruction. */
    val suggestion: String? = null,
    /** Manat at stake, used to rank. A 40% overrun on 5 ₼ must not outrank 200 ₼. */
    val materiality: Double,
    /**
     * What the advice is about, usually a category. A bucket holds three
     * entries, and three findings about one category crowd out everything else
     * the month had to say — so only the largest per subject is kept.
     */
    val subject: String? = null,
    val meter: AdviceMeter? = null,
)

data class BudgetHealth(
    val income: Double,
    val expenses: Double,
    val remaining: Double,
    /** Share of income not spent. Null when nothing came in. */
    val retainedRate: Double?,
    /** Share of income spent. Null when nothing came in. */
    val spendingRatio: Double?,
    val plannedExpenses: Double,
    val plannedIncome: Double,
    /** actual − planned. Null when there is no plan. */
    val planVariance: Double?,
)

data class Unavailable(val method: MethodId, val reason: String)

data class AdviceReport(
    val month: MonthKey,
    val health: BudgetHealth,
    val attention: List<Advice>,
    val good: List<Advice>,
    val review: List<Advice>,
    /** Rules that stayed silent, and what they were missing. */
    val unavailable: List<Unavailable>,
)

/** At most this many per bucket, so the screen stays readable. */
private const val PER_BUCKET = 3

fun budgetAdvice(data: FinanceData, month: MonthKey, asOf: DateKey): AdviceReport {
    val income = actualIncome(data.transactions, month)
    val expenses = actualExpenses(data.transactions, month)
    val planned = plannedExpenses(data.budgetLines, month)
    val plan = data.incomePlans.firstOrNull { it.month == month }
    val plannedIn = round2(plannedIncomeOf(plan))

    val health = BudgetHealth(
        income = income,
        expenses = expenses,
        remaining = round2(income - expenses),
        retainedRate = if (income > 0) (income - expenses) / income else null,
        spendingRatio = if (income > 0) expenses / income else null,
        plannedExpenses = planned,
        plannedIncome = plannedIn,
        planVariance = if (planned > 0) round2(expenses - planned) else null,
    )

    val found = mutableListOf<Advice>()
    val unavailable = mutableListOf<Unavailable>()
    val context = Context(data, month, asOf, health, found::add, unavailable::add)

    RULES.forEach { rule -> rule(context) }

    fun bucket(priority: AdvicePriority): List<Advice> {
        val seen = mutableSetOf<String>()
        return found
            .filter { it.priority == priority }
            .sortedByDescending { it.materiality }
            .filter { entry ->
                val subject = entry.subject ?: return@filter true
                seen.add(subject)
            }
            .take(PER_BUCKET)
    }

    return AdviceReport(
        month = month,
        health = health,
        attention = bucket(AdvicePriority.ATTENTION),
        good = bucket(AdvicePriority.GOOD),
        review = bucket(AdvicePriority.REVIEW),
        unavailable = unavailable,
    )
}

/* ------------------------------------------------------------------ *
 * Rules
 * ------------------------------------------------------------------ */

private class Context(
    val data: FinanceData,
    val month: MonthKey,
    val asOf: DateKey,
    val health: BudgetHealth,
    val add: (Advice) -> Unit,
    val skip: (Unavailable) -> Unit,
)

private typealias Rule = (Context) -> Unit

private fun percent(ratio: Double): String = "${(abs(ratio) * 100).roundToInt()}%"

/* --- what the month looks like against income --------------------- */

private val spendingRatio: Rule = { context ->
    val ratio = context.health.spendingRatio
    if (ratio == null) {
        context.skip(Unavailable(MethodId.SPENDING_RATIO, "Bu ay gəlir qeyd edilməyib"))
    } else if (ratio > 1) {
        /* The ratio itself is already on screen in the health block, so it
           only becomes advice when it crosses the line where the month does
           not pay for itself. Restating it otherwise pushes the category
           detail out of a bucket that holds three. */
        context.add(
            Advice(
                id = "spending-ratio",
                method = MethodId.SPENDING_RATIO,
                priority = AdvicePriority.ATTENTION,
                fact = "Bu ay xərcləriniz gəlirinizin ${percent(ratio)}-ni təşkil edir " +
                    "(${formatAZN(context.health.expenses)} / ${formatAZN(context.health.income)}).",
                suggestion = "Fərqin hansı kateqoriyalardan gəldiyini nəzərdən keçirməyə dəyər.",
                materiality = round2(context.health.expenses - context.health.income),
            ),
        )
    }
}

private val retained: Rule = { context ->
    val health = context.health
    if (health.retainedRate == null) {
        context.skip(Unavailable(MethodId.RETAINED, "Bu ay gəlir qeyd edilməyib"))
    } else if (health.remaining < 0) {
        // Same reasoning as the ratio: the figure is in the health block already.
        context.add(
            Advice(
                id = "retained",
                method = MethodId.RETAINED,
                priority = AdvicePriority.ATTENTION,
                fact = "Bu ay gəlirinizdən ${formatAZN(-health.remaining)} çox xərclənib.",
                materiality = abs(health.remaining),
            ),
        )
    }
}

private val retainedTrend: Rule = { context ->
    val months = previousMonths(context.month, 3)
    val series = months
        .map { it to actualIncome(context.data.transactions, it) }
        .filter { (_, income) -> income > 0 }

    if (series.isEmpty() || context.health.retainedRate == null) {
        context.skip(
            Unavailable(MethodId.RETAINED, "Müqayisə üçün əvvəlki aylarda gəlir yoxdur"),
        )
    } else {
        val rates = series.map { (month, income) ->
            (income - actualExpenses(context.data.transactions, month)) / income
        }
        val average = rates.sum() / rates.size
        val gap = context.health.retainedRate - average

        if (abs(gap) >= Thresholds.materialRatio.value) {
            context.add(
                Advice(
                    id = "retained-trend",
                    method = MethodId.RETAINED,
                    priority = if (gap > 0) AdvicePriority.GOOD else AdvicePriority.REVIEW,
                    fact = if (gap > 0) {
                        "Qalan pulun payı son ${rates.size} ayın ortalamasından " +
                            "${percent(gap)} yüksəkdir " +
                            "(${percent(context.health.retainedRate)} / ${percent(average)})."
                    } else {
                        "Qalan pulun payı son ${rates.size} ayın ortalamasından " +
                            "${percent(gap)} aşağıdır " +
                            "(${percent(context.health.retainedRate)} / ${percent(average)})."
                    },
                    suggestion = if (gap < 0) {
                        "Fərqin gəlirin azalmasından, yoxsa xərcin artmasından gəldiyini " +
                            "nəzərdən keçirməyə dəyər."
                    } else {
                        null
                    },
                    materiality = abs(gap) * context.health.income,
                ),
            )
        }
    }
}

/* --- against the plan --------------------------------------------- */

private val totalVariance: Rule = { context ->
    val health = context.health
    val variance = health.planVariance
    if (variance == null) {
        context.skip(Unavailable(MethodId.VARIANCE, "Bu ay üçün xərc planı qurulmayıb"))
    } else if (abs(variance) >= Thresholds.materialAmount.value) {
        val ratio = variance / health.plannedExpenses
        if (abs(ratio) >= Thresholds.materialRatio.value) {
            context.add(
                Advice(
                    id = "total-variance",
                    method = MethodId.VARIANCE,
                    priority = if (variance > 0) AdvicePriority.ATTENTION else AdvicePriority.GOOD,
                    fact = if (variance > 0) {
                        "Ümumi xərciniz plandan ${formatAZN(variance)} çoxdur (${percent(ratio)})."
                    } else {
                        "Ümumi xərciniz plandan ${formatAZN(-variance)} azdır (${percent(ratio)})."
                    },
                    materiality = abs(variance),
                    meter = AdviceMeter(
                        value = min(health.expenses / health.plannedExpenses, 1.0),
                        reference = 1.0,
                        label = "plan ${formatAZN(health.plannedExpenses)}",
                    ),
                ),
            )
        }
    }
}

private val categoryVariance: Rule = { context ->
    for (row in categoryRows(context.data, context.month)) {
        if (row.planned <= 0) continue
        val variance = round2(row.actual - row.planned)
        if (abs(variance) < Thresholds.materialAmount.value) continue
        val ratio = variance / row.planned
        if (abs(ratio) < Thresholds.materialRatio.value) continue

        context.add(
            Advice(
                id = "variance-${row.category}",
                method = MethodId.VARIANCE,
                priority = if (variance > 0) AdvicePriority.ATTENTION else AdvicePriority.GOOD,
                fact = if (variance > 0) {
                    "${row.category} xərci plandan ${formatAZN(variance)} çoxdur " +
                        "(${formatAZN(row.actual)} / ${formatAZN(row.planned)})."
                } else {
                    "${row.category} xərci plandan ${formatAZN(-variance)} azdır " +
                        "(${formatAZN(row.actual)} / ${formatAZN(row.planned)})."
                },
                materiality = abs(variance),
                subject = row.category,
                meter = AdviceMeter(
                    value = min(row.actual / row.planned, 1.0),
                    reference = 1.0,
                    label = "plan ${formatAZN(row.planned)}",
                ),
            ),
        )
    }
}

private val repeatedOverrun: Rule = { context ->
    val window = Thresholds.repeatedWindow.value.toInt()
    val months = previousMonths(context.month, window - 1) + context.month
    val planned = months.filter { plannedExpenses(context.data.budgetLines, it) > 0 }

    if (planned.size < window) {
        context.skip(
            Unavailable(
                MethodId.VARIANCE,
                "Təkrarlanan aşım üçün $window ayın planı lazımdır (hazırda ${planned.size})",
            ),
        )
    } else {
        val categories = context.data.budgetLines
            .filter { months.contains(it.month) }
            .map { it.category }
            .toSet()

        for (category in categories) {
            val over = months.filter { month ->
                val plan = plannedFor(context.data, month, category)
                plan > 0 && actualFor(context.data, month, category) > plan
            }
            if (over.size < Thresholds.repeatedOverruns.value) continue

            val excess = sumOf(
                over.map {
                    actualFor(context.data, it, category) - plannedFor(context.data, it, category)
                },
            )

            context.add(
                Advice(
                    id = "repeated-$category",
                    method = MethodId.VARIANCE,
                    priority = AdvicePriority.ATTENTION,
                    fact = "$category son $window ayın ${over.size}-ində planı aşıb — " +
                        "cəmi ${formatAZN(excess)}.",
                    suggestion = "Nəzərdən keçirməyə dəyər: ya bu kateqoriyanın xərci, " +
                        "ya da onun üçün qoyulan plan məbləği.",
                    materiality = excess,
                    subject = category,
                ),
            )
        }
    }
}

/* --- statistics over history -------------------------------------- */

private val anomaly: Rule = { context ->
    val need = Thresholds.anomalyMinMonths.value.toInt()
    val baselineMonths = previousMonths(context.month, need + 2)
    val withData = baselineMonths.filter { actualExpenses(context.data.transactions, it) > 0 }

    if (withData.size < need) {
        context.skip(
            Unavailable(
                MethodId.ANOMALY,
                "Qeyri-adi xərc üçün ən azı $need aylıq tarixçə lazımdır " +
                    "(hazırda ${withData.size})",
            ),
        )
    } else {
        for (row in categoryRows(context.data, context.month)) {
            if (row.actual <= 0) continue
            val history = withData.map { actualFor(context.data, it, row.category) }
            val score = robustScore(row.actual, history)
            if (score == null || score <= Thresholds.anomalyScore.value) continue

            val typical = median(history)
            val difference = round2(row.actual - typical)
            if (abs(difference) < Thresholds.materialAmount.value) continue

            context.add(
                Advice(
                    id = "anomaly-${row.category}",
                    method = MethodId.ANOMALY,
                    priority = if (difference > 0) AdvicePriority.ATTENTION else AdvicePriority.REVIEW,
                    fact = "${row.category} bu ay ${formatAZN(row.actual)} — " +
                        "son ${history.size} ayın adi səviyyəsi ${formatAZN(typical)} olub.",
                    suggestion = if (difference > 0) {
                        "Birdəfəlik xərc olub-olmadığını yoxlamağa dəyər."
                    } else {
                        null
                    },
                    materiality = abs(difference),
                    subject = row.category,
                ),
            )
        }
    }
}

private val trend: Rule = { context ->
    val baseline = previousMonths(context.month, 3)
        .filter { actualExpenses(context.data.transactions, it) > 0 }

    if (baseline.size < Thresholds.trendMinMonths.value.toInt() - 1) {
        context.skip(
            Unavailable(
                MethodId.TREND,
                "Trend üçün əvvəlki 3 ayın məlumatı lazımdır (hazırda ${baseline.size})",
            ),
        )
    } else {
        for (row in categoryRows(context.data, context.month)) {
            val history = baseline.map { actualFor(context.data, it, row.category) }
            val average = history.sum() / history.size
            if (average < Thresholds.materialAmount.value) continue

            val ratio = (row.actual - average) / average
            if (abs(ratio) < Thresholds.trendRatio.value) continue

            val difference = round2(row.actual - average)
            if (abs(difference) < Thresholds.materialAmount.value) continue

            context.add(
                Advice(
                    id = "trend-${row.category}",
                    method = MethodId.TREND,
                    priority = if (ratio > 0) AdvicePriority.REVIEW else AdvicePriority.GOOD,
                    fact = if (ratio > 0) {
                        "${row.category} xərci son 3 ayın ortalamasından ${percent(ratio)} " +
                            "çoxdur (${formatAZN(row.actual)} / ${formatAZN(round2(average))})."
                    } else {
                        "${row.category} xərci son 3 ayın ortalamasından ${percent(ratio)} " +
                            "azdır (${formatAZN(row.actual)} / ${formatAZN(round2(average))})."
                    },
                    materiality = abs(difference),
                    subject = row.category,
                ),
            )
        }
    }
}

private val lifestyle: Rule = { context ->
    val need = Thresholds.lifestyleMinMonths.value.toInt()
    val months = previousMonths(context.month, need - 1) + context.month
    val active = months.filter { actualIncome(context.data.transactions, it) > 0 }

    if (active.size < need) {
        context.skip(
            Unavailable(
                MethodId.LIFESTYLE,
                "Müqayisə üçün $need aylıq tarixçə lazımdır (hazırda ${active.size})",
            ),
        )
    } else {
        val recent = months.takeLast(3)
        val earlier = months.take(3)
        fun mean(list: List<MonthKey>, pick: (MonthKey) -> Double) =
            list.sumOf(pick) / list.size

        val expenseBefore = mean(earlier) { actualExpenses(context.data.transactions, it) }
        val expenseNow = mean(recent) { actualExpenses(context.data.transactions, it) }
        val incomeBefore = mean(earlier) { actualIncome(context.data.transactions, it) }
        val incomeNow = mean(recent) { actualIncome(context.data.transactions, it) }

        if (expenseBefore > 0 && incomeBefore > 0) {
            val expenseGrowth = (expenseNow - expenseBefore) / expenseBefore
            val incomeGrowth = (incomeNow - incomeBefore) / incomeBefore
            val gap = expenseGrowth - incomeGrowth

            if (gap >= Thresholds.lifestyleGap.value) {
                context.add(
                    Advice(
                        id = "lifestyle",
                        method = MethodId.LIFESTYLE,
                        priority = AdvicePriority.REVIEW,
                        fact = "Son 3 ayda xərcləriniz ${percent(expenseGrowth)} artıb, " +
                            "gəliriniz ${percent(incomeGrowth)} — fərq ${percent(gap)}.",
                        suggestion = "Artımın hansı kateqoriyalardan gəldiyini nəzərdən " +
                            "keçirməyə dəyər.",
                        materiality = round2(expenseNow - expenseBefore),
                    ),
                )
            }
        }
    }
}

/* --- composition of the month ------------------------------------- */

private val concentration: Rule = { context ->
    val health = context.health
    if (health.expenses > 0) {
        val top = categoryRows(context.data, context.month)
            .filter { it.actual > 0 }
            .maxByOrNull { it.actual }

        if (top != null) {
            val share = top.actual / health.expenses
            if (share >= Thresholds.concentrationShare.value) {
                context.add(
                    Advice(
                        id = "concentration",
                        method = MethodId.CONCENTRATION,
                        priority = AdvicePriority.REVIEW,
                        fact = "${top.category} bu ayın xərclərinin ${percent(share)}-ni " +
                            "təşkil edir — ${formatAZN(top.actual)}.",
                        materiality = top.actual,
                        subject = top.category,
                        meter = AdviceMeter(value = share, label = "ümumi xərcdəki payı"),
                    ),
                )
            }
        }
    }
}

private val unexpected: Rule = { context ->
    val health = context.health
    if (health.plannedExpenses <= 0 || health.expenses <= 0) {
        context.skip(Unavailable(MethodId.UNEXPECTED, "Bu ay üçün plan və ya xərc yoxdur"))
    } else {
        val beyond = categoryRows(context.data, context.month)
            .sumOf { max(it.actual - it.planned, 0.0) }
        val share = beyond / health.expenses

        if (share >= Thresholds.unexpectedShare.value) {
            context.add(
                Advice(
                    id = "unexpected",
                    method = MethodId.UNEXPECTED,
                    priority = AdvicePriority.REVIEW,
                    fact = "Xərclərinizin ${percent(share)}-i planın kənarındadır — " +
                        "${formatAZN(round2(beyond))}.",
                    materiality = round2(beyond),
                    meter = AdviceMeter(value = share, label = "plandan kənar hissə"),
                ),
            )
        }
    }
}

private val recurringBurden: Rule = { context ->
    val health = context.health
    if (health.income <= 0) {
        context.skip(Unavailable(MethodId.RECURRING, "Bu ay gəlir qeyd edilməyib"))
    } else {
        val previous = shiftMonth(context.month, -1)
        val earlier = context.data.budgetLines
            .filter { it.month == previous }
            .map { normalise(it.description) }
            .toSet()
        val recurring = context.data.budgetLines.filter { line ->
            line.month == context.month &&
                line.planned > 0 &&
                earlier.contains(normalise(line.description))
        }

        if (recurring.isNotEmpty()) {
            val total = sumOf(recurring.map { it.planned })
            val share = total / health.income

            if (share >= Thresholds.recurringShare.value) {
                context.add(
                    Advice(
                        id = "recurring",
                        method = MethodId.RECURRING,
                        priority = AdvicePriority.REVIEW,
                        fact = "Hər ay təkrarlanan ${recurring.size} planlaşdırılmış xərc " +
                            "gəlirinizin ${percent(share)}-ni tutur — ${formatAZN(total)}.",
                        materiality = total,
                        meter = AdviceMeter(value = min(share, 1.0), label = "gəlirdəki payı"),
                    ),
                )
            }
        }
    }
}

/* --- the plan on its own terms ------------------------------------ */

private val zeroBased: Rule = { context ->
    val health = context.health
    if (health.plannedIncome <= 0) {
        context.skip(Unavailable(MethodId.ZERO_BASED, "Planlaşdırılan gəlir qeyd edilməyib"))
    } else {
        val unallocated = round2(health.plannedIncome - health.plannedExpenses)

        if (abs(unallocated) < Thresholds.materialAmount.value) {
            context.add(
                Advice(
                    id = "zero-based",
                    method = MethodId.ZERO_BASED,
                    priority = AdvicePriority.GOOD,
                    fact = "Planlaşdırılan gəlirin demək olar hamısının təyinatı var " +
                        "(fərq ${formatAZN(abs(unallocated))}).",
                    materiality = abs(unallocated),
                ),
            )
        } else {
            context.add(
                Advice(
                    id = "zero-based",
                    method = MethodId.ZERO_BASED,
                    priority = if (unallocated < 0) {
                        AdvicePriority.ATTENTION
                    } else {
                        AdvicePriority.REVIEW
                    },
                    fact = if (unallocated < 0) {
                        "Planınız qazanmağı gözlədiyinizdən ${formatAZN(-unallocated)} " +
                            "çox xərcləyir."
                    } else {
                        "Planlaşdırılan gəlirin ${formatAZN(unallocated)}-i heç bir xərcə " +
                            "və ya məqsədə təyin edilməyib."
                    },
                    suggestion = if (unallocated > 0) {
                        "Sıfır-baza yanaşmasında hər manatın təyinatı olur — bu məbləğ üçün " +
                            "də bir təyinat düşünməyə dəyər."
                    } else {
                        null
                    },
                    materiality = abs(unallocated),
                ),
            )
        }
    }
}

private val sinkingFunds: Rule = { context ->
    val future = context.data.budgetLines.filter { it.month > context.month && it.planned > 0 }

    if (future.isEmpty()) {
        context.skip(
            Unavailable(MethodId.SINKING_FUND, "Gələcək aylara planlaşdırılmış xərc yoxdur"),
        )
    } else {
        for (line in future) {
            val away = monthsBetween(context.month, line.month)
            if (away <= 0) continue
            val perMonth = round2(line.planned / away)
            if (perMonth < Thresholds.materialAmount.value) continue

            context.add(
                Advice(
                    id = "sinking-${line.id}",
                    method = MethodId.SINKING_FUND,
                    priority = AdvicePriority.REVIEW,
                    fact = "${line.description} — ${formatMonth(line.month)} üçün " +
                        "${formatAZN(line.planned)} planlaşdırılıb ($away ay sonra).",
                    suggestion = "İndidən ayda ${formatAZN(perMonth)} ayırsanız, " +
                        "vaxtına yığılmış olar.",
                    materiality = line.planned,
                ),
            )
        }
    }
}

private val RULES: List<Rule> = listOf(
    spendingRatio,
    retained,
    retainedTrend,
    totalVariance,
    categoryVariance,
    repeatedOverrun,
    anomaly,
    trend,
    lifestyle,
    concentration,
    unexpected,
    recurringBurden,
    zeroBased,
    sinkingFunds,
)

/* ------------------------------------------------------------------ *
 * Helpers
 * ------------------------------------------------------------------ */

private data class CategoryRow(val category: String, val actual: Double, val planned: Double)

private fun categoryRows(data: FinanceData, month: MonthKey): List<CategoryRow> {
    val names = LinkedHashSet<String>()
    for (transaction in data.transactions) {
        if (transaction.type == TransactionType.EXPENSE && monthOf(transaction.date) == month) {
            names.add(transaction.category)
        }
    }
    for (line in data.budgetLines) {
        if (line.month == month) names.add(line.category)
    }

    return names.map { category ->
        CategoryRow(category, actualFor(data, month, category), plannedFor(data, month, category))
    }
}

private fun actualFor(data: FinanceData, month: MonthKey, category: String): Double = sumOf(
    data.transactions
        .filter {
            it.type == TransactionType.EXPENSE &&
                it.category == category &&
                monthOf(it.date) == month
        }
        .map { it.amount },
)

private fun plannedFor(data: FinanceData, month: MonthKey, category: String): Double = sumOf(
    data.budgetLines
        .filter { it.month == month && it.category == category }
        .map { it.planned },
)

/** [count] months immediately before [month], oldest first. */
private fun previousMonths(month: MonthKey, count: Int): List<MonthKey> =
    (0 until count).map { shiftMonth(month, it - count) }

private fun monthsBetween(from: MonthKey, to: MonthKey): Int {
    fun key(month: MonthKey): Int {
        val (year, index) = month.split("-").map { it.toInt() }
        return year * 12 + (index - 1)
    }
    return key(to) - key(from)
}

fun median(values: List<Double>): Double {
    if (values.isEmpty()) return 0.0
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 0) {
        (sorted[middle - 1] + sorted[middle]) / 2
    } else {
        sorted[middle]
    }
}

/**
 * Distance from the median in robust units.
 *
 * `1.4826` scales the median absolute deviation so that, for normally
 * distributed data, it estimates the same spread the standard deviation would
 * — which is what makes a cutoff of 2.5 comparable to the familiar one.
 *
 * A history that never varied has a spread of zero, which leaves the ratio
 * undefined. Declining to judge it would hide exactly the case the rule is
 * for — four months at 150 and then 350 — so an unprecedented value scores as
 * unbounded, and the caller's materiality floor is what keeps a 20 qəpik
 * wobble off the screen.
 */
fun robustScore(value: Double, history: List<Double>): Double? {
    if (history.isEmpty()) return null
    val centre = median(history)
    val deviation = median(history.map { abs(it - centre) })
    if (deviation == 0.0) {
        return if (value == centre) null else Double.POSITIVE_INFINITY
    }
    return abs(value - centre) / (1.4826 * deviation)
}

private fun normalise(value: String): String =
    value.trim().lowercase().replace(Regex("\\s+"), " ")
