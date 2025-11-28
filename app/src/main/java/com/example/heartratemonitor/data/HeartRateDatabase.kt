package com.example.heartratemonitor.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.util.Date

@Entity(tableName = "heart_rate_measurements")
data class HeartRateMeasurement(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val bpm: Int,
    val confidence: String, // "High", "Good", "Low"
    val timestamp: Long = System.currentTimeMillis(),
    val duration: Int = 10 // measurement duration in seconds
)

@Dao
interface HeartRateDao {
    @Query("SELECT * FROM heart_rate_measurements ORDER BY timestamp DESC")
    fun getAllMeasurements(): Flow<List<HeartRateMeasurement>>

    @Query("SELECT * FROM heart_rate_measurements ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentMeasurements(limit: Int): Flow<List<HeartRateMeasurement>>

    @Query("SELECT * FROM heart_rate_measurements WHERE timestamp >= :startTime ORDER BY timestamp DESC")
    fun getMeasurementsSince(startTime: Long): Flow<List<HeartRateMeasurement>>

    @Query("SELECT AVG(bpm) FROM heart_rate_measurements WHERE timestamp >= :startTime")
    suspend fun getAverageBpmSince(startTime: Long): Double?

    @Query("SELECT MIN(bpm) FROM heart_rate_measurements WHERE timestamp >= :startTime")
    suspend fun getMinBpmSince(startTime: Long): Int?

    @Query("SELECT MAX(bpm) FROM heart_rate_measurements WHERE timestamp >= :startTime")
    suspend fun getMaxBpmSince(startTime: Long): Int?

    @Query("SELECT COUNT(*) FROM heart_rate_measurements")
    suspend fun getTotalMeasurements(): Int

    @Insert
    suspend fun insertMeasurement(measurement: HeartRateMeasurement): Long

    @Delete
    suspend fun deleteMeasurement(measurement: HeartRateMeasurement)

    @Query("DELETE FROM heart_rate_measurements")
    suspend fun deleteAllMeasurements()

    @Query("DELETE FROM heart_rate_measurements WHERE id = :id")
    suspend fun deleteMeasurementById(id: Long)
}

@Database(entities = [HeartRateMeasurement::class], version = 1, exportSchema = false)
abstract class HeartRateDatabase : RoomDatabase() {
    abstract fun heartRateDao(): HeartRateDao

    companion object {
        @Volatile
        private var INSTANCE: HeartRateDatabase? = null

        fun getDatabase(context: Context): HeartRateDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    HeartRateDatabase::class.java,
                    "heart_rate_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
