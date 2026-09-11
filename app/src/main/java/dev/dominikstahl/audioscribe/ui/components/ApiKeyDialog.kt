package dev.dominikstahl.audioscribe.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dominikstahl.audioscribe.security.SecureKeyManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiKeyDialog(
    currentKey: String?,
    currentModel: String,
    workspaceKey: String?,
    onDismiss: () -> Unit,
    onSaveAndValidate: (String, (Boolean, String) -> Unit) -> Unit,
    onDeleteKey: () -> Unit,
    onModelChange: (String) -> Unit
) {
    val context = LocalContext.current
    var keyInput by remember { mutableStateOf(currentKey ?: "") }
    var isPasswordVisible by remember { mutableStateOf(false) }
    var isValidating by remember { mutableStateOf(false) }
    var validationMessage by remember { mutableStateOf<String?>(null) }
    var isSuccess by remember { mutableStateOf<Boolean?>(null) }

    var modelDropdownExpanded by remember { mutableStateOf(false) }
    var selectedModel by remember { mutableStateOf(currentModel) }

    AlertDialog(
        onDismissRequest = {
            if (!isValidating) onDismiss()
        },
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(
                            MaterialTheme.colorScheme.primaryContainer,
                            RoundedCornerShape(8.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Key,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column {
                    Text(
                        text = "Gemini API Key (BYOK)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Your key is stored securely in Android KeyStore",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "AudioScribe performs audio transcriptions directly with Google Gemini using your personal API key without intermediary servers.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Quick Link to Google AI Studio
                OutlinedButton(
                    onClick = {
                        val intent = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://aistudio.google.com/app/apikey")
                        )
                        context.startActivity(intent)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("get_key_link_button"),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Get free key from Google AI Studio")
                }

                // If workspace key is available in build
                if (!workspaceKey.isNullOrBlank() && keyInput.isBlank()) {
                    OutlinedButton(
                        onClick = {
                            keyInput = workspaceKey
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("use_workspace_key_button"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(text = "Paste Detected Workspace Key")
                    }
                }

                // Key Input
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = {
                        keyInput = it
                        validationMessage = null
                        isSuccess = null
                    },
                    label = { Text("Gemini API Key") },
                    placeholder = { Text("AIzaSy...") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("api_key_input"),
                    visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(
                            onClick = { isPasswordVisible = !isPasswordVisible },
                            modifier = Modifier.testTag("toggle_key_visibility")
                        ) {
                            Icon(
                                imageVector = if (isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (isPasswordVisible) "Hide key" else "Show key"
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (keyInput.isNotBlank() && !isValidating) {
                                isValidating = true
                                validationMessage = null
                                onSaveAndValidate(keyInput) { success, msg ->
                                    isValidating = false
                                    isSuccess = success
                                    validationMessage = msg
                                }
                            }
                        }
                    ),
                    shape = RoundedCornerShape(10.dp)
                )

                // Model Selector
                ExposedDropdownMenuBox(
                    expanded = modelDropdownExpanded,
                    onExpandedChange = { modelDropdownExpanded = it }
                ) {
                    OutlinedTextField(
                        value = selectedModel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Gemini Model") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelDropdownExpanded) },
                        modifier = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth()
                            .testTag("model_selector"),
                        shape = RoundedCornerShape(10.dp)
                    )
                    ExposedDropdownMenu(
                        expanded = modelDropdownExpanded,
                        onDismissRequest = { modelDropdownExpanded = false }
                    ) {
                        SecureKeyManager.AVAILABLE_MODELS.forEach { model ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(model, fontWeight = FontWeight.Medium)
                                        Text(
                                            when (model) {
                                                "gemini-2.5-flash" -> "Recommended: Fast & exact audio transcription"
                                                "gemini-3.5-flash" -> "Advanced multimodal flash model"
                                                "gemini-1.5-flash" -> "Legacy model (may be sunset in some regions)"
                                                else -> ""
                                            },
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                onClick = {
                                    selectedModel = model
                                    onModelChange(model)
                                    modelDropdownExpanded = false
                                }
                            )
                        }
                    }
                }

                // Fallback information
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Offline Fallback Mode",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "If no Gemini API key is entered, AudioScribe uses the Android on-device speech recognizer and synthesizer as a local fallback.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Validation feedback banner
                AnimatedVisibility(visible = validationMessage != null) {
                    val valid = isSuccess == true
                    Surface(
                        color = if (valid) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = if (valid) Icons.Default.CheckCircle else Icons.Default.Error,
                                contentDescription = null,
                                tint = if (valid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = validationMessage ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (valid) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (keyInput.isNotBlank()) {
                        isValidating = true
                        validationMessage = null
                        onSaveAndValidate(keyInput) { success, msg ->
                            isValidating = false
                            isSuccess = success
                            validationMessage = msg
                            if (success) {
                                onDismiss()
                            }
                        }
                    }
                },
                enabled = keyInput.isNotBlank() && !isValidating,
                modifier = Modifier.testTag("validate_save_button"),
                shape = RoundedCornerShape(10.dp)
            ) {
                if (isValidating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Validating...")
                } else {
                    Text("Validate & Save")
                }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!currentKey.isNullOrBlank()) {
                    IconButton(
                        onClick = {
                            onDeleteKey()
                            keyInput = ""
                            validationMessage = "API Key deleted from secure storage"
                            isSuccess = true
                        },
                        modifier = Modifier.testTag("delete_key_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete key",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
                TextButton(
                    onClick = onDismiss,
                    enabled = !isValidating,
                    modifier = Modifier.testTag("cancel_key_button")
                ) {
                    Text("Cancel")
                }
            }
        },
        shape = RoundedCornerShape(18.dp)
    )
}
