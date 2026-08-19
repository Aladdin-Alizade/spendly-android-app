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
    contains(message, "rate limit", "too many requests") ->
        "Çox sayda cəhd oldu. Bir az gözləyin."
    contains(message, "signups not allowed", "signup is disabled") ->
        "Qeydiyyat Supabase panelində bağlıdır (Authentication → Sign In / Providers)."
    else -> message
}

private fun contains(message: String, vararg needles: String): Boolean {
    val lower = message.lowercase()
    return needles.any { lower.contains(it) }
}
