package az.spendly.domain

data class TransactionInput(
    val date: String = "",
    val type: TransactionType = TransactionType.EXPENSE,
    val category: String = "",
    val description: String = "",
    val amount: String = "",
    val note: String = "",
)

data class FieldErrors(
    val date: String? = null,
    val category: String? = null,
    val description: String? = null,
    val amount: String? = null,
) {
    val any: Boolean
        get() = date != null || category != null || description != null || amount != null
}

/** Largest amount accepted, so a mistyped figure cannot corrupt the history. */
private const val MAX_AMOUNT = 100_000_000.0

/**
 * [allowed] is the user's own category list for the chosen type. It is passed
 * in rather than read from a constant because categories are editable, and a
 * validator working from a stale hard-coded list would reject a category the
 * user had just created.
 */
fun validateTransaction(input: TransactionInput, allowed: List<String>): FieldErrors {
    val date = when {
        input.date.isBlank() -> "Tarix seçin"
        !isValidDate(input.date) -> "Belə tarix yoxdur"
        else -> null
    }

    val description = if (input.description.isBlank()) "Qısa təsvir yazın" else null
    val category = if (!allowed.contains(input.category)) "Kateqoriya seçin" else null

    val parsed = parseAmount(input.amount)
    val amount = when {
        parsed == null -> "Məbləği daxil edin"
        // The sheet only ever holds positive figures; direction comes from the type.
        parsed <= 0 -> "Məbləğ sıfırdan böyük olmalıdır"
        parsed > MAX_AMOUNT -> "Bu məbləğ həddindən artıq böyükdür"
        else -> null
    }

    return FieldErrors(date = date, category = category, description = description, amount = amount)
}
