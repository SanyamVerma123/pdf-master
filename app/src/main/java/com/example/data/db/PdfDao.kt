package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.PdfRecord
import kotlinx.coroutines.flow.Flow

@Dao
interface PdfDao {
    @Query("SELECT * FROM pdf_records ORDER BY createdAt DESC")
    fun getAllRecords(): Flow<List<PdfRecord>>

    @Query("SELECT * FROM pdf_records WHERE isFavorite = 1 ORDER BY createdAt DESC")
    fun getFavoriteRecords(): Flow<List<PdfRecord>>

    @Query("SELECT * FROM pdf_records WHERE fileName LIKE '%' || :query || '%' ORDER BY createdAt DESC")
    fun searchRecords(query: String): Flow<List<PdfRecord>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecord(record: PdfRecord): Long

    @Update
    suspend fun updateRecord(record: PdfRecord)

    @Query("UPDATE pdf_records SET isFavorite = NOT isFavorite WHERE id = :id")
    suspend fun toggleFavorite(id: Long)

    @Query("DELETE FROM pdf_records WHERE id = :id")
    suspend fun deleteRecordById(id: Long)

    @Query("DELETE FROM pdf_records")
    suspend fun clearAll()
}
