package com.example.data.repository

import com.example.data.db.PdfDao
import com.example.data.model.PdfRecord
import kotlinx.coroutines.flow.Flow
import java.io.File

class PdfRepository(private val pdfDao: PdfDao) {
    val allRecords: Flow<List<PdfRecord>> = pdfDao.getAllRecords()
    val favoriteRecords: Flow<List<PdfRecord>> = pdfDao.getFavoriteRecords()

    fun search(query: String): Flow<List<PdfRecord>> = pdfDao.searchRecords(query)

    suspend fun insert(record: PdfRecord): Long = pdfDao.insertRecord(record)

    suspend fun toggleFavorite(id: Long) = pdfDao.toggleFavorite(id)

    suspend fun delete(record: PdfRecord) {
        try {
            val file = File(record.filePath)
            if (file.exists()) {
                file.delete()
            }
        } catch (_: Exception) {}
        pdfDao.deleteRecordById(record.id)
    }

    suspend fun clearAll() {
        pdfDao.clearAll()
    }
}
