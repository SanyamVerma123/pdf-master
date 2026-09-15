package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.example.data.model.ConversionType
import com.example.data.model.PdfRecord

class Converters {
    @TypeConverter
    fun fromConversionType(type: ConversionType): String = type.name

    @TypeConverter
    fun toConversionType(value: String): ConversionType = try {
        ConversionType.valueOf(value)
    } catch (_: Exception) {
        ConversionType.IMAGE_TO_PDF
    }
}

@Database(entities = [PdfRecord::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun pdfDao(): PdfDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "omnivid_pdf_database"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
