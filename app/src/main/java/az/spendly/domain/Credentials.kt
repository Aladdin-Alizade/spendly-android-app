/**
 * Sign-in form validation.
 *
 * Deliberately thin: the only checks here are the ones that can be made
 * without asking the server, so the form can answer instantly and the server
 * stays the authority on everything else (address already taken, wrong
 * password, a stricter password policy).
 */
package az.spendly.domain

data class CredentialErrors(val email: String? = null, val password: String? = null) {
    val any: Boolean get() = email != null || password != null
}

enum class AuthMode { SIGN_IN, SIGN_UP }

/**
 * Supabase rejects anything shorter than six characters by default, so the
 * form says so before a round trip rather than after one.
 * https://supabase.com/docs/guides/auth/passwords
 */
const val MIN_PASSWORD_LENGTH = 6

/** Deliberately loose: an address is either accepted by the server or it is
 *  not, and a strict pattern here only ever rejects valid addresses. */
private val EMAIL = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

fun validateCredentials(email: String, password: String, mode: AuthMode): CredentialErrors {
    val trimmed = email.trim()
    val emailError = when {
        trimmed.isEmpty() -> "E-poçt ünvanını daxil edin"
        !EMAIL.matches(trimmed) -> "E-poçt ünvanı düzgün deyil"
        else -> null
    }

    val passwordError = when {
        password.isEmpty() -> "Şifrəni daxil edin"
        // Only on sign-up: an existing password that predates this rule must
        // still be able to sign in.
        mode == AuthMode.SIGN_UP && password.length < MIN_PASSWORD_LENGTH ->
            "Şifrə ən azı $MIN_PASSWORD_LENGTH simvol olmalıdır"
        else -> null
    }

    return CredentialErrors(emailError, passwordError)
}

data class PasswordChangeInput(
    val current: String = "",
    val next: String = "",
    val repeat: String = "",
)

data class PasswordChangeErrors(
    val current: String? = null,
    val next: String? = null,
    val repeat: String? = null,
) {
    val any: Boolean get() = current != null || next != null || repeat != null
}

/**
 * Changing a password, checked as far as it can be without the server.
 *
 * The current password is asked for rather than taken on trust from the open
 * session: an unattended phone is the ordinary case, and a session alone
 * should not be enough to lock its owner out of their own account. The server
 * is what actually verifies it — this only catches the empty field.
 */
fun validatePasswordChange(input: PasswordChangeInput): PasswordChangeErrors {
    val current = if (input.current.isEmpty()) "Cari şifrəni daxil edin" else null

    val next = when {
        input.next.isEmpty() -> "Yeni şifrəni daxil edin"
        input.next.length < MIN_PASSWORD_LENGTH ->
            "Yeni şifrə ən azı $MIN_PASSWORD_LENGTH simvol olmalıdır"
        input.next == input.current -> "Yeni şifrə köhnəsindən fərqli olmalıdır"
        else -> null
    }

    val repeat = if (input.repeat != input.next) "Şifrələr uyğun gəlmir" else null

    return PasswordChangeErrors(current, next, repeat)
}

/**
 * Setting a new password after a reset link.
 *
 * There is no current password to ask for here — the link out of the mailbox
 * is what stands in for it, which is the whole point of the flow.
 */
fun validateNewPassword(next: String, repeat: String): PasswordChangeErrors {
    val nextError = when {
        next.isEmpty() -> "Yeni şifrəni daxil edin"
        next.length < MIN_PASSWORD_LENGTH ->
            "Yeni şifrə ən azı $MIN_PASSWORD_LENGTH simvol olmalıdır"
        else -> null
    }
    val repeatError = if (repeat != next) "Şifrələr uyğun gəlmir" else null
    return PasswordChangeErrors(next = nextError, repeat = repeatError)
}

/**
 * Supabase's auth errors, in the user's language. Anything unrecognised is
 * passed through rather than replaced with a vague sentence.
 */
fun authErrorMessage(message: String): String = when {
    contains(message, "invalid login credentials") ->
        "E-poçt və ya şifrə yanlışdır"
    contains(message, "user already registered", "already exists") ->
        "Bu e-poçt ünvanı ilə hesab artıq var — daxil olun"
    contains(message, "email not confirmed") ->
        "E-poçt ünvanı təsdiqlənməyib. Gələn məktubdakı linki açın."
    contains(message, "password should be at least") ->
        "Şifrə ən azı $MIN_PASSWORD_LENGTH simvol olmalıdır"
    contains(message, "new password should be different", "same as the old password") ->
        "Yeni şifrə köhnəsindən fərqli olmalıdır"
    contains(message, "expired", "invalid token", "invalid jwt", "otp_expired", "link is invalid") ->
        "Link vaxtı keçib və ya artıq istifadə olunub. Yenidən sıfırlama tələb edin."
    /*
     * Supabase's built-in mail service allows a handful of messages an hour,
     * and it counts sign-up confirmations, reset links and everything else
     * together. Calling that "too many attempts" blames the person for typing
     * their password once — the limit is on the mail, not on them.
     */
    contains(message, "email rate limit", "over_email_send_rate_limit") ->
        "Supabase-in e-poçt limiti dolub — pulsuz xidmət saatda yalnız bir neçə " +
            "məktub göndərir. Bir saat gözləyin, təsdiqi söndürün, və ya öz SMTP-nizi qoşun."

    WAIT_SECONDS.find(message) != null ->
        "Növbəti cəhd üçün ${WAIT_SECONDS.find(message)!!.groupValues[1]} saniyə " +
            "gözləmək lazımdır."

    contains(message, "rate limit", "too many requests") ->
        "Çox sayda sorğu göndərilib. Bir az gözləyin."
    contains(message, "signups not allowed", "signup is disabled") ->
        "Qeydiyyat Supabase panelində bağlıdır (Authentication → Sign In / Providers)."
    else -> message
}

/** How long the server itself said to wait, when it says so. */
private val WAIT_SECONDS =
    Regex("you can only request this after (\\d+) seconds?", RegexOption.IGNORE_CASE)

private fun contains(message: String, vararg needles: String): Boolean {
    val lower = message.lowercase()
    return needles.any { lower.contains(it) }
}
