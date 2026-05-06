package com.akimy.genie.tools

import android.content.Context
import com.akimy.genie.data.GenieDatabase
import com.akimy.genie.data.VisualizerSceneDao
import com.akimy.genie.data.VisualizerSceneEntity
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

private const val MAX_NODES = 24
private const val MAX_EDGES = 40
private const val MAX_LABEL_LEN = 120
private const val MAX_BOARD_OBJECTS = 80
private const val MAX_BOARD_STEPS = 24
private const val MAX_BOARD_TEXT_LEN = 240

@Serializable
data class VisualNode(
    val id: String,
    val label: String,
    val kind: String = "concept",
)

@Serializable
data class VisualEdge(
    val from: String,
    val to: String,
    val label: String? = null,
    val style: String? = null,
)

@Serializable
data class VisualizerScene(
    val sceneId: String,
    val diagramType: String,
    val title: String,
    val nodes: List<VisualNode>,
    val edges: List<VisualEdge>,
    val focusNodeIds: List<String> = emptyList(),
    val board: TeachingBoard? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)

data class SceneStoreResult(
    val ok: Boolean,
    val message: String,
)

data class SceneSnapshot(
    val scene: VisualizerScene,
    val layout: VisualizerSceneLayout,
)

data class VisualizerSceneMeta(
    val sceneId: String,
    val title: String,
    val diagramType: String,
    val updatedAt: Long,
)

object VisualizerSceneStore {
    private val scenes = ConcurrentHashMap<String, VisualizerScene>()
    private val layouts = ConcurrentHashMap<String, VisualizerSceneLayout>()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    @Volatile private var dao: VisualizerSceneDao? = null
    @Volatile private var hasLoadedPersisted = false

    fun initialize(context: Context) {
        if (dao == null) {
            dao = GenieDatabase.getInstance(context.applicationContext).visualizerSceneDao()
        }
        if (!hasLoadedPersisted) {
            loadPersistedScenes()
        }
    }

    fun createScene(
        sceneId: String,
        diagramType: String,
        title: String?,
        nodesJson: String?,
        edgesJson: String?,
    ): SceneStoreResult {
        if (scenes.containsKey(sceneId)) {
            return SceneStoreResult(false, "Scene '$sceneId' already exists")
        }

        val parsedNodes = parseNodes(nodesJson) ?: return SceneStoreResult(false, "Invalid 'nodes' JSON")
        val parsedEdges = parseEdges(edgesJson) ?: return SceneStoreResult(false, "Invalid 'edges' JSON")
        val validation = validateGraph(parsedNodes, parsedEdges)
        if (validation != null) return SceneStoreResult(false, validation)

        val scene = VisualizerScene(
            sceneId = sceneId,
            diagramType = diagramType,
            title = (title ?: "").take(MAX_LABEL_LEN),
            nodes = parsedNodes,
            edges = parsedEdges,
        )
        saveScene(scene)
        val layout = layouts[sceneId] ?: VisualizerLayoutEngine.layout(scene)
        return SceneStoreResult(
            true,
            "Scene '$sceneId' created (${parsedNodes.size} nodes, ${parsedEdges.size} edges, layout=${layout.layoutType})"
        )
    }

    fun updateScene(
        sceneId: String,
        title: String?,
        nodesJson: String?,
        edgesJson: String?,
    ): SceneStoreResult {
        val existing = scenes[sceneId] ?: return SceneStoreResult(false, "Scene '$sceneId' not found")

        val parsedNodes = if (nodesJson.isNullOrBlank()) existing.nodes else parseNodes(nodesJson)
            ?: return SceneStoreResult(false, "Invalid 'nodes' JSON")
        val parsedEdges = if (edgesJson.isNullOrBlank()) existing.edges else parseEdges(edgesJson)
            ?: return SceneStoreResult(false, "Invalid 'edges' JSON")

        val validation = validateGraph(parsedNodes, parsedEdges)
        if (validation != null) return SceneStoreResult(false, validation)

        val updated = existing.copy(
            title = (title ?: existing.title).take(MAX_LABEL_LEN),
            nodes = parsedNodes,
            edges = parsedEdges,
            updatedAt = System.currentTimeMillis(),
        )
        saveScene(updated)
        val layout = layouts[sceneId] ?: VisualizerLayoutEngine.layout(updated)
        return SceneStoreResult(
            true,
            "Scene '$sceneId' updated (${parsedNodes.size} nodes, ${parsedEdges.size} edges, layout=${layout.layoutType})"
        )
    }

