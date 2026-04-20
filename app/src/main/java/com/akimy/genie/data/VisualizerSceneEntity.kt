package com.akimy.genie.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "visualizer_scenes")
data class VisualizerSceneEntity(
    @PrimaryKey val sceneId: String,
    val title: String,
    val diagramType: String,
    val updatedAt: Long,
    val sceneJson: String,
)
