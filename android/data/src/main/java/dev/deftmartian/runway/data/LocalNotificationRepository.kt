package dev.deftmartian.runway.data

import androidx.room.withTransaction

data class LocalNotificationPreferences(
    val runReminderEnabled: Boolean = false,
    val runReminderMinuteOfDay: Int = NotificationPreferencesEntity.DEFAULT_REMINDER_MINUTE_OF_DAY,
    val folderImportAlertsEnabled: Boolean = false,
)

data class LocalRunReminderCandidate(
    val epochDay: Long,
    val workoutIds: List<String>,
) {
    init {
        require(workoutIds.isNotEmpty())
        require(workoutIds.all(String::isNotBlank))
    }

    val subjectId: String = "$epochDay:${workoutIds.sorted().joinToString(separator = ",")}"
}

class LocalNotificationRepository(
    private val database: RunwayLedgerDatabase,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun preferences(): LocalNotificationPreferences =
        database.notificationDao().preferences()?.toLocal()
            ?: LocalNotificationPreferences()

    suspend fun updateRunReminder(enabled: Boolean, minuteOfDay: Int) {
        require(minuteOfDay in 0 until MINUTES_PER_DAY)
        database.withTransaction {
            val dao = database.notificationDao()
            val now = nowEpochMillis()
            val current = dao.preferences() ?: defaultPreferences(now)
            dao.savePreferences(
                current.copy(
                    runReminderEnabled = enabled,
                    runReminderMinuteOfDay = minuteOfDay,
                    updatedAtEpochMillis = now,
                ),
            )
            if (!enabled) dao.clearPending(NOTIFICATION_KIND_RUN_REMINDER)
        }
    }

    suspend fun updateFolderImportAlerts(enabled: Boolean) {
        database.withTransaction {
            val dao = database.notificationDao()
            val now = nowEpochMillis()
            val current = dao.preferences() ?: defaultPreferences(now)
            dao.savePreferences(
                current.copy(
                    folderImportAlertsEnabled = enabled,
                    updatedAtEpochMillis = now,
                ),
            )
            if (!enabled) dao.clearPending(NOTIFICATION_KIND_FOLDER_IMPORT)
        }
    }

    suspend fun nextPlannedRun(fromEpochDay: Long): LocalRunReminderCandidate? {
        val dao = database.notificationDao()
        val next = dao.nextPlannedRun(fromEpochDay) ?: return null
        val runs = dao.plannedRunsOn(next.currentScheduledEpochDay)
        return runs
            .map(WorkoutEntity::workoutId)
            .takeIf(List<String>::isNotEmpty)
            ?.let { LocalRunReminderCandidate(next.currentScheduledEpochDay, it) }
    }

    suspend fun plannedRunsOn(epochDay: Long): LocalRunReminderCandidate? =
        database.notificationDao().plannedRunsOn(epochDay)
            .map(WorkoutEntity::workoutId)
            .takeIf(List<String>::isNotEmpty)
            ?.let { LocalRunReminderCandidate(epochDay, it) }

    suspend fun enqueueRunReminder(candidate: LocalRunReminderCandidate): Boolean =
        database.notificationDao().enqueue(
            NotificationDeliveryEntity(
                deliveryId = "run:${candidate.subjectId}",
                kind = NOTIFICATION_KIND_RUN_REMINDER,
                subjectId = candidate.subjectId,
                localEpochDay = candidate.epochDay,
                state = NOTIFICATION_DELIVERY_PENDING,
                createdAtEpochMillis = nowEpochMillis(),
                deliveredAtEpochMillis = null,
            ),
        ) != -1L

    suspend fun pendingRunReminders(limit: Int = MAX_PENDING_DELIVERIES): List<NotificationDeliveryEntity> =
        database.notificationDao().pending(NOTIFICATION_KIND_RUN_REMINDER, limit)

    suspend fun discardStaleRunReminders(beforeEpochDay: Long): Int =
        database.notificationDao().clearPendingBefore(
            NOTIFICATION_KIND_RUN_REMINDER,
            beforeEpochDay,
        )

    suspend fun pendingFolderImportAlerts(limit: Int = MAX_PENDING_DELIVERIES): List<NotificationDeliveryEntity> =
        database.notificationDao().pending(NOTIFICATION_KIND_FOLDER_IMPORT, limit)

    suspend fun markDelivered(deliveries: List<NotificationDeliveryEntity>): Int {
        if (deliveries.isEmpty()) return 0
        val dao = database.notificationDao()
        val marked = dao.markDelivered(
            deliveries.map(NotificationDeliveryEntity::deliveryId),
            nowEpochMillis(),
        )
        dao.clearDeliveredBefore(nowEpochMillis() - DELIVERY_RETENTION_MILLIS)
        return marked
    }

    suspend fun clearFolderImportDeliveryHistory(): Int =
        database.notificationDao().clearKind(NOTIFICATION_KIND_FOLDER_IMPORT)

    private fun defaultPreferences(now: Long) = NotificationPreferencesEntity(
        runReminderEnabled = false,
        runReminderMinuteOfDay = NotificationPreferencesEntity.DEFAULT_REMINDER_MINUTE_OF_DAY,
        folderImportAlertsEnabled = false,
        updatedAtEpochMillis = now,
    )

    private fun NotificationPreferencesEntity.toLocal() = LocalNotificationPreferences(
        runReminderEnabled = runReminderEnabled,
        runReminderMinuteOfDay = normalizedReminderMinuteOfDay(runReminderMinuteOfDay),
        folderImportAlertsEnabled = folderImportAlertsEnabled,
    )

    private companion object {
        const val MINUTES_PER_DAY = 24 * 60
        const val MAX_PENDING_DELIVERIES = 100
        const val DELIVERY_RETENTION_MILLIS = 180L * 24 * 60 * 60 * 1_000
    }
}

internal fun normalizedReminderMinuteOfDay(value: Int): Int =
    value.takeIf { it in 0 until 24 * 60 }
        ?: NotificationPreferencesEntity.DEFAULT_REMINDER_MINUTE_OF_DAY

const val NOTIFICATION_KIND_RUN_REMINDER = "run_reminder"
const val NOTIFICATION_KIND_FOLDER_IMPORT = "folder_import_review"
const val NOTIFICATION_DELIVERY_PENDING = "pending"
const val NOTIFICATION_DELIVERY_DELIVERED = "delivered"
