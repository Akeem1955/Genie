package com.akimy.genie.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A pre-compiled agent plan stored in the Skill Library.
 *
 * When the agent successfully completes a novel goal, the plan is serialized
 * and saved here. On future requests matching the goalPattern, the Planner
 * can skip LLM inference and reuse the cached plan.
 *
 * This is the "self-evolution" mechanism — the agent gets faster over time.
 */
@Entity(tableName = "skills")
data class Skill(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,

    /** Pattern for matching goals (keywords or regex) */
    val goalPattern: String,

    /** Serialized JSON list of Decision.Act steps */
    val planJson: String,

    /** Number of times this skill was successfully used */
    val successCount: Int = 0,

    /** Timestamp when the skill was created */
    val createdAt: Long = System.currentTimeMillis(),
)
