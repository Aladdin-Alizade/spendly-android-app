/**
 * Backend errors, translated into the step that fixes them.
 *
 * Every one of these is something that cannot be done from inside the app —
 * it needs the Supabase dashboard — so echoing the raw API message at someone
 * who has just connected a project tells them nothing they can act on.
 *
 * Order matters: the first match wins, so the specific patterns come before
 * the general ones.
 */
package az.spendly.data

private val SETUP_HINTS: List<Pair<Regex, String>> = listOf(
    // Sign-up is off by default on some projects, which makes the register
    // form fail with nothing the user can do about it from inside the app.
    Regex("signups not allowed|signup is disabled", RegexOption.IGNORE_CASE) to
        "Qeydiyyat Supabase panelində bağlıdır. Authentication → Sign In / Providers " +
        "bölməsindən Email provayderini və qeydiyyatı aktiv edin.",

    Regex("hesaba daxil olunmayıb|not signed in|JWT|session|sessiya", RegexOption.IGNORE_CASE) to
        "Sessiya bitib. Yenidən daxil olun.",

    // 42703 is Postgres, PGRST204 is PostgREST's schema cache, 42P10 is an
    // upsert naming a key the table does not have yet. All three mean the
    // table is there but is an older version of it than the app expects.
    Regex(
        "42703|42P10|PGRST204|column .* does not exist|" +
            "could not find the .* column|no unique or exclusion constraint",
        RegexOption.IGNORE_CASE,
    ) to
        "Verilənlər bazası köhnə quruluşdadır. Supabase SQL redaktorunda " +
        "supabase/schema.sql faylını yenidən işə salın — təkrar işə salmaq təhlükəsizdir.",

    Regex(
        "could not find the table|PGRST205|relation .* does not exist|does not exist",
        RegexOption.IGNORE_CASE,
    ) to
        "Cədvəlləri yaratmaq üçün Supabase SQL redaktorunda supabase/schema.sql faylını işə salın.",

    Regex("row-level security|RLS|permission denied", RegexOption.IGNORE_CASE) to
        "Sətir səviyyəsində icazələr tətbiq olunmayıb. supabase/schema.sql faylını yenidən işə salın.",

    Regex(
        "failed to connect|unable to resolve host|timeout|network|UnknownHost",
        RegexOption.IGNORE_CASE,
    ) to
        "İnternet bağlantınızı və local.properties faylındakı SUPABASE_URL dəyərini yoxlayın.",
)

/**
 * Everything useful out of a thrown value.
 *
 * Returns an empty string when there is genuinely nothing to report, so the
 * caller can fall back to saying only that the write failed.
 */
fun describeError(cause: Throwable?): String {
    if (cause == null) return ""
    val message = cause.message?.trim().orEmpty()
    if (message.isNotEmpty()) return message
    return cause::class.simpleName.orEmpty()
}

/** The setup step that fixes [message], or null when none of them do. */
fun setupHint(message: String?): String? {
    if (message.isNullOrBlank()) return null
    return SETUP_HINTS.firstOrNull { (pattern, _) -> pattern.containsMatchIn(message) }?.second
}
