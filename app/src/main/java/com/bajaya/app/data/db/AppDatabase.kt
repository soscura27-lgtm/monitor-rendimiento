package com.bajaya.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/** Base de datos local (solo el historial de descargas). No sale del dispositivo. */
@Database(entities = [DownloadEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao
}
