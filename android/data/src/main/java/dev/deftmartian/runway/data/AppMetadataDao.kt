package dev.deftmartian.runway.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface AppMetadataDao {
    @Upsert
    suspend fun save(metadata: AppMetadataEntity)

    @Query("SELECT * FROM app_metadata WHERE key = :key")
    suspend fun value(key: String): AppMetadataEntity?

    @Query("DELETE FROM app_metadata WHERE key = :key")
    suspend fun delete(key: String): Int

    @Query("DELETE FROM app_metadata")
    suspend fun clear()
}
