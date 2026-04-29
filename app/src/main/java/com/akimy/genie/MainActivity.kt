package com.akimy.genie

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.akimy.genie.engine.DownloadState
import com.akimy.genie.engine.GenieModelConfig
import com.akimy.genie.engine.ModelDownloadManager
import com.akimy.genie.engine.ModelPrefs
import com.akimy.genie.service.ScreenMapStore
import com.akimy.genie.tools.ToolProfile
import com.akimy.genie.tools.ToolProfilePrefs
import com.akimy.genie.tools.VisualizerSceneStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "GenieMainActivity"

// ── Genie colour palette ────────────────────────────────────────────────
private val GeniePrimary = Color(0xFF6C63FF)
private val GenieSecondary = Color(0xFF38BDF8)
private val GenieAccent = Color(0xFF31E7B6)

private val GenieDarkScheme = darkColorScheme(
    primary = GeniePrimary,
    secondary = GenieSecondary,
    tertiary = GenieAccent,
    background = Color(0xFF0F1117),
    surface = Color(0xFF181A20),
    surfaceVariant = Color(0xFF23262F),
    onBackground = Color(0xFFF1F1F4),
    onSurface = Color(0xFFE4E4E9),
    onSurfaceVariant = Color(0xFF9CA3AF),
    error = Color(0xFFF87171),
    outline = Color(0xFF334155),
    outlineVariant = Color(0xFF1E293B),
)

private val GenieLightScheme = lightColorScheme(
    primary = GeniePrimary,
    secondary = Color(0xFF0284C7),
    tertiary = Color(0xFF059669),
    background = Color(0xFFF8FAFC),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF1F5F9),
    onBackground = Color(0xFF0F172A),
    onSurface = Color(0xFF1E293B),
    onSurfaceVariant = Color(0xFF64748B),
    error = Color(0xFFDC2626),
    outline = Color(0xFFCBD5E1),
    outlineVariant = Color(0xFFE2E8F0),
)

class MainActivity : ComponentActivity() {

    private val allPermissionsGranted = mutableStateOf(false)
    private val downloadManager by lazy { ModelDownloadManager(applicationContext) }
    private var storageSettingsLaunchInProgress = false

