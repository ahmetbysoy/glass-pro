package com.glasspro.tracker.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import com.glasspro.tracker.core.model.Direction
import com.glasspro.tracker.core.model.LiquidationSide
import com.glasspro.tracker.core.model.SignalStatus
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

// ---------------------------------------------------------------------------
// Entities
// ---------------------------------------------------------------------------

@Entity(tableName = "liquidations")
data class LiquidationEntity(
    @PrimaryKey val id: String,
    val symbol: String,
    val exchange: String,
    val side: String,          // LONG | SHORT
    val price: Double,
    val quantity: Double,
    val notionalUsd: Double,
    val timestampMs: Long
)

@Entity(tableName = "analyses")
data class AnalysisEntity(
    @PrimaryKey val id: String,
    val symbol: String,
    val createdAtMs: Long,
    val horizonMs: Long,
    val horizonLabel: String,
    val price: Double,
    val totalScore: Double,
    val direction: String,     // LONG | SHORT | NEUTRAL
    val confidence: Double,
    val signalStrength: Double,
    val probabilitiesJson: String,
    val componentsJson: String,
    val orderBookImbalancePct: Double,
    val tradeFlowBuyPct: Double,
    val cvd: Double,
    val fundingRatePct: Double,
    val oiChangePct1h: Double?,
    val oiUsd: Double?,
    val takerBuyPct: Double,
    val lsRatio: Double?,
    val lsTrend: String,
    val fundingTrend: String,
    val liquidationImbalancePct: Double,
    val globalOiUsd: Double?,
    val risksJson: String,
    val strategyJson: String,
    val forecastsJson: String,
    val whaleTradesJson: String,
    val conflictsJson: String,
    val calibrationJson: String,
    val providerCount: Int,
    val priceDispersionPct: Double,
    val status: String,        // PENDING | HIT | MISS
    val actualPrice: Double?,
    val priceChangePct: Double?,
    val verifyAtMs: Long,
    val atrPct1h: Double
)

@Entity(tableName = "calibration")
data class CalibrationEntity(
    @PrimaryKey val symbol: String,
    val resolvedJson: String,
    val rollingAccuracy20: Double?,
    val componentCorrelationsJson: String,
    val updatedAtMs: Long
)

// ---------------------------------------------------------------------------
// Type converters
// ---------------------------------------------------------------------------

class Converters {

    @TypeConverter
    fun fromStringList(value: List<String>): String {
        val arr = JSONArray()
        value.forEach { arr.put(it) }
        return arr.toString()
    }

    @TypeConverter
    fun toStringList(json: String): List<String> {
        val result = mutableListOf<String>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) result.add(arr.getString(i))
        } catch (_: Exception) {
        }
        return result
    }

    @TypeConverter
    fun fromDoubleList(value: List<Double>): String {
        val arr = JSONArray()
        value.forEach { arr.put(it) }
        return arr.toString()
    }

    @TypeConverter
    fun toDoubleList(json: String): List<Double> {
        val result = mutableListOf<Double>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) result.add(arr.getDouble(i))
        } catch (_: Exception) {
        }
        return result
    }

    @TypeConverter
    fun fromStringMap(value: Map<String, String>): String {
        val obj = JSONObject()
        value.forEach { (k, v) -> obj.put(k, v) }
        return obj.toString()
    }

    @TypeConverter
    fun toStringMap(json: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        try {
            val obj = JSONObject(json)
            val it = obj.keys()
            while (it.hasNext()) {
                val k = it.next()
                result[k] = obj.optString(k)
            }
        } catch (_: Exception) {
        }
        return result
    }

    @TypeConverter
    fun fromDoubleMap(value: Map<String, Double>): String {
        val obj = JSONObject()
        value.forEach { (k, v) -> obj.put(k, v) }
        return obj.toString()
    }

    @TypeConverter
    fun toDoubleMap(json: String): Map<String, Double> {
        val result = mutableMapOf<String, Double>()
        try {
            val obj = JSONObject(json)
            val it = obj.keys()
            while (it.hasNext()) {
                val k = it.next()
                result[k] = obj.optDouble(k)
            }
        } catch (_: Exception) {
        }
        return result
    }
}

// ---------------------------------------------------------------------------
// DAOs
// ---------------------------------------------------------------------------

@Dao
interface LiquidationDao {
    @Query("SELECT * FROM liquidations ORDER BY timestampMs DESC LIMIT 300")
    fun getAll(): Flow<List<LiquidationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: LiquidationEntity)

    @Query("DELETE FROM liquidations")
    suspend fun clearAll()
}

@Dao
interface AnalysisDao {
    @Query("SELECT * FROM analyses ORDER BY createdAtMs DESC")
    fun getAll(): Flow<List<AnalysisEntity>>

    @Query("SELECT * FROM analyses WHERE status = 'PENDING' ORDER BY verifyAtMs ASC")
    suspend fun getPending(): List<AnalysisEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(analysis: AnalysisEntity)

    @Query(
        "UPDATE analyses SET status = :status, actualPrice = :actualPrice, " +
            "priceChangePct = :priceChangePct WHERE id = :id"
    )
    suspend fun updateVerification(id: String, status: String, actualPrice: Double, priceChangePct: Double)

    @Query("DELETE FROM analyses")
    suspend fun clearAll()

    @Query("SELECT * FROM analyses WHERE symbol = :symbol AND status != 'PENDING' ORDER BY createdAtMs DESC")
    suspend fun getResolved(symbol: String): List<AnalysisEntity>
}

@Dao
interface CalibrationDao {
    @Query("SELECT * FROM calibration WHERE symbol = :symbol LIMIT 1")
    suspend fun get(symbol: String): CalibrationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CalibrationEntity)

    @Query("DELETE FROM calibration")
    suspend fun clearAll()
}

// ---------------------------------------------------------------------------
// Database
// ---------------------------------------------------------------------------

@Database(
    entities = [
        LiquidationEntity::class,
        AnalysisEntity::class,
        CalibrationEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class GlassDatabase : RoomDatabase() {
    abstract fun liquidationDao(): LiquidationDao
    abstract fun analysisDao(): AnalysisDao
    abstract fun calibrationDao(): CalibrationDao

    companion object {
        @Volatile
        private var INSTANCE: GlassDatabase? = null

        fun getInstance(context: Context): GlassDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = androidx.room.Room.databaseBuilder(
                    context.applicationContext,
                    GlassDatabase::class.java,
                    "glasspro.db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}

// Small helpers used by the repository to map enums to wire strings.
object Wire {

    fun side(entity: LiquidationEntity): LiquidationSide =
        if (entity.side == LiquidationSide.LONG.name) LiquidationSide.LONG else LiquidationSide.SHORT

    fun direction(entity: AnalysisEntity): Direction =
        runCatching { Direction.valueOf(entity.direction) }.getOrDefault(Direction.NEUTRAL)

    fun status(entity: AnalysisEntity): SignalStatus =
        runCatching { SignalStatus.valueOf(entity.status) }.getOrDefault(SignalStatus.PENDING)
}
