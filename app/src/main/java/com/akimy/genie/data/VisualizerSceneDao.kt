package com.akimy.genie.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface VisualizerSceneDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(scene: VisualizerSceneEntity)

    @Query("DELETE FROM visualizer_scenes WHERE sceneId = :sceneId")
    suspend fun deleteById(sceneId: String)

    @Query("SELECT * FROM visualizer_scenes WHERE sceneId = :sceneId LIMIT 1")
    suspend fun getById(sceneId: String): VisualizerSceneEntity?

    @Query("SELECT * FROM visualizer_scenes ORDER BY updatedAt DESC")
    suspend fun getAllByRecent(): List<VisualizerSceneEntity>
}
