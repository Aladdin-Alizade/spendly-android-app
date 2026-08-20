/**
 * Sign-in form validation, carried over from the web app's suite.
 */
package az.spendly

import az.spendly.domain.AuthMode
import az.spendly.domain.MIN_PASSWORD_LENGTH
import az.spendly.domain.PasswordChangeInput
import az.spendly.domain.authErrorMessage
import az.spendly.domain.validateCredentials
import az.spendly.domain.validateNewPassword
import az.spendly.domain.validatePasswordChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val EMAIL = "a@b.com"
private const val PASSWORD = "correct-horse"

class ValidateCredentialsTest {

    @Test
    fun `accepts a well-formed pair in both modes`() {
        assertFalse(validateCredentials(EMAIL, PASSWORD, AuthMode.SIGN_IN).any)
        assertFalse(validateCredentials(EMAIL, PASSWORD, AuthMode.SIGN_UP).any)
    }

    @Test
    fun `requires both fields`() {
        val errors = validateCredentials("", "", AuthMode.SIGN_IN)
        assertNotNull(errors.email)
        assertNotNull(errors.password)
    }

    @Test
    fun `rejects an address that is not one`() {
        assertNotNull(validateCredentials("not-an-email", PASSWORD, AuthMode.SIGN_IN).email)
        assertNotNull(validateCredentials("a@b", PASSWORD, AuthMode.SIGN_IN).email)
        assertNull(validateCredentials(" a@b.com ", PASSWORD, AuthMode.SIGN_IN).email)
    }

    @Test
    fun `enforces the password length only when creating an account`() {
        val short = "x".repeat(MIN_PASSWORD_LENGTH - 1)
        assertNotNull(validateCredentials(EMAIL, short, AuthMode.SIGN_UP).password)
        // An existing password predating this rule still has to be able to sign in.
        assertNull(validateCredentials(EMAIL, short, AuthMode.SIGN_IN).password)
    }
}

class AuthErrorMessageTest {

    @Test
    fun `translates the errors a user actually hits`() {
        assertTrue(authErrorMessage("Invalid login credentials").contains("yanlışdır"))
        assertTrue(authErrorMessage("User already registered").contains("artıq var"))
        assertTrue(authErrorMessage("Email not confirmed").contains("təsdiqlənməyib"))
        assertTrue(
            authErrorMessage("Signups not allowed for this instance").contains("Qeydiyyat"),
        )
    }

    @Test
    fun `passes an unrecognised message through rather than hiding it`() {
        assertEquals("some unmapped failure", authErrorMessage("some unmapped failure"))
    }
}

class PasswordChangeTest {

    private val valid = PasswordChangeInput(
        current = "old-one",
        next = "brand-new",
        repeat = "brand-new",
    )

    @Test
    fun `accepts a well-formed change`() {
        assertFalse(validatePasswordChange(valid).any)
    }

    @Test
    fun `asks for the current password rather than trusting the session`() {
        // An unattended phone has a session too; a session alone should not be
        // enough to lock somebody out of their own account.
        assertNotNull(validatePasswordChange(valid.copy(current = "")).current)
    }

    @Test
    fun `holds the new password to the same length rule as sign-up`() {
        val short = "x".repeat(MIN_PASSWORD_LENGTH - 1)
        assertNotNull(validatePasswordChange(valid.copy(next = short, repeat = short)).next)
    }

    @Test
    fun `rejects a new password identical to the old one`() {
        val same = PasswordChangeInput("same-one", "same-one", "same-one")
        assertNotNull(validatePasswordChange(same).next)
    }

    @Test
    fun `catches a mistyped repeat`() {
        assertNotNull(validatePasswordChange(valid.copy(repeat = "brand-neW")).repeat)
    }

    @Test
    fun `translates the refusal Supabase sends for an unchanged password`() {
        assertTrue(
            authErrorMessage("New password should be different from the old password.")
                .contains("fərqli olmalıdır"),
        )
    }
}

class NewPasswordTest {

    @Test
    fun `accepts a matching pair that is long enough`() {
        assertFalse(validateNewPassword("brand-new", "brand-new").any)
    }

    @Test
    fun `holds it to the same length rule as everywhere else`() {
        val short = "x".repeat(MIN_PASSWORD_LENGTH - 1)
        assertNotNull(validateNewPassword(short, short).next)
    }

    @Test
    fun `catches a mistyped repeat`() {
        assertNotNull(validateNewPassword("brand-new", "brand-neW").repeat)
    }

    @Test
    fun `asks for no current password — the link is what stands in for it`() {
        assertNull(validateNewPassword("brand-new", "brand-new").current)
    }

    @Test
    fun `says a spent or expired link is spent, and what to do`() {
        assertTrue(
            authErrorMessage("Email link is invalid or has expired")
                .contains("Yenidən sıfırlama"),
        )
    }

    @Test
    fun `says the same when the backend only calls the link invalid`() {
        // Not every refusal mentions expiry — a link that has already been
        // used comes back as invalid on its own, and the answer is the same
        // one either way.
        assertTrue(
            authErrorMessage("Email link is invalid").contains("Yenidən sıfırlama"),
        )
    }
}

class RateLimitTest {

    @Test
    fun `names the mail quota rather than blaming the attempt`() {
        // Supabase counts sign-up confirmations and reset links against one
        // small hourly quota; "too many attempts" blames somebody for typing
        // their password once.
        val message = authErrorMessage("email rate limit exceeded")
        assertTrue(message, message.contains("e-poçt limiti"))
        assertFalse(message, message.contains("cəhd oldu"))
    }

    @Test
    fun `passes on how long the server said to wait`() {
        assertTrue(
            authErrorMessage(
                "For security purposes, you can only request this after 47 seconds.",
            ).contains("47 saniyə"),
        )
    }
}

class SpentLinkTest {

    @Test
    fun `says a link no longer works, rather than passing on a JWT complaint`() {
        // What the server says is "invalid number of segments"; what happened
        // is that the link was already used or has expired.
        assertTrue(
            authErrorMessage("invalid JWT: unable to parse or verify signature")
                .contains("Link vaxtı keçib"),
        )
    }
}
