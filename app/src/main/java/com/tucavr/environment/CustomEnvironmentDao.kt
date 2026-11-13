package com.tucavr.environment

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CustomEnvironmentDao {
    @Query("SELECT * FROM custom_environment ORDER BY created_at_ms ASC")
    suspend fun getAll(): List<CustomEnvironment>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(environment: CustomEnvironment)
}