    fun highlightScene(sceneId: String, focusCsv: String?): SceneStoreResult {
        val existing = scenes[sceneId] ?: return SceneStoreResult(false, "Scene '$sceneId' not found")
        val focus = focusCsv
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            ?: emptyList()

        if (focus.any { focusId -> existing.nodes.none { it.id == focusId } }) {
            return SceneStoreResult(false, "One or more focus node ids are missing in scene '$sceneId'")
        }

        val updated = existing.copy(
            focusNodeIds = focus,
            updatedAt = System.currentTimeMillis(),
        )
        saveScene(updated)
        return SceneStoreResult(true, "Scene '$sceneId' highlighted (${focus.size} nodes)")
    }

    fun teachWithBoard(
        sceneId: String,
        title: String?,
        theme: String?,
        objectsJson: String?,
        stepsJson: String?,
        narrationText: String?,
    ): SceneStoreResult {
        val objects = parseBoardObjects(objectsJson) ?: return SceneStoreResult(false, "Invalid 'objects' JSON")
        val steps = parseBoardSteps(stepsJson) ?: return SceneStoreResult(false, "Invalid 'steps' JSON")
        val validation = validateBoard(objects, steps)
        if (validation != null) return SceneStoreResult(false, validation)

        val requestedTheme = theme?.trim()?.take(40).orEmpty().ifBlank { "dark_classroom" }
        val requestedNarration = narrationText?.trim()?.take(600).orEmpty()
        val initialStepId = steps.firstOrNull()?.stepId
        val rawBoard = TeachingBoard(
            theme = requestedTheme,
            objects = objects,
            steps = steps,
            currentStepId = initialStepId,
            narrationText = requestedNarration.ifBlank { steps.firstOrNull()?.narration.orEmpty() },
        )
        val board = normalizeBoard(rawBoard)
        val existing = scenes[sceneId]
        val sceneTitle = (title ?: existing?.title ?: sceneId).trim().take(MAX_LABEL_LEN)
        val updated = if (existing == null) {
            VisualizerScene(
                sceneId = sceneId,
                diagramType = "board",
                title = sceneTitle,
                nodes = emptyList(),
                edges = emptyList(),
                board = board,
            )
        } else {
            existing.copy(
                diagramType = "board",
                title = sceneTitle,
                nodes = emptyList(),
                edges = emptyList(),
                focusNodeIds = emptyList(),
                board = board,
                updatedAt = System.currentTimeMillis(),
            )
        }
        saveScene(updated)
        val verb = if (existing == null) "created" else "updated"
        return SceneStoreResult(
            true,
            "Teaching board '$sceneId' $verb (${objects.size} objects, ${steps.size} steps)"
        )
    }

    fun boardAddObject(
        sceneId: String,
        objectId: String,
        objectType: String,
        text: String?,
        x: Float,
        y: Float,
        width: Float?,
        height: Float?,
        styleJson: String?,
        stepId: String?,
        animation: String?,
        pathData: String? = null,
    ): SceneStoreResult {
        val scene = scenes[sceneId]
        val existingBoard = scene?.board ?: TeachingBoard()
        if (existingBoard.objects.any { it.objectId == objectId }) {
            return SceneStoreResult(false, "Board object '$objectId' already exists in scene '$sceneId'")
        }

        val parsedStyle = parseBoardStyle(styleJson) ?: return SceneStoreResult(false, "Invalid 'style' JSON")
        val normalizedType = objectType.trim().lowercase()
        val size = defaultBoardSizeFor(normalizedType).copy(
            width = width ?: defaultBoardSizeFor(normalizedType).width,
            height = height ?: defaultBoardSizeFor(normalizedType).height,
        )
        val newObject = BoardObject(
            objectId = objectId,
            objectType = normalizedType,
            text = text?.trim()?.take(MAX_BOARD_TEXT_LEN).orEmpty(),
            position = BoardPoint(x = x, y = y),
            size = size,
            style = parsedStyle ?: BoardStyle(),
            stepId = stepId?.trim()?.takeIf { it.isNotEmpty() },
            animation = animation?.trim()?.take(40),
            pathData = pathData?.trim()?.take(2_000),
        )
        val updatedBoard = normalizeBoard(
            existingBoard.copy(objects = existingBoard.objects + newObject)
        )
        val validation = validateBoard(updatedBoard.objects, updatedBoard.steps)
        if (validation != null) return SceneStoreResult(false, validation)

        val updatedScene = (scene ?: VisualizerScene(
            sceneId = sceneId,
            diagramType = "board",
            title = sceneId.take(MAX_LABEL_LEN),
            nodes = emptyList(),
            edges = emptyList(),
            board = updatedBoard,
        )).copy(
            diagramType = "board",
            nodes = emptyList(),
            edges = emptyList(),
            board = updatedBoard,
            updatedAt = System.currentTimeMillis(),
        )
        saveScene(updatedScene)
        return SceneStoreResult(true, "Added board object '$objectId' to scene '$sceneId'")
    }

