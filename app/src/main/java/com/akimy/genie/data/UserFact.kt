package com.akimy.genie.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A persistent user fact/preference stored in the Room database.
 *
 * Used by the agent to remember user preferences across sessions.
 * The agent can save and retrieve facts via SaveFactTool / RetrieveFactTool.
 */
@Entity(tableName = "user_facts")
data class UserFact(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,

    /** The fact key (e.g., "preferred_language", "home_address") */
    val key: String,

    /** The fact value (e.g., "Yoruba", "123 Main St") */
    val value: String,

    /** Timestamp when the fact was first created */
    val createdAt: Long = System.currentTimeMillis(),

    /** Timestamp when the fact was last updated */
    val updatedAt: Long = System.currentTimeMillis(),
)
