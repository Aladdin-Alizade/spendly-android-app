/**
 * Sign-in form validation, carried over from the web app's suite.
 */
package az.spendly

import az.spendly.domain.AuthMode
import az.spendly.domain.MIN_PASSWORD_LENGTH
import az.spendly.domain.authErrorMessage
import az.spendly.domain.validateCredentials
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
