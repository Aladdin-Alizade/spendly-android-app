/**
 * Domain model, derived from the "Oktyabr hesabat" spreadsheet.
 *
 * Sheet -> model mapping:
 *   'Aylıq rasxod'   row      -> BudgetLine   (description, category, planned amount)
 *   'Aylıq rasxod'   column E -> derived from Transactions of type 'expense'
 *   'BÜDCƏ İCMALI'   C11/C12  -> IncomePlan   (a planned amount per income
 *                                               category — the sheet had two
 *                                               fixed rows, this has one per
 *                                               category the user keeps)
 *   'BÜDCƏ İCMALI'   D11/D12  -> derived from Transactions of type 'income'
 */
package az.spendly.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Calendar month key, `YYYY-MM`. One month == one spreadsheet file. */
typealias MonthKey = String

/** ISO calendar day, `YYYY-MM-DD`. */
typealias DateKey = String

@Serializable
enum class TransactionType {
    @SerialName("income")
    INCOME,

    @SerialName("expense")
    EXPENSE;

    /** The wire value, which is what every stored row and every Supabase
     *  column already holds. */
    val wire: String get() = if (this == INCOME) "income" else "expense"

    companion object {
        fun of(value: String?): TransactionType =
            if (value == "income") INCOME else EXPENSE
    }
}

/**
 * The expense categories an account starts with — one for each entry of the
 * data-validation list on 'Aylıq rasxod'!C3:C25, translated into Azerbaijani.
 *
 * These are a starting set, not the set. Categories are data: they live in
 * [FinanceData.categories] and can be added to, renamed and removed. This list
 * is only what a new account is seeded with.
 */
val EXPENSE_CATEGORIES = listOf(
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

/**
 * The income categories an account starts with. The sheet modelled income as
 * exactly two rows ('BÜDCƏ İCMALI'!B11 and B12), and the planned side of
 * income still has exactly those two fields — so these two are seeded, and
 * anything added beyond them is reported with no planned amount.
 */
val INCOME_CATEGORIES = listOf("Maaş", "Əlavə gəlir")

/**
 * A category is referenced by name, the way the spreadsheet did it and the way
 * every stored row already does. The id exists so a rename is an edit to one
 * record rather than a new category, and so the name can change without the
 * history losing track of which category it was.
 */
@Serializable
data class CategoryDef(
    val id: String,
    val name: String,
    val type: TransactionType,
)

/** The categories a new account starts with. Ids are stable, so seeding the
 *  same account twice cannot produce duplicates. */
fun defaultCategories(): List<CategoryDef> =
    EXPENSE_CATEGORIES.mapIndexed { index, name ->
        CategoryDef("cat-expense-$index", name, TransactionType.EXPENSE)
    } + INCOME_CATEGORIES.mapIndexed { index, name ->
        CategoryDef("cat-income-$index", name, TransactionType.INCOME)
    }

/**
 * Categories were stored in Russian before the app was translated. Data saved
 * then is rewritten on load, so an existing transaction keeps its category
 * rather than falling out of every total.
 */
private val LEGACY_CATEGORIES = mapOf(
    "Кредиты" to "Kreditlər",
    "Еда" to "Ərzaq",
    "Транспорт" to "Nəqliyyat",
    "Транспорт " to "Nəqliyyat",
    "Предметы личной гигиены" to "Şəxsi gigiyena",
    "Для телефона" to "Telefon və internet",
    "Обучение" to "Təhsil",
    "Спорт" to "İdman",
    "Развлечения" to "Əyləncə",
    "Подарки и благотворительность" to "Hədiyyə və xeyriyyə",
    "Лишние затраты" to "Əlavə xərclər",
    "Карта для машина" to "Avtomobil kartı",
    "Зарплата" to "Maaş",
    "Дополнительный доход" to "Əlavə gəlir",
)

/** Map a stored category onto the current set, leaving unknown ones untouched. */
fun migrateCategory(value: String): String = LEGACY_CATEGORIES[value] ?: value

/** A real movement of money. Replaces the hand-totalled column E. */
@Serializable
data class Transaction(
    val id: String,
    val date: DateKey,
    val type: TransactionType,
    val category: String,
    val description: String,
    /** Always stored positive. Direction is carried by [type]. */
    val amount: Double,
    val note: String? = null,
)

/** One planned expense row of 'Aylıq rasxod' (columns B, C, D). */
@Serializable
data class BudgetLine(
    val id: String,
    val month: MonthKey,
    val description: String,
    val category: String,
    /** Column D, "Запланированные затраты". Always >= 0. */
    val planned: Double,
)

/**
 * The planned side of income, from 'BÜDCƏ İCMALI'!C11:C12.
 *
 * The sheet had exactly two rows for this, so the model used to have exactly
 * two fields. Income categories are the user's own now, so the plan is a
 * figure per category instead — keyed by category name, the way every other
 * reference to a category works.
 */
@Serializable
data class IncomePlan(
    val month: MonthKey,
    /** Planned amount per income category name. Absent means nothing planned. */
    val amounts: Map<String, Double> = emptyMap(),
)

/** Everything planned for a month, across its income categories. */
fun plannedIncomeOf(plan: IncomePlan?): Double =
    plan?.amounts?.values?.sum() ?: 0.0

@Serializable
data class FinanceData(
    val transactions: List<Transaction> = emptyList(),
    val budgetLines: List<BudgetLine> = emptyList(),
    val incomePlans: List<IncomePlan> = emptyList(),
    val categories: List<CategoryDef> = emptyList(),
)

val emptyData = FinanceData()

/**
 * Read an income plan saved in either shape.
 *
 * Rows written before income categories were editable carry `salary` and
 * `additional`; those two figures belong to the two categories an account is
 * seeded with, so that is where they are put.
 */
fun migrateIncomePlan(
    month: MonthKey,
    amounts: Map<String, Double>?,
    salary: Double = 0.0,
    additional: Double = 0.0,
): IncomePlan {
    if (!amounts.isNullOrEmpty()) {
        return IncomePlan(
            month = month,
            amounts = amounts.entries.associate { (category, amount) ->
                migrateCategory(category) to amount
            },
        )
    }

    val migrated = buildMap {
        if (salary > 0) put(INCOME_CATEGORIES[0], salary)
        if (additional > 0) put(INCOME_CATEGORIES[1], additional)
    }
    return IncomePlan(month, migrated)
}

/**
 * Defend against partially-shaped or hand-edited stored data, and bring
 * categories saved before the app was translated onto the current names.
 */
fun normaliseData(data: FinanceData): FinanceData = FinanceData(
    transactions = data.transactions.map { it.copy(category = migrateCategory(it.category)) },
    budgetLines = data.budgetLines.map { it.copy(category = migrateCategory(it.category)) },
    incomePlans = data.incomePlans.map { migrateIncomePlan(it.month, it.amounts) },
    // A snapshot saved before categories were editable has none stored, so it
    // is given the starting set rather than an app with no categories at all.
    categories = if (data.categories.isNotEmpty()) {
        data.categories.map { it.copy(name = migrateCategory(it.name)) }
    } else {
        defaultCategories()
    },
)
