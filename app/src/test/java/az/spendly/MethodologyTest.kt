/**
 * The register of references. Guidance goes out of date, and the app cannot
 * know that; what it can do is refuse to present an unchecked reference as
 * though it were current.
 */
package az.spendly

import az.spendly.domain.insights.METHODS
import az.spendly.domain.insights.MethodOrigin
import az.spendly.domain.insights.ORIGIN_LABEL
import az.spendly.domain.insights.REVIEW_INTERVAL_MONTHS
import az.spendly.domain.insights.methodsNeedingReview
import az.spendly.domain.insights.needsReview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MethodologyRegisterTest {

    @Test
    fun `gives every reference a source, an origin and a review date`() {
        val iso = Regex("^\\d{4}-\\d{2}-\\d{2}$")
        for ((id, method) in METHODS) {
            assertTrue(id.name, method.name.isNotEmpty())
            assertTrue(id.name, method.source.isNotEmpty())
            assertTrue(id.name, iso.matches(method.reviewedOn))
        }
    }

    @Test
    fun `labels a jurisdiction rather than implying a universal rule`() {
        // Most published budgeting guidance is written for one country.
        // Showing a US figure unlabelled would present a market's rule as a
        // fact about money.
        assertEquals("ABŞ mənbəyi", ORIGIN_LABEL[MethodOrigin.US])
        assertTrue(METHODS.values.any { it.origin == MethodOrigin.US })
        assertTrue(METHODS.values.any { it.origin == MethodOrigin.APP })
    }

    @Test
    fun `only claims an external source when there is one`() {
        for ((id, method) in METHODS) {
            if (method.origin == MethodOrigin.APP) {
                assertNull(id.name, method.url)
            } else {
                assertTrue(id.name, method.url!!.startsWith("https://"))
            }
        }
    }
}

class GoingOutOfDateTest {

    private val method = METHODS.getValue(az.spendly.domain.insights.MethodId.ANOMALY)
        .copy(reviewedOn = "2026-01-15")

    @Test
    fun `is current inside the review interval`() {
        assertFalse(needsReview(method, "2026-06-01"))
    }

    @Test
    fun `is flagged once the interval has passed`() {
        assertTrue(needsReview(method, "2027-01-15"))
        assertTrue(needsReview(method, "2030-01-01"))
    }

    @Test
    fun `measures the interval in whole months`() {
        assertFalse(needsReview(method, "2026-12-31"))
        assertTrue(needsReview(method, "2027-01-01"))
        assertEquals(12, REVIEW_INTERVAL_MONTHS)
    }

    @Test
    fun `lists everything due a check, so the screen can say so once`() {
        assertTrue(methodsNeedingReview("2026-08-19").isEmpty())
        assertEquals(METHODS.size, methodsNeedingReview("2030-01-01").size)
    }
}
