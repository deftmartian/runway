package dev.deftmartian.runway.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileSettingsDao {
    @Query("SELECT * FROM profile_settings WHERE singletonId = :singletonId")
    fun observe(singletonId: Int = ProfileSettingsEntity.SINGLETON_ID): Flow<ProfileSettingsEntity?>

    @Query("SELECT * FROM profile_settings WHERE singletonId = :singletonId")
    suspend fun get(singletonId: Int = ProfileSettingsEntity.SINGLETON_ID): ProfileSettingsEntity?

    @Upsert
    suspend fun save(profile: ProfileSettingsEntity)

    @Query("SELECT * FROM profile_availability_days WHERE singletonId = :singletonId ORDER BY dayOfWeek LIMIT :limit")
    suspend fun availabilityDays(
        singletonId: Int = ProfileSettingsEntity.SINGLETON_ID,
        limit: Int,
    ): List<ProfileAvailabilityDayEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAvailabilityDays(days: List<ProfileAvailabilityDayEntity>)

    @Query("DELETE FROM profile_availability_days WHERE singletonId = :singletonId")
    suspend fun clearAvailabilityDays(singletonId: Int = ProfileSettingsEntity.SINGLETON_ID)

    @Transaction
    suspend fun replaceOnboardingInputs(
        profile: ProfileSettingsEntity,
        availabilityDays: List<Int>,
    ) {
        require(availabilityDays.distinct().size == availabilityDays.size && availabilityDays.all { it in 0..6 }) {
            "Availability must contain distinct weekday values from 0 through 6."
        }
        save(profile)
        clearAvailabilityDays(profile.singletonId)
        if (availabilityDays.isNotEmpty()) {
            insertAvailabilityDays(availabilityDays.map { ProfileAvailabilityDayEntity(profile.singletonId, it) })
        }
    }

    @Query("DELETE FROM profile_settings")
    suspend fun clear()
}
