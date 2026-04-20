package com.akimy.genie.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room database for Genie's persistent storage.
 *
 * Contains:
 * - user_facts: Long-term user preferences and facts
 * - skills: Pre-compiled agent plans for self-evolution
 */
@Database(
    entities = [UserFact::class, Skill::class, VisualizerSceneEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class GenieDatabase : RoomDatabase() {

    abstract fun factDao(): FactDao
    abstract fun skillDao(): SkillDao
    abstract fun visualizerSceneDao(): VisualizerSceneDao

    companion object {
        @Volatile
        private var INSTANCE: GenieDatabase? = null

        fun getInstance(context: Context): GenieDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    GenieDatabase::class.java,
                    "genie_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
