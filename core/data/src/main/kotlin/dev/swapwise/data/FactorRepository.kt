package dev.swapwise.data

import dev.swapwise.data.db.CarbonFactorEntity
import dev.swapwise.data.db.FactorDao
import dev.swapwise.data.network.SwapwiseApi
import dev.swapwise.data.settings.AppSettings
import dev.swapwise.domain.CategoryFactor
import dev.swapwise.domain.FoodCategory
import kotlinx.coroutines.flow.Flow
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FactorRepository @Inject constructor(
    private val dao: FactorDao,
    private val api: SwapwiseApi,
    private val settings: AppSettings,
) {
    /**
     * Downloads factors only when they changed: sends the stored ETag, and a 304 means the copy in Room is
     * current. Returns true if new factors were stored.
     */
    suspend fun sync(): Boolean {
        val etag = settings.factorsEtag().takeIf { dao.version() != null } // no local copy: ask for everything
        val response = api.factors(etag)
        if (response.code() == 304) return false
        if (!response.isSuccessful) throw HttpException(response)
        val body = response.body() ?: return false
        dao.replaceAll(
            body.factors.map {
                CarbonFactorEntity(it.category, body.version, it.kgCo2ePerKg, it.kgCo2ePerKgLow, it.kgCo2ePerKgHigh, it.typicalItemKg)
            },
        )
        settings.setFactorsEtag(response.headers()["ETag"])
        return true
    }

    /** Local factors by category. Categories this app version does not know are skipped. */
    suspend fun factors(): Map<FoodCategory, CategoryFactor> = dao.all().mapNotNull { entity ->
        FoodCategory.fromCode(entity.category)?.let { category ->
            category to CategoryFactor(category, entity.kgCo2ePerKg, entity.kgCo2ePerKgLow, entity.kgCo2ePerKgHigh, entity.typicalItemKg)
        }
    }.toMap()

    suspend fun version(): Int? = dao.version()

    /** Emits the local factor version, null until the first download. */
    fun observeVersion(): Flow<Int?> = dao.observeVersion()
}
