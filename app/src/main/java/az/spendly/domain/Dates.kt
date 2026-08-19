package az.spendly.domain

import java.time.LocalDate

private val MONTH_NAMES = listOf(
    "Yanvar", "Fevral", "Mart", "Aprel", "May", "İyun",
    "İyul", "Avqust", "Sentyabr", "Oktyabr", "Noyabr", "Dekabr",
)

/**
 * Written out rather than sliced from the full names: the first three letters
 * of İyun and İyul are both "İyu", which would make June and July identical on
 * a chart axis.
 */
private val MONTH_SHORT = listOf(
    "Yan", "Fev", "Mar", "Apr", "May", "İyn",
    "İyl", "Avq", "Sen", "Okt", "Noy", "Dek",
)

/** Monday first, matching how the week is read here. */
private val WEEKDAY_SHORT = listOf("B.e", "Ç.a", "Ç", "C.a", "C", "Ş", "B")

/** `YYYY-MM-DD` for today, in the device's own timezone (never UTC-shifted). */
fun today(): DateKey = LocalDate.now().let { toDateKey(it.year, it.monthValue, it.dayOfMonth) }

fun toDateKey(year: Int, month: Int, day: Int): DateKey =
    "%04d-%02d-%02d".format(year, month, day)

fun currentMonth(): MonthKey = today().substring(0, 7)

/** `2025-10-14` -> `2025-10`. */
fun monthOf(date: DateKey): MonthKey = date.substring(0, 7)

/** Number of days in a month, e.g. `2024-02` -> 29. */
fun daysInMonth(month: MonthKey): Int {
    val (year, monthIndex) = month.split("-").map { it.toInt() }
    return LocalDate.of(year, monthIndex, 1).lengthOfMonth()
}

/** `2025-10` -> `Okt`. */
fun formatMonthShort(month: MonthKey): String {
    val monthIndex = month.split("-")[1].toIntOrNull() ?: return month
    return MONTH_SHORT.getOrNull(monthIndex - 1) ?: month
}

/** `2025-10` -> `Oktyabr 2025`. */
fun formatMonth(month: MonthKey): String {
    val parts = month.split("-")
    if (parts.size < 2) return month
    val year = parts[0]
    val monthIndex = parts[1].toIntOrNull() ?: return month
    val name = MONTH_NAMES.getOrNull(monthIndex - 1) ?: return month
    return "$name $year"
}

/** `2025-10-14` -> `14 Okt`. */
fun formatDayShort(date: DateKey): String {
    val parts = date.split("-")
    if (parts.size < 3) return date
    val month = parts[1].toIntOrNull() ?: return date
    val day = parts[2].toIntOrNull() ?: return date
    val name = MONTH_SHORT.getOrNull(month - 1) ?: return date
    return "$day $name"
}

/** Day of the week, 0 = Monday. */
fun weekdayOf(date: DateKey): Int {
    val (year, month, day) = date.split("-").map { it.toInt() }
    // DayOfWeek.value is 1 = Monday, so this is already Monday-first.
    return LocalDate.of(year, month, day).dayOfWeek.value - 1
}

/** `0` -> `B.e`. */
fun formatWeekdayShort(weekday: Int): String = WEEKDAY_SHORT.getOrNull(weekday) ?: ""

/** Shift a month key by [delta] months. Handles year boundaries. */
fun shiftMonth(month: MonthKey, delta: Int): MonthKey {
    val (year, monthIndex) = month.split("-").map { it.toInt() }
    val zeroBased = year * 12 + (monthIndex - 1) + delta
    return "%d-%02d".format(Math.floorDiv(zeroBased, 12), Math.floorMod(zeroBased, 12) + 1)
}

/**
 * Validate a `YYYY-MM-DD` string, rejecting both malformed strings and
 * calendar-impossible dates such as `2025-02-30`.
 */
fun isValidDate(value: String): Boolean {
    if (!Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(value)) return false
    val (year, month, day) = value.split("-").map { it.toInt() }
    if (month < 1 || month > 12 || day < 1) return false
    return day <= LocalDate.of(year, month, 1).lengthOfMonth()
}
