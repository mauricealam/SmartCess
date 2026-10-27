package com.smartcess.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.smartcess.app.data.local.entity.UserPreferencesEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserPreferencesDao {
    @Upsert suspend fun upsert(prefs: UserPreferencesEntity)
    @Query("SELECT * FROM user_preferences WHERE id = 0") suspend fun get(): UserPreferencesEntity?
    @Query("SELECT * FROM user_preferences WHERE id = 0") fun observe(): Flow<UserPreferencesEntity?>
    @Query("DELETE FROM user_preferences") suspend fun deleteAll()
}