    fun boardUpdateObject(
        sceneId: String,
        objectId: String,
        objectType: String?,
        text: String?,
        x: Float?,
        y: Float?,
        width: Float?,
        height: Float?,
        styleJson: String?,
        stepId: String?,
        animation: String?,
    ): SceneStoreResult {
        val scene = requireBoardScene(sceneId) ?: return SceneStoreResult(false, "Board scene '$sceneId' not found")
        val board = scene.board ?: return SceneStoreResult(false, "Scene '$sceneId' is not a board scene")
        val existing = board.objects.firstOrNull { it.objectId == objectId }
            ?: return SceneStoreResult(false, "Board object '$objectId' not found in scene '$sceneId'")

        val replacementStyle = if (styleJson == null) existing.style else {
            parseBoardStyle(styleJson) ?: return SceneStoreResult(false, "Invalid 'style' JSON")
        }
        val requestedType = objectType?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: existing.objectType
        val updatedObject = existing.copy(
            objectType = requestedType,
            text = text?.trim()?.take(MAX_BOARD_TEXT_LEN) ?: existing.text,
            position = BoardPoint(
                x = x ?: existing.position.x,
                y = y ?: existing.position.y,
            ),
            size = BoardSize(
                width = width ?: existing.size.width,
                height = height ?: existing.size.height,
            ),
            style = replacementStyle,
            stepId = when {
                stepId == "__clear__" -> null
                stepId != null -> stepId.trim().takeIf { it.isNotEmpty() }
                else -> existing.stepId
            },
            animation = when {
                animation == "__clear__" -> null
                animation != null -> animation.trim().take(40)
                else -> existing.animation
            },
        )
        val updatedBoard = normalizeBoard(
            board.copy(objects = board.objects.map { if (it.objectId == objectId) updatedObject else it })
        )
        val validation = validateBoard(updatedBoard.objects, updatedBoard.steps)
        if (validation != null) return SceneStoreResult(false, validation)

        saveScene(
            scene.copy(
                board = updatedBoard,
                updatedAt = System.currentTimeMillis(),
            )
        )
        return SceneStoreResult(true, "Updated board object '$objectId' in scene '$sceneId'")
    }

    fun boardRemoveObject(sceneId: String, objectId: String): SceneStoreResult {
        val scene = requireBoardScene(sceneId) ?: return SceneStoreResult(false, "Board scene '$sceneId' not found")
        val board = scene.board ?: return SceneStoreResult(false, "Scene '$sceneId' is not a board scene")
        if (board.objects.none { it.objectId == objectId }) {
            return SceneStoreResult(false, "Board object '$objectId' not found in scene '$sceneId'")
        }

        val updatedBoard = normalizeBoard(
            board.copy(objects = board.objects.filterNot { it.objectId == objectId })
        )
        saveScene(
            scene.copy(
                board = updatedBoard,
                updatedAt = System.currentTimeMillis(),
            )
        )
        return SceneStoreResult(true, "Removed board object '$objectId' from scene '$sceneId'")
    }

