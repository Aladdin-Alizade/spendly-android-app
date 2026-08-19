package az.spendly.domain

/**
 * The recurring plan carried over from 'Aylıq rasxod' (October report).
 * Planned total is 1,142.00 ₼, matching 'BÜDCƏ İCMALI'!F11.
 *
 * The sheet's empty placeholder rows (category only, no description or amount)
 * are omitted — they carry no information. Categories are copied verbatim,
 * including the ones that look surprising, because they are the user's own.
 */
private val PLAN = listOf(
    Triple("Ev kirəsi", "Əlavə xərclər", 230.0),
    Triple("Adi kredit kartı", "Kreditlər", 220.0),
    Triple("Umiko kredit kartı", "Kreditlər", 35.0),
    Triple("Nağd kredit kartı", "Kreditlər", 0.0),
    Triple("Qızıl krediti", "Kreditlər", 300.0),
    Triple("İnternet", "Telefon və internet", 15.0),
    Triple("Nəqliyyat (İş)", "Nəqliyyat", 25.0),
    Triple("Nəqliyyat (Kurs)", "Təhsil", 12.0),
    Triple("Saç", "Şəxsi gigiyena", 20.0),
    Triple("Lazer", "Şəxsi gigiyena", 10.0),
    Triple("Geyim və ayaqqabı", "Şəxsi gigiyena", 35.0),
    Triple("Ev üçün ərzaq", "Ərzaq", 100.0),
    Triple("Özüm üçün ərzaq", "Ərzaq", 0.0),
    Triple("İdman aylıq", "İdman", 40.0),
    Triple("Avtomobil icarəsi", "Əlavə xərclər", 50.0),
    Triple("Ad günləri", "Əlavə xərclər", 50.0),
)

/** 'BÜDCƏ İCMALI'!C11 — planned salary. */
private const val PLANNED_SALARY = 990.0

fun budgetTemplate(month: MonthKey): List<BudgetLine> =
    PLAN.mapIndexed { index, (description, category, planned) ->
        BudgetLine(
            id = "$month-seed-$index",
            month = month,
            description = description,
            category = category,
            planned = planned,
        )
    }

/** First-run data: the recurring plan, applied to the current month. */
fun seedData(): FinanceData {
    val month = currentMonth()
    return FinanceData(
        transactions = emptyList(),
        budgetLines = budgetTemplate(month),
        incomePlans = listOf(IncomePlan(month, mapOf(INCOME_CATEGORIES[0] to PLANNED_SALARY))),
        categories = defaultCategories(),
    )
}
