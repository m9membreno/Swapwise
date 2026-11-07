package dev.swapwise.data.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.swapwise.data.BuildConfig
import dev.swapwise.data.db.ALL_MIGRATIONS
import dev.swapwise.data.db.EventDao
import dev.swapwise.data.db.FactorDao
import dev.swapwise.data.db.ReceiptDao
import dev.swapwise.data.db.SuggestionDao
import dev.swapwise.data.db.SwapwiseDatabase
import dev.swapwise.data.network.SwapwiseApi
import dev.swapwise.data.settings.AppSettings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.Clock
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Supplies an App Check token for each request. The Firebase implementation is added together with the
 * Firebase project; until then the backend runs with App Check disabled for local testing.
 */
fun interface AppCheckTokenProvider {
    suspend fun token(): String?
}

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): SwapwiseDatabase =
        Room.databaseBuilder(context, SwapwiseDatabase::class.java, "swapwise.db")
            .addMigrations(*ALL_MIGRATIONS)
            .build()

    @Provides fun receiptDao(db: SwapwiseDatabase): ReceiptDao = db.receiptDao()

    @Provides fun factorDao(db: SwapwiseDatabase): FactorDao = db.factorDao()

    @Provides fun suggestionDao(db: SwapwiseDatabase): SuggestionDao = db.suggestionDao()

    @Provides fun eventDao(db: SwapwiseDatabase): EventDao = db.eventDao()

    @Provides
    @Singleton
    fun clock(): Clock = Clock.systemUTC()

    @Provides
    @Singleton
    fun appCheckTokenProvider(): AppCheckTokenProvider = AppCheckTokenProvider { null }

    @Provides
    @Singleton
    fun json(): Json = Json {
        ignoreUnknownKeys = true // a newer backend may add fields; old app versions must keep working
        explicitNulls = false
    }

    @Provides
    @Singleton
    fun okHttp(settings: AppSettings, appCheck: AppCheckTokenProvider): OkHttpClient {
        // Runs on OkHttp's background threads, never the main thread, so the short blocking read is acceptable.
        val headers = Interceptor { chain ->
            val (installId, token) = runBlocking { settings.installId() to appCheck.token() }
            val request = chain.request().newBuilder()
                .header("X-Install-Id", installId)
                .apply { token?.let { header("X-Firebase-AppCheck", it) } }
                .build()
            chain.proceed(request)
        }
        return OkHttpClient.Builder()
            .addInterceptor(headers)
            .apply {
                if (BuildConfig.DEBUG) {
                    // Headers only: bodies contain receipt lines and must not reach logcat.
                    addInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.HEADERS))
                }
            }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS) // the backend gives the LLM 10 s, plus network time
            .build()
    }

    @Provides
    @Singleton
    fun api(client: OkHttpClient, json: Json): SwapwiseApi = Retrofit.Builder()
        .baseUrl(BuildConfig.API_BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(SwapwiseApi::class.java)
}