    fun boardFocusObject(sceneId: String, objectId: String): SceneStoreResult {
        val scene = requireBoardScene(sceneId) ?: return SceneStoreResult(false, "Board scene '$sceneId' not found")
        val board = scene.board ?: return SceneStoreResult(false, "Scene '$sceneId' is not a board scene")
        if (board.objects.none { it.objectId == objectId }) {
            return SceneStoreResult(false, "Board object '$objectId' not found in scene '$sceneId'")
        }

        val updatedBoard = board.copy(focusObjectIds = listOf(objectId))
        saveScene(scene.copy(board = updatedBoard, updatedAt = System.currentTimeMillis()))
        return SceneStoreResult(true, "Focused board object '$objectId' in scene '$sceneId'")
    }

    fun boardRevealStep(sceneId: String, stepId: String): SceneStoreResult {
        val scene = requireBoardScene(sceneId) ?: return SceneStoreResult(false, "Board scene '$sceneId' not found")
        val board = scene.board ?: return SceneStoreResult(false, "Scene '$sceneId' is not a board scene")
        if (board.steps.none { it.stepId == stepId }) {
            return SceneStoreResult(false, "Step '$stepId' not found in scene '$sceneId'")
        }

        val updatedBoard = board.withCurrentStep(stepId)
        saveScene(scene.copy(board = updatedBoard, updatedAt = System.currentTimeMillis()))
        return SceneStoreResult(true, "Revealed board step '$stepId' in scene '$sceneId'")
    }

    fun boardNextStep(sceneId: String): SceneStoreResult {
        val scene = requireBoardScene(sceneId) ?: return SceneStoreResult(false, "Board scene '$sceneId' not found")
        val board = scene.board ?: return SceneStoreResult(false, "Scene '$sceneId' is not a board scene")
        if (board.steps.isEmpty()) return SceneStoreResult(false, "Scene '$sceneId' has no board steps")
        val updatedBoard = board.withNextStep()
        saveScene(scene.copy(board = updatedBoard, updatedAt = System.currentTimeMillis()))
        val stepLabel = updatedBoard.currentStepId ?: "none"
        return SceneStoreResult(true, "Advanced board '$sceneId' to step '$stepLabel'")
    }

    fun boardPrevStep(sceneId: String): SceneStoreResult {
        val scene = requireBoardScene(sceneId) ?: return SceneStoreResult(false, "Board scene '$sceneId' not found")
        val board = scene.board ?: return SceneStoreResult(false, "Scene '$sceneId' is not a board scene")
        if (board.steps.isEmpty()) return SceneStoreResult(false, "Scene '$sceneId' has no board steps")
        val updatedBoard = board.withPreviousStep()
        saveScene(scene.copy(board = updatedBoard, updatedAt = System.currentTimeMillis()))
        val stepLabel = updatedBoard.currentStepId ?: "none"
        return SceneStoreResult(true, "Moved board '$sceneId' back to step '$stepLabel'")
    }

    fun boardReplayStep(sceneId: String): SceneStoreResult {
        val scene = requireBoardScene(sceneId) ?: return SceneStoreResult(false, "Board scene '$sceneId' not found")
        val board = scene.board ?: return SceneStoreResult(false, "Scene '$sceneId' is not a board scene")
        val updatedBoard = board.withReplayStep()
        saveScene(scene.copy(board = updatedBoard, updatedAt = System.currentTimeMillis()))
        val stepLabel = updatedBoard.currentStepId ?: "none"
        return SceneStoreResult(true, "Replayed board step '$stepLabel' in scene '$sceneId'")
    }

    fun boardSetNarration(sceneId: String, narrationText: String): SceneStoreResult {
        val scene = requireBoardScene(sceneId) ?: return SceneStoreResult(false, "Board scene '$sceneId' not found")
        val board = scene.board ?: return SceneStoreResult(false, "Scene '$sceneId' is not a board scene")
        val updatedBoard = board.copy(narrationText = narrationText.trim().take(600))
        saveScene(scene.copy(board = updatedBoard, updatedAt = System.currentTimeMillis()))
        return SceneStoreResult(true, "Updated board narration for scene '$sceneId'")
    }

    fun renameScene(sceneId: String, newTitle: String): SceneStoreResult {
        val existing = scenes[sceneId] ?: return SceneStoreResult(false, "Scene '$sceneId' not found")
        val updated = existing.copy(
            title = newTitle.trim().take(MAX_LABEL_LEN),
            updatedAt = System.currentTimeMillis(),
        )
        saveScene(updated)
        return SceneStoreResult(true, "Scene '$sceneId' renamed")
    }

