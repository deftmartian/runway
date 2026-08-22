package dev.deftmartian.runway.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface NotificationDao {
    @Query("SELECT * FROM notification_preferences WHERE singletonId = :singletonId")
    suspend fun preferences(
        singletonId: Int = NotificationPreferencesEntity.SINGLETON_ID,
    ): NotificationPreferencesEntity?

    @Upsert
    suspend fun savePreferences(preferences: NotificationPreferencesEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueue(delivery: NotificationDeliveryEntity): Long

    @Query(
        "SELECT * FROM notification_deliveries WHERE kind = :kind AND state = 'pending' " +
            "ORDER BY createdAtEpochMillis, deliveryId LIMIT :limit",
    )
    suspend fun pending(kind: String, limit: Int): List<NotificationDeliveryEntity>

    @Query(
        "UPDATE notification_deliveries SET state = 'delivered', deliveredAtEpochMillis = :deliveredAt " +
            "WHERE deliveryId IN (:deliveryIds) AND state = 'pending'",
    )
    suspend fun markDelivered(deliveryIds: List<String>, deliveredAt: Long): Int

    @Query("DELETE FROM notification_deliveries WHERE kind = :kind AND state = 'pending'")
    suspend fun clearPending(kind: String): Int

    @Query(
        "DELETE FROM notification_deliveries WHERE kind = :kind AND state = 'pending' " +
            "AND localEpochDay IS NOT NULL AND localEpochDay < :beforeEpochDay",
    )
    suspend fun clearPendingBefore(kind: String, beforeEpochDay: Long): Int

    @Query(
        "DELETE FROM notification_deliveries WHERE state = 'delivered' " +
            "AND deliveredAtEpochMillis IS NOT NULL AND deliveredAtEpochMillis < :beforeEpochMillis",
    )
    suspend fun clearDeliveredBefore(beforeEpochMillis: Long): Int

    @Query("DELETE FROM notification_deliveries WHERE kind = :kind")
    suspend fun clearKind(kind: String): Int

    @Query("DELETE FROM notification_deliveries")
    suspend fun clearDeliveries()

    @Query("DELETE FROM notification_preferences")
    suspend fun clearPreferences()

    @Query(
        """
        SELECT stored_workout.* FROM workouts AS stored_workout
        INNER JOIN plans AS stored_plan ON stored_plan.planId = stored_workout.planId
        WHERE stored_plan.state = 'active'
          AND stored_workout.currentStatus = 'planned'
          AND stored_workout.tombstonedAtEpochMillis IS NULL
          AND stored_workout.currentWorkoutType != 'rest'
          AND stored_workout.currentScheduledEpochDay >= :fromEpochDay
        ORDER BY stored_workout.currentScheduledEpochDay, stored_workout.position, stored_workout.workoutId
        LIMIT 1
        """,
    )
    suspend fun nextPlannedRun(fromEpochDay: Long): WorkoutEntity?

    @Query(
        """
        SELECT stored_workout.* FROM workouts AS stored_workout
        INNER JOIN plans AS stored_plan ON stored_plan.planId = stored_workout.planId
        WHERE stored_plan.state = 'active'
          AND stored_workout.currentStatus = 'planned'
          AND stored_workout.tombstonedAtEpochMillis IS NULL
          AND stored_workout.currentWorkoutType != 'rest'
          AND stored_workout.currentScheduledEpochDay = :epochDay
        ORDER BY stored_workout.position, stored_workout.workoutId
        """,
    )
    suspend fun plannedRunsOn(epochDay: Long): List<WorkoutEntity>
}
