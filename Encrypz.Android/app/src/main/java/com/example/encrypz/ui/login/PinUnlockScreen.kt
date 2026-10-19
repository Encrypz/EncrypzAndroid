package com.example.encrypz.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import com.example.encrypz.theme.*

@Composable
internal fun PinUnlockScreen(email: String, pin: String, error: String?, onDigit: (String) -> Unit,
    onBackspace: () -> Unit, onUnlock: () -> Unit, onUsePassword: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(EncrypzBlack).safeDrawingPadding(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 380.dp).fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Surface(shape = CircleShape, color = EncrypzBlue.copy(alpha = 0.15f)) {
                Icon(Icons.Default.Lock, null, tint = EncrypzBlue, modifier = Modifier.padding(20.dp).size(28.dp))
            }
            Text("Welcome back", style = MaterialTheme.typography.headlineMedium, color = EncrypzText)
            Text(email, style = MaterialTheme.typography.bodyMedium, color = EncrypzMuted)
            Text("Enter your PIN to unlock", style = MaterialTheme.typography.bodyLarge, color = EncrypzText)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(vertical = 10.dp).clearAndSetSemantics { contentDescription = "${pin.length} PIN digits entered" }) {
                repeat(6) { index ->
                    Surface(Modifier.size(14.dp), shape = CircleShape,
                        color = if (index < pin.length) EncrypzBlue else EncrypzInputBorder) {}
                }
            }
            Text(error ?: "4?6 digits", color = if (error != null) MaterialTheme.colorScheme.error else EncrypzMuted,
                style = MaterialTheme.typography.bodySmall)
            for (row in listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("", "0", "delete"))) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    row.forEach { key ->
                        if (key.isEmpty()) Spacer(Modifier.weight(1f).height(64.dp))
                        else FilledTonalButton(onClick = { if (key == "delete") onBackspace() else onDigit(key) },
                            modifier = Modifier.weight(1f).height(64.dp), shape = CircleShape,
                            enabled = if (key == "delete") pin.isNotEmpty() else pin.length < 6,
                            colors = ButtonDefaults.filledTonalButtonColors(containerColor = EncrypzInput, contentColor = EncrypzText)) {
                            if (key == "delete") Icon(Icons.AutoMirrored.Filled.Backspace, "Delete last digit")
                            else Text(key, style = MaterialTheme.typography.headlineSmall)
                        }
                    }
                }
            }
            Button(onClick = onUnlock, enabled = pin.length in 4..6,
                modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Unlock vault") }
            TextButton(onClick = onUsePassword) { Text("Use master password instead") }
        }
    }
}
