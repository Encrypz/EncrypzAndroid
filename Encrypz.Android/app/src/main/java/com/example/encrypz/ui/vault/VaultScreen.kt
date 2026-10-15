package com.example.encrypz.ui.vault

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.encrypz.theme.EncrypzTheme

data class VaultItem(val id: String, val name: String, val isFolder: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(
    onLogout: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Mock items for demonstration
    val items = listOf(
        VaultItem("1", "Documents", true),
        VaultItem("2", "Photos", true),
        VaultItem("3", "passwords.txt.enc", false),
        VaultItem("4", "tax_returns_2025.pdf.enc", false)
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Vault") },
                actions = {
                    IconButton(onClick = onLogout) {
                        Icon(Icons.Default.Logout, contentDescription = "Lock Vault")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { /* TODO: Upload file */ }) {
                Icon(Icons.Default.Add, contentDescription = "Upload")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            items(items) { item ->
                ListItem(
                    headlineContent = { Text(item.name) },
                    leadingContent = {
                        Icon(
                            imageVector = if (item.isFolder) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                            contentDescription = null,
                            tint = if (item.isFolder) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                        )
                    },
                    trailingContent = {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Encrypted",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                    }
                )
                Divider()
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun VaultScreenPreview() {
    EncrypzTheme {
        VaultScreen(onLogout = {})
    }
}
