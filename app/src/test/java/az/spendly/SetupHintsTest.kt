/**
 * Backend errors, and the setup step each one calls for.
 *
 * Every rule here exists because a raw API message once reached somebody who
 * could do nothing with it. What is being checked is not the wording but which
 * rule wins: the specific one has to beat the general one, or "run the schema
 * again" turns into "create the tables" and the person does the wrong thing.
 */
package az.spendly

import az.spendly.data.describeError
import az.spendly.data.setupHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupHintTest {

    @Test
    fun `has nothing to say about nothing`() {
        assertNull(setupHint(null))
        assertNull(setupHint(""))
        assertNull(setupHint("something entirely unrelated"))
    }

    @Test
    fun `names the provider setting when sign-up is closed`() {
        // Nothing the user can do from inside the app, so the hint has to name
        // the dashboard setting.
        val hint = setupHint("Signups not allowed for this instance")
        assertNotNull(hint)
        assertTrue(hint!!.contains("Sign In / Providers"))
    }

    @Test
    fun `says to sign in again when the session has gone`() {
        assertTrue(setupHint("Hesaba daxil olunmayıb")!!.contains("Yenidən daxil olun"))
    }

    @Test
    fun `asks for a re-run when a column is missing, not a first-time setup`() {
        // The exact error behind "income amounts are not saved": the table is
        // there, but predates the column the app writes to.
        val hint = setupHint("column income_plans.amounts does not exist")
        assertNotNull(hint)
        assertTrue(hint!!.contains("yenidən işə salın"))

        assertEquals(hint, setupHint("42703"))
        assertEquals(
            hint,
            setupHint("Could not find the 'amounts' column of 'income_plans' in the schema cache"),
        )
        assertEquals(hint, setupHint("PGRST204"))

        // An upsert naming (user_id, id) against a table still keyed on the id
        // alone. Same cause, same fix, and the raw message names neither.
        assertEquals(
            hint,
            setupHint(
                "there is no unique or exclusion constraint matching the " +
                    "ON CONFLICT specification",
            ),
        )
        assertEquals(hint, setupHint("42P10"))
    }

    @Test
    fun `asks for a first-time setup when the table itself is missing`() {
        val hint = setupHint("Could not find the table public.categories")
        assertNotNull(hint)
        assertTrue(hint!!.contains("schema.sql"))
        assertTrue(!hint.contains("yenidən"))
        assertEquals(hint, setupHint("PGRST205"))
    }

    @Test
    fun `does not let the general rule shadow the specific one`() {
        // Both patterns match this string; the column rule has to win.
        assertNotEquals(
            setupHint("relation public.categories does not exist"),
            setupHint("column income_plans.amounts does not exist"),
        )
    }

    @Test
    fun `covers permissions and connectivity`() {
        assertNotNull(setupHint("new row violates row-level security policy"))
        assertTrue(setupHint("Unable to resolve host")!!.contains("SUPABASE_URL"))
    }
}

class DescribeErrorTest {

    @Test
    fun `reads the message of a thrown error`() {
        assertEquals("boom", describeError(IllegalStateException("boom")))
    }

    @Test
    fun `trims what it reads`() {
        assertEquals("boom", describeError(IllegalStateException("  boom  ")))
    }

    @Test
    fun `names the failure when it carries no message`() {
        // A banner saying nothing at all is worse than one naming the class:
        // the class is at least something to search for.
        assertEquals("IllegalStateException", describeError(IllegalStateException()))
    }

    @Test
    fun `reports nothing rather than inventing something`() {
        assertEquals("", describeError(null))
    }

    @Test
    fun `produces something setupHint can still match`() {
        val described = describeError(
            IllegalStateException(
                "PGRST204: Could not find the 'amounts' column of 'income_plans' " +
                    "in the schema cache",
            ),
        )
        assertTrue(setupHint(described)!!.contains("yenidən işə salın"))
    }
}
