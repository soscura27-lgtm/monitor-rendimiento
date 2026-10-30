package com.bajaya.app

import android.content.Context
import android.os.Build
import androidx.room.Room
import androidx.work.WorkManager
import com.bajaya.app.data.DownloadRepository
import com.bajaya.app.data.MediaStoreSaver
import com.bajaya.app.data.SettingsRepository
import com.bajaya.app.data.db.AppDatabase
import com.bajaya.app.data.db.DownloadDao
import com.bajaya.app.extract.DirectLinkExtractor
import com.bajaya.app.extract.ExtractorRegistry
import com.bajaya.app.extract.HtmlPageExtractor
import com.bajaya.app.extract.InternetArchiveExtractor
import com.bajaya.app.net.FileDownloader
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Contenedor de dependencias hecho a mano (sin Hilt, para mantener el proyecto simple).
 * Todo se crea de forma perezosa y se comparte en toda la app.
 */
class AppContainer(context: Context) {
    private val appContext: Context = context.applicationContext

    /** Cliente HTTP único. Se identifica con honestidad: no se hace pasar por otra app. */
    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            // Solo TLS: si un servidor redirige a http:// la conexión se rechaza en vez de viajar sin cifrar.
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS))
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", "BajaYa/1.0 (Android ${Build.VERSION.RELEASE})")
                        .build()
                )
            }
            .build()
    }

    private val database: AppDatabase by lazy {
        Room.databaseBuilder(appContext, AppDatabase::class.java, "bajaya.db").build()
    }

    val downloadDao: DownloadDao by lazy { database.downloadDao() }
    val settings: SettingsRepository by lazy { SettingsRepository(appContext) }
    val mediaSaver: MediaStoreSaver by lazy { MediaStoreSaver(appContext) }
    val downloader: FileDownloader by lazy { FileDownloader(httpClient) }

    val downloads: DownloadRepository by lazy {
        DownloadRepository(appContext, downloadDao, WorkManager.getInstance(appContext), mediaSaver)
    }

    /**
     * Fuentes de descarga, de la más específica a la más genérica.
     * Para añadir un sitio nuevo: crea una clase que implemente MediaExtractor y agrégala aquí.
     */
    val extractors: ExtractorRegistry by lazy {
        ExtractorRegistry(
            listOf(
                InternetArchiveExtractor(httpClient),
                DirectLinkExtractor(),
                HtmlPageExtractor(httpClient)
            )
        )
    }
}
