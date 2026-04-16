package com.akimy.genie

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

private const val TAG = "GenieMainActivity"

/**
 * Minimal MainActivity for Genie.
 *
 * This is NOT a chat UI — it's a one-time setup screen for:
 * 1. Granting RECORD_AUDIO permission
 * 2. Inputting HuggingFace access token (stored in encrypted SharedPreferences)
 * 3. Navigating to Accessibility Settings to enable the service
 *
 * After setup, the user never needs to open this activity again.
 */
class MainActivity : ComponentActivity() {

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        Log.d(TAG, "RECORD_AUDIO permission: $isGranted")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request audio permission
        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)

        setContent {
            MaterialTheme {
                GenieSetupScreen()
            }
        }
    }
}

@Composable
fun GenieSetupScreen() {
    val context = LocalContext.current
    var hfToken by remember { mutableStateOf("") }
    var savedToken by remember { mutableStateOf(getSavedToken(context)) }
    var showSaveConfirm by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(40.dp))

            // Header
            Text(
                text = "🧞 Genie",
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "Autonomous AI Accessibility Agent",
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.secondary,
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Status card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Setup Status",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    StatusRow(
                        label = "HuggingFace Token",
                        done = savedToken != null,
                    )
                    StatusRow(
                        label = "Accessibility Service",
                        done = false, // Can't check from here easily
                    )
                }
            }

            // HuggingFace token input
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "HuggingFace Access Token",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                    )
                    Text(
                        text = "Required to download the Gemma 4 model (gated access).",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = hfToken,
                        onValueChange = { hfToken = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("hf_xxx...") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            saveToken(context, hfToken)
                            savedToken = hfToken
                            showSaveConfirm = true
                            Toast.makeText(context, "Token saved securely", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = hfToken.isNotBlank(),
                    ) {
                        Text("Save Token")
                    }
                }
            }

            // Enable Accessibility Service button
            Button(
                onClick = {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Text("Enable Genie Accessibility Service")
            }

            // Info
            Text(
                text = "After enabling the service, say \"Gemma\" to activate Genie.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
fun StatusRow(label: String, done: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, fontSize = 14.sp)
        Text(
            text = if (done) "✅ Done" else "⚠️ Needed",
            fontSize = 14.sp,
            color = if (done) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.error,
        )
    }
}

private fun saveToken(context: android.content.Context, token: String) {
    try {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        val prefs = EncryptedSharedPreferences.create(
            "genie_secure_prefs",
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        prefs.edit().putString("hf_token", token).apply()
        Log.d("GenieMainActivity", "Token saved securely")
    } catch (e: Exception) {
        Log.e("GenieMainActivity", "Failed to save token: ${e.message}")
    }
}

private fun getSavedToken(context: android.content.Context): String? {
    return try {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        val prefs = EncryptedSharedPreferences.create(
            "genie_secure_prefs",
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        prefs.getString("hf_token", null)
    } catch (e: Exception) {
        null
    }
}