    fun clearScene(sceneId: String): SceneStoreResult {
        val removed = scenes.remove(sceneId)
        layouts.remove(sceneId)
        deleteScene(sceneId)
        return if (removed != null) {
            SceneStoreResult(true, "Scene '$sceneId' cleared")
        } else {
            SceneStoreResult(false, "Scene '$sceneId' not found")
        }
    }

    fun exportScene(sceneId: String): SceneStoreResult {
        val scene = scenes[sceneId] ?: return SceneStoreResult(false, "Scene '$sceneId' not found")
        val layout = layouts[sceneId]
        val payload = json.encodeToString(scene)
        val summary = if (scene.board != null) {
            val stepSummary = scene.board.currentStepId?.let { ", current_step=$it" }.orEmpty()
            "board objects=${scene.board.objects.size}, steps=${scene.board.steps.size}$stepSummary"
        } else if (layout != null) {
            "layout nodes=${layout.nodeLayouts.size}, edges=${layout.edgeLayouts.size}"
        } else {
            "layout unavailable"
        }
        return SceneStoreResult(true, "Scene '$sceneId' export payload: $payload\n$summary")
    }

    fun getSceneLayout(sceneId: String): VisualizerSceneLayout? = layouts[sceneId]

    fun getLatestSnapshot(): SceneSnapshot? {
        val latestScene = scenes.values.maxByOrNull { it.updatedAt } ?: return null
        val layout = layouts[latestScene.sceneId] ?: VisualizerLayoutEngine.layout(latestScene)
        return SceneSnapshot(latestScene, layout)
    }

    fun getSnapshot(sceneId: String): SceneSnapshot? {
        val scene = scenes[sceneId] ?: return null
        val layout = layouts[sceneId] ?: VisualizerLayoutEngine.layout(scene)
        return SceneSnapshot(scene, layout)
    }

    fun listScenes(): List<VisualizerSceneMeta> {
        return scenes.values
            .sortedByDescending { it.updatedAt }
            .map {
                VisualizerSceneMeta(
                    sceneId = it.sceneId,
                    title = it.title,
                    diagramType = it.diagramType,
                    updatedAt = it.updatedAt,
                )
            }
    }

    private fun requireBoardScene(sceneId: String): VisualizerScene? {
        val scene = scenes[sceneId] ?: return null
        return if (scene.board != null || scene.diagramType == "board") scene else null
    }

    private fun saveScene(scene: VisualizerScene) {
        scenes[scene.sceneId] = scene
        layouts[scene.sceneId] = VisualizerLayoutEngine.layout(scene)
        persistScene(scene)
    }

    private fun normalizeBoard(board: TeachingBoard): TeachingBoard {
        val validFocus = board.focusObjectIds.filter { focusId ->
            board.objects.any { it.objectId == focusId }
        }
        val normalized = board.copy(focusObjectIds = validFocus)
        if (normalized.steps.isEmpty()) {
            return normalized.copy(currentStepId = null)
        }

        val stepId = normalized.currentStepId
            ?.takeIf { current -> normalized.steps.any { it.stepId == current } }
            ?: normalized.steps.first().stepId
        return normalized.withCurrentStep(stepId)
    }

