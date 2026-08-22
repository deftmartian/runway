package dev.deftmartian.runway.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PlanSetupReceiptDao {
    @Query("SELECT * FROM plan_setup_receipts WHERE operationId = :operationId")
    suspend fun receipt(operationId: String): PlanSetupReceiptEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(receipt: PlanSetupReceiptEntity)
}
