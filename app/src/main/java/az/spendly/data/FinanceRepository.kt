/**
 * Persistence boundary.
 *
 * Everything above this file talks to [FinanceRepository] only, so the app
 * neither knows nor cares whether data lives in a file on the device or in
 * Postgres.
 *
 * The interface suspends because a real backend does: making it so costs the
 * local implementation nothing and means adding one did not change the UI.
 */
package az.spendly.data

import az.spendly.domain.FinanceData

interface FinanceRepository {
    suspend fun load(): FinanceData
    suspend fun save(data: FinanceData)
}
