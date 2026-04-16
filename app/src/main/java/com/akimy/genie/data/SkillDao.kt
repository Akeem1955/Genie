package com.akimy.genie.data

import androidx.room.*

/**
 * DAO for Skill Library CRUD operations.
 */
@Dao
interface SkillDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(skill: Skill): Long

    @Update
    suspend fun update(skill: Skill)

    @Delete
    suspend fun delete(skill: Skill)

    @Query("SELECT * FROM skills WHERE goalPattern LIKE '%' || :query || '%' ORDER BY successCount DESC")
    suspend fun findMatchingSkills(query: String): List<Skill>

    @Query("SELECT * FROM skills ORDER BY successCount DESC")
    suspend fun getAllSkills(): List<Skill>

    @Query("UPDATE skills SET successCount = successCount + 1 WHERE id = :skillId")
    suspend fun incrementSuccessCount(skillId: Int)
}
