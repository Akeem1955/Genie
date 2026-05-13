package com.akimy.genie.tools

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.Serializable

// ═══════════════════════════════════════════════════════════════════════════
// Food Calorie & Nutrient Analysis Models
// ═══════════════════════════════════════════════════════════════════════════

@Serializable
data class NutrientInfo(
    val name: String,
    val amount: String,
    val unit: String,
    val explanation: String, // What this nutrient does in your body
    val dailyValuePercent: Int? = null, // Optional % of daily recommended value
)

@Serializable
data class FoodNutritionAnalysis(
    val foodName: String,
    val totalCalories: Int,
    val servingSize: String,
    val macronutrients: List<NutrientInfo>, // Protein, carbs, fats
    val vitamins: List<NutrientInfo>,
    val minerals: List<NutrientInfo>,
    val otherNutrients: List<NutrientInfo>, // Fiber, sugar, cholesterol, etc.
    val nutritionCoverage: NutritionCoverage,
)

@Serializable
data class NutritionCoverage(
    val covered: List<String>, // Nutrients this food provides well
    val missing: List<String>, // Nutrients not covered or insufficient
    val summary: String, // Brief summary of nutritional value
)

// ═══════════════════════════════════════════════════════════════════════════
// Health Topics Library Models
// ═══════════════════════════════════════════════════════════════════════════

@Serializable
data class HealthRecord(
    val disease: String,
    val url_source: String,
    val data: Map<String, DataValue> = emptyMap(),
)

/**
 * Represents different value types in health record data.
 * JSON can have: String, List<String>, or nested Map<String, Object>
 */
@Serializable
sealed class DataValue {
    @Serializable
    data class Text(val value: String) : DataValue()

    @Serializable
    data class ListText(val value: List<String>) : DataValue()

    @Serializable
    data class NestedMap(val value: Map<String, String>) : DataValue()
}

// ═══════════════════════════════════════════════════════════════════════════
// Health Topic Index (for model context)
// ═══════════════════════════════════════════════════════════════════════════

data class HealthTopicIndex(
    val topics: List<String>, // All 200+ disease names
    val totalCount: Int,
)

// ═══════════════════════════════════════════════════════════════════════════
// Session State Management
// ═══════════════════════════════════════════════════════════════════════════

sealed class HealthResult {
    data class FoodAnalysis(val analysis: FoodNutritionAnalysis) : HealthResult()
    data class HealthTopic(val record: HealthRecord) : HealthResult()
    data class Error(val message: String) : HealthResult()
}

object HealthSessionStore {
    private val _resultFlow = MutableSharedFlow<HealthResult>(replay = 1)
    val resultFlow: SharedFlow<HealthResult> = _resultFlow.asSharedFlow()

    fun setResult(result: HealthResult) {
        _resultFlow.tryEmit(result)
    }

    fun clear() {
        _resultFlow.resetReplayCache()
    }
}