    private fun parseNodes(raw: String?): List<VisualNode>? {
        if (raw.isNullOrBlank()) return emptyList()
        val arr = json.parseToJsonElement(raw) as? JsonArray ?: return null
        val nodes = arr.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj["id"]?.toString()?.trim('"')?.trim().orEmpty()
            val label = obj["label"]?.toString()?.trim('"')?.trim().orEmpty()
            val kind = obj["kind"]?.toString()?.trim('"')?.trim().orEmpty().ifBlank { "concept" }
            if (id.isBlank() || label.isBlank()) null else VisualNode(id, label.take(MAX_LABEL_LEN), kind)
        }
        return if (nodes.size == arr.size) nodes else null
    }

    private fun parseEdges(raw: String?): List<VisualEdge>? {
        if (raw.isNullOrBlank()) return emptyList()
        val arr = json.parseToJsonElement(raw) as? JsonArray ?: return null
        val edges = arr.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val from = obj["from"]?.toString()?.trim('"')?.trim().orEmpty()
            val to = obj["to"]?.toString()?.trim('"')?.trim().orEmpty()
            val label = obj["label"]?.toString()?.trim('"')?.trim()
            val style = obj["style"]?.toString()?.trim('"')?.trim()
            if (from.isBlank() || to.isBlank()) null else VisualEdge(from, to, label?.take(MAX_LABEL_LEN), style)
        }
        return if (edges.size == arr.size) edges else null
    }

    private fun parseBoardObjects(raw: String?): List<BoardObject>? {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<BoardObject>>(raw) }.getOrNull()
    }

    private fun parseBoardSteps(raw: String?): List<BoardStep>? {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<BoardStep>>(raw) }.getOrNull()
    }

    private fun parseBoardStyle(raw: String?): BoardStyle? {
        if (raw.isNullOrBlank()) return BoardStyle()
        return runCatching { json.decodeFromString<BoardStyle>(raw) }.getOrNull()
    }

    private fun validateGraph(nodes: List<VisualNode>, edges: List<VisualEdge>): String? {
        if (nodes.size > MAX_NODES) return "Too many nodes: ${nodes.size} > $MAX_NODES"
        if (edges.size > MAX_EDGES) return "Too many edges: ${edges.size} > $MAX_EDGES"

        val ids = nodes.map { it.id }.toSet()
        if (ids.size != nodes.size) return "Node ids must be unique"

        if (edges.any { it.from !in ids || it.to !in ids }) {
            return "All edges must reference existing node ids"
        }

        return null
    }

    private fun validateBoard(objects: List<BoardObject>, steps: List<BoardStep>): String? {
        if (objects.size > MAX_BOARD_OBJECTS) {
            return "Too many board objects: ${objects.size} > $MAX_BOARD_OBJECTS"
        }
        if (steps.size > MAX_BOARD_STEPS) {
            return "Too many board steps: ${steps.size} > $MAX_BOARD_STEPS"
        }
        if (objects.map { it.objectId }.toSet().size != objects.size) {
            return "Board object ids must be unique"
        }
        if (steps.map { it.stepId }.toSet().size != steps.size) {
            return "Board step ids must be unique"
        }

        val allowedTypes = setOf("title", "text", "box", "card", "circle", "line", "arrow", "code")
        if (objects.any { it.objectType.lowercase() !in allowedTypes }) {
            return "Board object type must be one of: ${allowedTypes.joinToString()}"
        }
        if (objects.any { it.text.length > MAX_BOARD_TEXT_LEN }) {
            return "Board object text is too long. Keep each object text under $MAX_BOARD_TEXT_LEN characters"
        }

        val stepIds = steps.map { it.stepId }.toSet()
        if (stepIds.isNotEmpty() && objects.any { it.stepId != null && it.stepId !in stepIds }) {
            return "Every board object step_id must reference a declared step"
        }

        return null
    }

    private fun loadPersistedScenes() {
        val localDao = dao ?: return
        val persisted = runBlocking(Dispatchers.IO) {
            localDao.getAllByRecent()
        }
        scenes.clear()
        layouts.clear()
        persisted.forEach { row ->
            try {
                val scene = json.decodeFromString<VisualizerScene>(row.sceneJson)
                scenes[scene.sceneId] = scene
                layouts[scene.sceneId] = VisualizerLayoutEngine.layout(scene)
            } catch (_: Exception) {
                // Skip malformed persisted rows.
            }
        }
        hasLoadedPersisted = true
    }

    private fun persistScene(scene: VisualizerScene) {
        val localDao = dao ?: return
        val payload = json.encodeToString(scene)
        val row = VisualizerSceneEntity(
            sceneId = scene.sceneId,
            title = scene.title,
            diagramType = scene.diagramType,
            updatedAt = scene.updatedAt,
            sceneJson = payload,
        )
        runBlocking(Dispatchers.IO) {
            localDao.upsert(row)
        }
    }

    private fun deleteScene(sceneId: String) {
        val localDao = dao ?: return
        runBlocking(Dispatchers.IO) {
            localDao.deleteById(sceneId)
        }
    }
}
