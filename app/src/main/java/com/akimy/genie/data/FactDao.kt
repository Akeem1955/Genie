package com.akimy.genie.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * DAO for UserFact CRUD operations.
 */
@Dao
interface FactDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(fact: UserFact): Long

    @Update
    suspend fun update(fact: UserFact)

    @Delete
    suspend fun delete(fact: UserFact)

    @Query("SELECT * FROM user_facts WHERE `key` = :key LIMIT 1")
    suspend fun getFactByKey(key: String): UserFact?

    @Query("SELECT * FROM user_facts WHERE `key` LIKE '%' || :query || '%'")
    suspend fun searchFacts(query: String): List<UserFact>

    @Query("SELECT * FROM user_facts ORDER BY updatedAt DESC")
    fun getAllFacts(): Flow<List<UserFact>>

    @Query("SELECT * FROM user_facts ORDER BY updatedAt DESC")
    suspend fun getAllFactsSnapshot(): List<UserFact>

    /**
     * Upsert a fact — insert if new, update if key already exists.
     */
    @Transaction
    suspend fun upsert(key: String, value: String) {
        val existing = getFactByKey(key)
        if (existing != null) {
            update(existing.copy(value = value, updatedAt = System.currentTimeMillis()))
        } else {
            insert(UserFact(key = key, value = value))
        }
    }
}