    private val runtimePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { continuePermissionFlowAfterRuntimePermissions() }

    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        storageSettingsLaunchInProgress = false
        recheckPermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
            navigationBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
        )
        super.onCreate(savedInstanceState)

        VisualizerSceneStore.initialize(applicationContext)
        ScreenMapStore.initialize(applicationContext)
        startPermissionFlow()

        setContent {
            val isDark = isSystemInDarkTheme()
            val colorScheme = if (isDark) GenieDarkScheme else GenieLightScheme

            MaterialTheme(colorScheme = colorScheme) {
                GenieSetupScreen(
                    allPermissionsGranted = allPermissionsGranted.value,
                    downloadManager = downloadManager,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        recheckPermissions()
    }

    private fun requestAllPermissions() {
        startPermissionFlow()
    }

    private fun startPermissionFlow() {
        val missingRuntimePermissions = buildList {
            if (!hasAudioPermission()) {
                add(Manifest.permission.RECORD_AUDIO)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission()) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (missingRuntimePermissions.isNotEmpty()) {
            runtimePermissionLauncher.launch(missingRuntimePermissions.toTypedArray())
            return
        }

        if (!Environment.isExternalStorageManager() && !storageSettingsLaunchInProgress) {
            storageSettingsLaunchInProgress = true
            val intent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName"),
            )
            manageStorageLauncher.launch(intent)
            return
        }

        recheckPermissions()
    }

    private fun continuePermissionFlowAfterRuntimePermissions() {
        if (hasAudioPermission() && hasNotificationPermission()) {
            startPermissionFlow()
        } else {
            recheckPermissions()
        }
    }

    private fun recheckPermissions() {
        val hasAudio = hasAudioPermission()
        val hasNotifications = hasNotificationPermission()
        val hasStorage = Environment.isExternalStorageManager()
        allPermissionsGranted.value = hasAudio && hasNotifications && hasStorage
        Log.d(TAG, "Permissions: audio=$hasAudio, notif=$hasNotifications, storage=$hasStorage")
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasNotificationPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Main Screen
// ─────────────────────────────────────────────────────────────────────────

@Composable
fun GenieSetupScreen(
    allPermissionsGranted: Boolean,
    downloadManager: ModelDownloadManager,
) {
    val context = LocalContext.current
    val downloadState by downloadManager.downloadState.collectAsState()

    // Persisted model selection
    var selectedModelId by remember {
        mutableStateOf(ModelPrefs.getSelectedModelId(context) ?: GenieModelConfig.DEFAULT.modelId)
    }
    var selectedToolProfileId by remember {
        mutableStateOf(ToolProfilePrefs.getSelectedProfile(context).id)
    }
    val selectedConfig = GenieModelConfig.ALL.firstOrNull { it.modelId == selectedModelId }
        ?: ModelPrefs.getSelectedConfig(context)
        ?: GenieModelConfig.DEFAULT
    val isModelDownloaded = remember(selectedModelId, downloadState) {
        selectedConfig.isDownloaded(context)
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(32.dp))

            // ── Logo ──
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(GeniePrimary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("🧞", fontSize = 36.sp)
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Genie",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "Autonomous AI Accessibility Agent",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(28.dp))

            // ── Permissions card ──
            if (!allPermissionsGranted) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.08f),
                    ),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "⚠️  Permissions Required",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Grant all requested permissions to continue.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // ── Model selection ──
            Text(
                text = "Select AI Model",
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
            )

            GenieModelConfig.ALL.forEach { config ->
                val isSelected = config.modelId == selectedModelId
                val alreadyDownloaded = config.isDownloaded(context)

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                        .then(
                            if (isSelected) Modifier.border(
                                2.dp,
                                GeniePrimary,
                                RoundedCornerShape(16.dp)
                            ) else Modifier
                        )
                        .clickable {
                            selectedModelId = config.modelId
                            ModelPrefs.setSelectedModelId(context, config.modelId)
                        },
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected)
                            GeniePrimary.copy(alpha = 0.07f)
                        else
                            MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = config.displayName,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "Download size: ${formatModelSize(config.sizeInBytes)}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (alreadyDownloaded) {
                            Text(
                                text = "✓ Ready",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = GenieAccent,
                            )
                        }
                    }
                }
            }

            // Custom Model selected via UI
            if (selectedModelId.startsWith("custom:")) {
                val customFilename = selectedModelId.substringAfter("custom:")
                val alreadyDownloaded = selectedConfig.isDownloaded(context)
                
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                        .border(2.dp, GeniePrimary, RoundedCornerShape(16.dp)),
                    colors = CardDefaults.cardColors(
                        containerColor = GeniePrimary.copy(alpha = 0.07f),
                    ),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Custom: $customFilename",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "Local File",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (alreadyDownloaded) {
                            Text(
                                text = "✓ Ready",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = GenieAccent,
                            )
                        }
                    }
                }
            }

            // Custom Import Button
            val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) { result ->
                val uri = result.data?.data
                if (result.resultCode == android.app.Activity.RESULT_OK && uri != null) {
                    // Extract filename from Uri (fallback to litertlm if not found)
                    var filename = "custom_model.litertlm"
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (cursor.moveToFirst() && nameIndex != -1) {
                            filename = cursor.getString(nameIndex)
                        }
                    }

                    if (!filename.endsWith(".litertlm", ignoreCase = true)) {
                        Toast.makeText(context, "File must be a .litertlm model", Toast.LENGTH_LONG).show()
                    } else {
                        val newCustomId = "custom:$filename"
                        selectedModelId = newCustomId
                        ModelPrefs.setSelectedModelId(context, newCustomId)
                        val newConfig = ModelPrefs.getSelectedConfig(context)!!
                        
                        kotlinx.coroutines.MainScope().launch {
                            downloadManager.importCustomModelUri(uri, newConfig)
                        }
                    }
                }
            }

            OutlinedButton(
                onClick = { 
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            val downloadsUri = Uri.parse("content://com.android.providers.downloads.documents/root/downloads")
                            putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, downloadsUri)
                        }
                    }
                    importLauncher.launch(intent) 
                },
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("Select Local Model (.litertlm)")
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Tool Profile",
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
            )

            Text(
                text = "Messaging is the default optimized loadout. Larger profiles expose more tools but use more model context.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
            )

            ToolProfile.values().forEach { profile ->
                val isSelected = profile.id == selectedToolProfileId
                val profileShape = RoundedCornerShape(16.dp)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                        .clip(profileShape)
                        .background(
                            if (isSelected)
                                GeniePrimary.copy(alpha = 0.07f)
                            else
                                MaterialTheme.colorScheme.surfaceVariant,
                        )
                        .border(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) GeniePrimary else MaterialTheme.colorScheme.outlineVariant,
                            shape = profileShape,
                        )
                        .clickable {
                            selectedToolProfileId = profile.id
                            ToolProfilePrefs.setSelectedProfile(context, profile)
                        }
                        .padding(16.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = profile.displayName,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "${profile.toolNames.size} tools",
                            fontSize = 12.sp,
                            color = if (isSelected) GenieAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = profile.description,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (isSelected) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = profile.toolNames.sorted().joinToString(", "),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Text(
                text = "Profile changes apply when the accessibility service starts or restarts.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
            )

            Spacer(modifier = Modifier.height(8.dp))

            // ── Download button / progress ──
            when {
                isModelDownloaded -> {
                    Text(
                        text = "✓ ${selectedConfig.displayName} is downloaded and ready.",
                        fontSize = 13.sp,
                        color = GenieAccent,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                downloadState is DownloadState.Downloading -> {
                    val dl = downloadState as DownloadState.Downloading
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        LinearProgressIndicator(
                            progress = { dl.progressPercent / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = GeniePrimary,
                            trackColor = MaterialTheme.colorScheme.outlineVariant,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Downloading… ${dl.progressPercent}%",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                else -> {
                    Button(
                        onClick = {
                            ModelPrefs.setSelectedModelId(context, selectedModelId)
                            kotlinx.coroutines.MainScope().launch {
                                downloadManager.ensureModelReady(selectedConfig)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = allPermissionsGranted,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = GeniePrimary),
                    ) {
                        Text("Download ${selectedConfig.displayName}")
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ── Enable accessibility service ──
            Button(
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = allPermissionsGranted && isModelDownloaded,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = GeniePrimary),
            ) {
                Text("Enable Genie Accessibility Service", fontSize = 15.sp)
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "After enabling the service, say \"Gemma\" to wake Genie.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

private fun formatModelSize(bytes: Long): String {
    val gb = bytes / 1_000_000_000.0
    return String.format("%.2f GB", gb)
}
