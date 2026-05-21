package com.example.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "transcriptions")
data class Transcription(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val text: String,
    val summary: String? = null,
    val actionItems: String? = null,
    val language: String, // "es", "en", "auto"
    val timestamp: Long = System.currentTimeMillis(),
    val durationSeconds: Long = 0L,
    val isFromFile: Boolean = false
) : java.io.Serializable

@Dao
interface TranscriptionDao {
    @Query("SELECT * FROM transcriptions ORDER BY timestamp DESC")
    fun getAllTranscriptions(): Flow<List<Transcription>>

    @Query("SELECT * FROM transcriptions WHERE id = :id")
    suspend fun getTranscriptionById(id: Int): Transcription?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTranscription(transcription: Transcription): Long

    @Update
    suspend fun updateTranscription(transcription: Transcription)

    @Delete
    suspend fun deleteTranscription(transcription: Transcription)

    @Query("DELETE FROM transcriptions WHERE id = :id")
    suspend fun deleteById(id: Int)
}

@Database(entities = [Transcription::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transcriptionDao(): TranscriptionDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "aurascribe_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

class TranscriptionRepository(private val transcriptionDao: TranscriptionDao) {
    val allTranscriptions: Flow<List<Transcription>> = transcriptionDao.getAllTranscriptions()

    suspend fun getById(id: Int): Transcription? = transcriptionDao.getTranscriptionById(id)

    suspend fun insert(transcription: Transcription): Long = transcriptionDao.insertTranscription(transcription)

    suspend fun update(transcription: Transcription) = transcriptionDao.updateTranscription(transcription)

    suspend fun delete(transcription: Transcription) = transcriptionDao.deleteTranscription(transcription)

    suspend fun deleteById(id: Int) = transcriptionDao.deleteById(id)
}
