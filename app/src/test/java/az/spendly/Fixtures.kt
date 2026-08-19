/**
 * The "Oktyabr hesabat" spreadsheet, as test data.
 *
 * The app itself hands out nothing: an account starts with no categories and
 * no plan, because those are the shape somebody gives their own money. But the
 * sheet is still the only independently-known-good set of figures this project
 * has, so it lives on here — the fidelity tests check the calculations against
 * the totals a person once worked out by hand.
 */
package az.spendly

import az.spendly.domain.BudgetLine
import az.spendly.domain.CategoryDef
import az.spendly.domain.MonthKey
import az.spendly.domain.TransactionType

/** The data-validation list on 'Aylıq rasxod'!C3:C25, translated. */
private val EXPENSE_CATEGORIES = listOf(
    "Kreditlər",
    "Ərzaq",
    "Nəqliyyat",
    "Şəxsi gigiyena",
    "Telefon və internet",
    "Təhsil",
    "İdman",
    "Əyləncə",
    "Hədiyyə və xeyriyyə",
    "Əlavə xərclər",
    "Avtomobil kartı",
)

/** 'BÜDCƏ İCMALI'!B11 and B12 — the sheet's two income rows. */
private val INCOME_CATEGORIES = listOf("Maaş", "Əlavə gəlir")

/**
 * The recurring plan carried over from 'Aylıq rasxod' (October report).
 * Planned total is 1,142.00 ₼, matching 'BÜDCƏ İCMALI'!F11.
 *
 * The sheet's empty placeholder rows (category only, no description or amount)
 * are omitted — they carry no information.
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
const val PLANNED_SALARY = 990.0

/** The sheet's 16 budget lines, applied to one month. */
fun sheetPlan(month: MonthKey): List<BudgetLine> =
    PLAN.mapIndexed { index, (description, category, planned) ->
        BudgetLine("$month-plan-$index", month, description, category, planned)
    }

/** A category list covering both sides of the sheet's ledger. */
fun sheetCategories(): List<CategoryDef> {
    fun of(names: List<String>, type: TransactionType) =
        names.mapIndexed { index, name -> CategoryDef("cat-${type.wire}-$index", name, type) }

    return of(EXPENSE_CATEGORIES, TransactionType.EXPENSE) +
        of(INCOME_CATEGORIES, TransactionType.INCOME)
}
