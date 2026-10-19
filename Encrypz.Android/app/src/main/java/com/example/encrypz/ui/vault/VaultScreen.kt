package com.example.encrypz.ui.vault

import com.example.encrypz.drive.GoogleDriveSyncManager
import androidx.compose.foundation.layout.heightIn

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.ScreenLockPortrait
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.encrypz.theme.EncrypzBlack
import com.example.encrypz.theme.EncrypzBlue
import com.example.encrypz.theme.EncrypzBlueBright
import com.example.encrypz.theme.EncrypzBlueSoft
import com.example.encrypz.theme.EncrypzBorder
import com.example.encrypz.theme.EncrypzBorderGlow
import com.example.encrypz.theme.EncrypzDanger
import com.example.encrypz.theme.EncrypzDangerBg
import com.example.encrypz.theme.EncrypzDangerBorder
import com.example.encrypz.theme.EncrypzDangerLight
import com.example.encrypz.theme.EncrypzDeepBlack
import com.example.encrypz.theme.EncrypzEmeraldBright
import com.example.encrypz.theme.EncrypzGlassBorder
import com.example.encrypz.theme.EncrypzGlowCyan
import com.example.encrypz.theme.EncrypzInput
import com.example.encrypz.theme.EncrypzInputBorder
import com.example.encrypz.theme.EncrypzMuted
import com.example.encrypz.theme.EncrypzPanel
import com.example.encrypz.theme.EncrypzPurple
import com.example.encrypz.theme.EncrypzSecondaryBg
import com.example.encrypz.theme.EncrypzSubtleText
import com.example.encrypz.theme.EncrypzSuccessBg
import com.example.encrypz.theme.EncrypzSuccessBorder
import com.example.encrypz.theme.EncrypzSurface
import com.example.encrypz.theme.EncrypzText
import com.example.encrypz.theme.EncrypzTheme
import com.example.encrypz.ui.components.EncrypzAmbientBackground
import com.example.encrypz.ui.components.EncrypzBentoCard
import com.example.encrypz.ui.components.EncrypzCardIconBox
import com.example.encrypz.ui.components.EncrypzSecurityBadgesRow
import com.example.encrypz.ui.components.EncrypzSpecsBox
import com.example.encrypz.ui.components.EncrypzStatusBadge

// Kept for backwards compatibility
data class VaultItem(val id: String, val name: String, val isFolder: Boolean)

private enum class VaultTab(val label: String) {
    PHOTOS("Photos"),
    FILES("Files"),
    RECYCLE_BIN("Recycle Bin"),
    SECURITY_PROFILE("Security"),
    NOTIFICATIONS("Notifications")
}

@Composable
private fun NotificationsTabContent() {
    val context = LocalContext.current
    val notifications = com.example.encrypz.AppNotifications.items
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Notifications", style = MaterialTheme.typography.titleMedium, color = EncrypzText)
                TextButton(onClick = { com.example.encrypz.AppNotifications.markAsRead(context) },
                    enabled = com.example.encrypz.AppNotifications.unreadCount > 0) { Text("Mark all as read") }
            }
        }
        if (notifications.isEmpty()) item { Text("You're all caught up", color = EncrypzMuted) }
        items(notifications, key = { it.id }) { notification ->
            Surface(color = if (notification.isRead) EncrypzPanel else EncrypzBlue.copy(alpha = 0.12f),
                shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(notification.message, color = if (notification.isRead) EncrypzMuted else EncrypzText,
                        style = MaterialTheme.typography.bodyMedium)
                    if (!notification.isRead) TextButton(onClick = {
                        com.example.encrypz.AppNotifications.markAsRead(context, notification.id)
                    }, modifier = Modifier.align(Alignment.End)) { Text("Mark as read") }
                    else Text("Read", style = MaterialTheme.typography.labelSmall, color = EncrypzMuted,
                        modifier = Modifier.align(Alignment.End).padding(top = 8.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(
    onLogout: () -> Unit,
    onRequestDrivePermission: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: VaultViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val copyToClipboard: (String, String) -> Unit = { label, text ->
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    var currentTab by rememberSaveable { mutableStateOf(VaultTab.PHOTOS) }
    var showAddSheet by rememberSaveable { mutableStateOf(false) }
    var showCreateFolderDialog by rememberSaveable { mutableStateOf(false) }
    var showCreateNoteDialog by rememberSaveable { mutableStateOf(false) }
    var showConfigBackupDialog by rememberSaveable { mutableStateOf(false) }
    var showPinSetupDialog by rememberSaveable { mutableStateOf(false) }
    var selectedPhotoId by remember { mutableStateOf<String?>(null) }
    var photoToMove by remember { mutableStateOf<VaultFileEntry?>(null) }
    var photoToDelete by remember { mutableStateOf<VaultFileEntry?>(null) }
    var selectedFileForDetails by remember { mutableStateOf<VaultFileEntry?>(null) }
    var fullScreenPreviewFile by remember { mutableStateOf<VaultFileEntry?>(null) }
    var selectedFolderForDetails by remember { mutableStateOf<VaultFolder?>(null) }
    var filePendingDecryptedExport by remember { mutableStateOf<VaultFileEntry?>(null) }

    // Ensure local encrypted vault storage and saved Google account email are loaded
    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.ensureLocalStorageLoaded(context)
        if (state.connectedGoogleEmail.isBlank()) {
            val saved = com.example.encrypz.auth.GoogleAuthConfig.getSavedGoogleEmail(context)
            if (!saved.isNullOrBlank()) {
                viewModel.connectGoogleAccount(saved, context)
            }
        }
    }

    // Launcher for Google OAuth Drive scope consent (UserRecoverableAuthException)
    val driveConsentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val granted = result.resultCode == android.app.Activity.RESULT_OK
        viewModel.onDriveConsentResult(context, granted)
    }

    // Launcher for saving AES-256-GCM encrypted .enc file directly into Google Drive via DocumentsProvider
    val driveDocumentSaverLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val targetUri = if (result.resultCode == android.app.Activity.RESULT_OK) {
            result.data?.data
        } else {
            null
        }
        viewModel.onDriveDocumentSaverResult(context, targetUri)
    }

    // Launcher for exporting a decrypted copy of a vault file
    val decryptedExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val targetFile = filePendingDecryptedExport
        filePendingDecryptedExport = null
        if (result.resultCode == android.app.Activity.RESULT_OK && targetFile != null) {
            val destinationUri = result.data?.data
            if (destinationUri != null) {
                viewModel.exportDecryptedFileToUri(context, targetFile, destinationUri)
            }
        }
    }

    // Automatically launch Google Drive OAuth consent dialog when needed
    androidx.compose.runtime.LaunchedEffect(state.pendingDriveConsentIntent) {
        val consentIntent = state.pendingDriveConsentIntent
        if (consentIntent != null) {
            try {
                driveConsentLauncher.launch(consentIntent)
            } catch (_: Exception) {
                viewModel.onDriveConsentResult(context, false)
            }
        }
    }

    // Automatically launch Google Drive Document Saver (.enc) when triggered
    androidx.compose.runtime.LaunchedEffect(state.pendingDriveSaverFileId, state.pendingDriveSaverFileName) {
        val saverFileId = state.pendingDriveSaverFileId
        val saverFileName = state.pendingDriveSaverFileName
        if (!saverFileId.isNullOrBlank() && !saverFileName.isNullOrBlank()) {
            try {
                val saveIntent = com.example.encrypz.drive.GoogleDriveSyncManager
                    .createGoogleDriveSaveDocumentIntent(saverFileName)
                driveDocumentSaverLauncher.launch(saveIntent)
            } catch (_: Exception) {
                viewModel.onDriveDocumentSaverResult(context, null)
            }
        }
    }

    // Handle Android Back button inside nested folders
    BackHandler(enabled = currentTab == VaultTab.FILES && state.breadcrumbs.size > 1) {
        viewModel.navigateUp()
    }

    // Native Android Document Picker to encrypt real files, generate visual previews, and save .enc to Google Drive
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val picked = readPickedUriForVault(context, uri)
            viewModel.encryptAndAddFile(
                fileName = picked.fileName,
                rawBytes = picked.rawBytes,
                thumbnailBytes = picked.thumbnailBytes,
                originalSizeBytes = picked.originalSizeBytes,
                context = context,
                autoSaveToGoogleDrive = true
            )
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = EncrypzBlack,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(EncrypzBlack.copy(alpha = 0.95f))
            ) {
                TopAppBar(
                    navigationIcon = {
                        if (currentTab == VaultTab.FILES && state.breadcrumbs.size > 1) {
                            IconButton(onClick = { viewModel.navigateUp() }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Navigate up",
                                    tint = EncrypzText
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .padding(start = 16.dp, end = 4.dp)
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(Color(0xFF013D83)),
                                contentAlignment = Alignment.Center
                            ) {
                                androidx.compose.foundation.Image(
                                    painter = androidx.compose.ui.res.painterResource(id = com.example.encrypz.R.drawable.encrypz_logo),
                                    contentDescription = "Encrypz",
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    },
                    title = {
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = when (currentTab) {
                                        VaultTab.PHOTOS -> "Photos"
                                        VaultTab.FILES -> state.breadcrumbs.lastOrNull()?.name ?: "Files"
                                        VaultTab.RECYCLE_BIN -> "Recycle Bin"
                                        VaultTab.SECURITY_PROFILE -> "Security & Profile"
                                        VaultTab.NOTIFICATIONS -> "Notifications"
                                    },
                                    style = MaterialTheme.typography.titleLarge,
                                    color = EncrypzText,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                text = state.connectedGoogleEmail.ifBlank { "Encrypted and private · Zero-Knowledge" },
                                style = MaterialTheme.typography.labelSmall,
                                color = EncrypzMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { viewModel.syncVaultWithGoogleDrive(context) }
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudDone,
                                contentDescription = "Sync with Google Drive",
                                tint = EncrypzEmeraldBright
                            )
                        }
                        BadgedBox(
                            badge = {
                                if (com.example.encrypz.AppNotifications.unreadCount > 0) {
                                    Badge(containerColor = EncrypzBlue) {
                                        Text(com.example.encrypz.AppNotifications.unreadCount.toString())
                                    }
                                }
                            }
                        ) {
                            IconButton(onClick = { currentTab = VaultTab.NOTIFICATIONS }) {
                                Icon(
                                    imageVector = Icons.Default.Notifications,
                                    contentDescription = "Notifications",
                                    tint = EncrypzText
                                )
                            }
                        }
                        IconButton(
                            onClick = {
                                viewModel.lockVault()
                                onLogout()
                            }
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Logout,
                                contentDescription = "Lock Vault",
                                tint = EncrypzMuted
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = EncrypzText
                    )
                )
                HorizontalDivider(color = EncrypzBorder)
            }
        },
        bottomBar = {
            Column {
                HorizontalDivider(color = EncrypzBorder)
                NavigationBar(
                    containerColor = EncrypzSurface,
                    contentColor = EncrypzText,
                    tonalElevation = 0.dp
                ) {
                    NavigationBarItem(
                        selected = currentTab == VaultTab.PHOTOS,
                        onClick = {
                            currentTab = VaultTab.PHOTOS
                            viewModel.selectCategory(VaultCategoryFilter.ALL)
                        },
                        icon = {
                            Icon(
                                imageVector = Icons.Default.PhotoLibrary,
                                contentDescription = "Photos"
                            )
                        },
                        label = { Text("Photos") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = EncrypzBlue,
                            selectedTextColor = EncrypzBlue,
                            indicatorColor = EncrypzSecondaryBg,
                            unselectedIconColor = EncrypzMuted,
                            unselectedTextColor = EncrypzMuted
                        )
                    )

                    NavigationBarItem(
                        selected = currentTab == VaultTab.FILES,
                        onClick = {
                            currentTab = VaultTab.FILES
                            viewModel.selectCategory(VaultCategoryFilter.ALL)
                        },
                        icon = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                                contentDescription = "Files"
                            )
                        },
                        label = { Text("Files") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = EncrypzBlue,
                            selectedTextColor = EncrypzBlue,
                            indicatorColor = EncrypzSecondaryBg,
                            unselectedIconColor = EncrypzMuted,
                            unselectedTextColor = EncrypzMuted
                        )
                    )

                    val deletedCount = state.deletedFiles.size + state.deletedFolders.size
                    NavigationBarItem(
                        selected = currentTab == VaultTab.RECYCLE_BIN,
                        onClick = { currentTab = VaultTab.RECYCLE_BIN },
                        icon = {
                            BadgedBox(
                                badge = {
                                    if (deletedCount > 0) {
                                        Badge(
                                            containerColor = EncrypzBlue,
                                            contentColor = EncrypzText
                                        ) {
                                            Text(deletedCount.toString())
                                        }
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = "Recycle Bin"
                                )
                            }
                        },
                        label = { Text("Recycle Bin") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = EncrypzBlue,
                            selectedTextColor = EncrypzBlue,
                            indicatorColor = EncrypzSecondaryBg,
                            unselectedIconColor = EncrypzMuted,
                            unselectedTextColor = EncrypzMuted
                        )
                    )

                    NavigationBarItem(
                        selected = currentTab == VaultTab.SECURITY_PROFILE,
                        onClick = { currentTab = VaultTab.SECURITY_PROFILE },
                        icon = {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = "Security & Profile"
                            )
                        },
                        label = { Text("Security") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = EncrypzBlue,
                            selectedTextColor = EncrypzBlue,
                            indicatorColor = EncrypzSecondaryBg,
                            unselectedIconColor = EncrypzMuted,
                            unselectedTextColor = EncrypzMuted
                        )
                    )
                }
            }
        },
        floatingActionButton = {
            if (currentTab == VaultTab.PHOTOS || currentTab == VaultTab.FILES) {
                ExtendedFloatingActionButton(
                    onClick = { showAddSheet = true },
                    shape = RoundedCornerShape(100.dp),
                    containerColor = EncrypzBlue,
                    contentColor = EncrypzText
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Upload")
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Add files",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    ) { innerPadding ->
        EncrypzAmbientBackground(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                when (currentTab) {
                    VaultTab.PHOTOS -> PhotosTimeline(
                        state = state, viewModel = viewModel,
                        onFileClick = { if (it.category == VaultCategoryFilter.PHOTOS) selectedPhotoId = it.id else fullScreenPreviewFile = it },
                        onAddClick = { showAddSheet = true }
                    )
                    VaultTab.FILES -> MyVaultTabContent(
                        state = state,
                        viewModel = viewModel,
                        photosOnly = false,
                        onSearchQueryChange = viewModel::updateSearchQuery,
                        onSelectCategory = { category ->
                            if (category != VaultCategoryFilter.PHOTOS) viewModel.selectCategory(category)
                        },
                        onToggleViewMode = viewModel::toggleViewMode,
                        onBreadcrumbClick = viewModel::navigateToBreadcrumb,
                        onFolderClick = viewModel::navigateToFolder,
                        onFolderMoreClick = { selectedFolderForDetails = it },
                        onFileClick = { selectedFileForDetails = it },
                        onAddClick = { showAddSheet = true }
                    )

                    VaultTab.RECYCLE_BIN -> RecycleBinTabContent(
                        state = state,
                        onRestoreFolder = { viewModel.restoreFolder(it, context) },
                        onDeleteFolderPermanently = { viewModel.deleteFolderPermanently(it, context) },
                        onRestoreFile = { viewModel.restoreFile(it, context) },
                        onDeleteFilePermanently = { viewModel.deleteFilePermanently(it, context) },
                        onEmptyRecycleBin = { viewModel.emptyRecycleBin(context) }
                    )

                    VaultTab.SECURITY_PROFILE -> SecurityProfileTabContent(
                        state = state,
                        onRequestDrivePermission = onRequestDrivePermission,
                        onToggleScreenshotProtection = viewModel::setScreenshotProtection,
                        onConfigurePinClick = { showPinSetupDialog = true },
                        onRemovePinClick = { viewModel.removeUnlockPin(context) },
                        onSelectAutoLockTimeout = viewModel::setAutoLockTimeout,
                        onShowBackupConfig = { showConfigBackupDialog = true },
                        onLockVault = {
                            viewModel.lockVault()
                            onLogout()
                        }
                    )
                    VaultTab.NOTIFICATIONS -> NotificationsTabContent()
                }
            }
        }
    }

    // Bottom Sheet: Add to Vault (Upload File, Create Secure Note, New Folder, Sync/Import from Google Drive)
    if (showAddSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAddSheet = false },
            containerColor = EncrypzPanel,
            contentColor = EncrypzText,
            scrimColor = Color.Black.copy(alpha = 0.7f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Add to Zero-Knowledge Vault",
                    style = MaterialTheme.typography.titleLarge,
                    color = EncrypzText
                )
                Text(
                    text = "Files are encrypted client-side with AES-256-GCM (.enc) and saved to your Google Drive.",
                    style = MaterialTheme.typography.bodySmall,
                    color = EncrypzMuted
                )

                Spacer(Modifier.height(4.dp))

                AddActionRowCard(
                    icon = Icons.Default.UploadFile,
                    title = "Encrypt & Save File to Google Drive",
                    subtitle = "Pick any photo, PDF, video, or document to encrypt (.enc) & save to Drive",
                    tint = EncrypzBlue,
                    onClick = {
                        showAddSheet = false
                        filePickerLauncher.launch("*/*")
                    }
                )

                AddActionRowCard(
                    icon = Icons.AutoMirrored.Filled.NoteAdd,
                    title = "Create Encrypted Text / Secret Note",
                    subtitle = "Encrypt passwords or recovery phrases (.enc) & save to Google Drive",
                    tint = EncrypzEmeraldBright,
                    onClick = {
                        showAddSheet = false
                        showCreateNoteDialog = true
                    }
                )

                AddActionRowCard(
                    icon = Icons.Default.CreateNewFolder,
                    title = "New Encrypted Folder",
                    subtitle = "Organize files with encrypted folder metadata",
                    tint = EncrypzBlueBright,
                    onClick = {
                        showAddSheet = false
                        showCreateFolderDialog = true
                    }
                )

                AddActionRowCard(
                    icon = Icons.Default.CloudDone,
                    title = "Sync / Import Encrypted (.enc) from Google Drive",
                    subtitle = "Pull encrypted .enc files from your Google Drive vault",
                    tint = EncrypzGlowCyan,
                    onClick = {
                        showAddSheet = false
                        viewModel.syncVaultWithGoogleDrive(context)
                    }
                )
            }
        }
    }

    selectedPhotoId?.let { photoId ->
        val photos = remember(state.files) {
            val parser = java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.US)
            state.files.filter { !it.isDeleted && it.category == VaultCategoryFilter.PHOTOS }
                .sortedByDescending { runCatching { parser.parse(it.uploadedAt)?.time }.getOrNull() ?: 0L }
        }
        if (photos.any { it.id == photoId }) {
            PhotoViewer(photos = photos, initialId = photoId, viewModel = viewModel,
                onDismiss = { selectedPhotoId = null },
                onDownload = { file ->
                    filePendingDecryptedExport = file
                    decryptedExportLauncher.launch(GoogleDriveSyncManager.createDecryptedExportDocumentIntent(file.cleanFileName))
                },
                onMove = { file -> selectedPhotoId = null; photoToMove = file },
                onDelete = { file -> selectedPhotoId = null; photoToDelete = file },
                onDetails = { file -> selectedPhotoId = null; selectedFileForDetails = file })
        }
    }
    photoToDelete?.let { file ->
        AlertDialog(onDismissRequest = { photoToDelete = null }, title = { Text("Move photo to Recycle Bin?") },
            text = { Text("You can restore this photo from the Recycle Bin.") },
            confirmButton = { TextButton(onClick = { viewModel.moveFileToRecycleBin(file.id, context); photoToDelete = null }) { Text("Move to Recycle Bin") } },
            dismissButton = { TextButton(onClick = { photoToDelete = null }) { Text("Cancel") } })
    }
    photoToMove?.let { file ->
        AlertDialog(onDismissRequest = { photoToMove = null }, title = { Text("Move to folder") },
            text = {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    item { Text("Organize this photo on this device.", color = EncrypzMuted) }
                    item { TextButton(onClick = { viewModel.moveFileToFolder(file.id, null, context); photoToMove = null }) { Text("Vault root") } }
                    items(state.folders.filter { !it.isDeleted }, key = { it.id }) { folder ->
                        TextButton(onClick = { viewModel.moveFileToFolder(file.id, folder.id, context); photoToMove = null }) { Text(folder.name) }
                    }
                }
            }, confirmButton = { TextButton(onClick = { photoToMove = null }) { Text("Cancel") } })
    }

    // Bottom Sheet: Encrypted File Preview & Details (matching MyVault.tsx side-panel)
    selectedFileForDetails?.let { selectedSnapshot ->
        val file = state.files.find { it.id == selectedSnapshot.id } ?: selectedSnapshot
        var isDecryptedPreviewVisible by remember(file.id) { mutableStateOf(true) }

        ModalBottomSheet(
            onDismissRequest = { selectedFileForDetails = null },
            containerColor = EncrypzPanel,
            contentColor = EncrypzText,
            scrimColor = Color.Black.copy(alpha = 0.7f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    EncrypzCardIconBox(
                        icon = iconForCategory(file.category),
                        tint = colorForCategory(file.category),
                        size = 48.dp
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = file.cleanFileName,
                            style = MaterialTheme.typography.titleMedium,
                            color = EncrypzText
                        )
                        Spacer(Modifier.height(4.dp))
                        EncrypzStatusBadge(text = file.driveSyncState.badgeLabel)
                    }
                }

                // Automatic In-Memory Visual File Preview (Photos, PDFs, Videos, Documents)
                AnimatedVisibility(visible = isDecryptedPreviewVisible) {
                    VaultFilePreviewSection(
                        file = file,
                        viewModel = viewModel,
                        onCopyText = { text -> copyToClipboard("Decrypted Payload", text) },
                        onOpenFullScreen = { fullScreenPreviewFile = file }
                    )
                }

                // Cryptographic & File Metadata Specs Box
                EncrypzSpecsBox(
                    specs = listOf(
                        "Encrypted File (.enc)" to file.name,
                        "Encryption Cipher" to "AES-256-GCM (128-bit Auth Tag)",
                        "Original Size" to formatFileSize(file.sizeBytes),
                        "Encrypted Blob Size" to formatFileSize(file.encryptedPayload.size.toLong()),
                        "96-bit GCM IV" to "0x${file.ivHex.take(16)}...",
                        "Google Drive Status" to file.driveSyncState.badgeLabel,
                        "Google Account" to file.googleAccountEmail.ifBlank {
                            state.connectedGoogleEmail.ifBlank { "Google Drive Vault" }
                        }
                    )
                )

            }
        }
    }

    // Full-Screen Decrypted File Viewer Dialog
    fullScreenPreviewFile?.let { selectedSnapshot ->
        val file = state.files.find { it.id == selectedSnapshot.id } ?: selectedSnapshot
        VaultFileFullScreenDialog(
            file = file,
            viewModel = viewModel,
            onDismiss = { fullScreenPreviewFile = null },
            onCopyText = { text -> copyToClipboard("Decrypted Payload", text) }
        )
    }

    // Bottom Sheet: Folder Details & Actions
    selectedFolderForDetails?.let { folder ->
        ModalBottomSheet(
            onDismissRequest = { selectedFolderForDetails = null },
            containerColor = EncrypzPanel,
            contentColor = EncrypzText,
            scrimColor = Color.Black.copy(alpha = 0.7f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    EncrypzCardIconBox(
                        icon = Icons.Default.Folder,
                        tint = EncrypzBlue,
                        size = 48.dp
                    )
                    Column {
                        Text(
                            text = folder.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = EncrypzText
                        )
                        Text(
                            text = "Encrypted Folder · Created ${folder.createdAt}",
                            style = MaterialTheme.typography.bodySmall,
                            color = EncrypzMuted
                        )
                    }
                }

                EncrypzSpecsBox(
                    specs = listOf(
                        "Folder Name Cipher" to "AES-256-GCM",
                        "Metadata IV" to "0x${folder.ivHex.take(16)}...",
                        "Items Inside" to "${state.files.count { !it.isDeleted && it.folderId == folder.id }} files"
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            val target = folder
                            selectedFolderForDetails = null
                            viewModel.navigateToFolder(target)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp),
                        shape = RoundedCornerShape(100.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = EncrypzBlue,
                            contentColor = EncrypzText
                        )
                    ) {
                        Text("Open Folder")
                    }

                    OutlinedButton(
                        onClick = {
                            viewModel.moveFolderToRecycleBin(folder.id, context)
                            selectedFolderForDetails = null
                        },
                        modifier = Modifier.height(46.dp),
                        shape = RoundedCornerShape(100.dp),
                        border = BorderStroke(1.dp, EncrypzDangerBorder),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = EncrypzDangerBg,
                            contentColor = EncrypzDangerLight
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Move to Bin")
                    }
                }
            }
        }
    }

    // Dialog: Create Encrypted Folder
    if (showCreateFolderDialog) {
        var newFolderName by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            containerColor = EncrypzPanel,
            titleContentColor = EncrypzText,
            textContentColor = EncrypzMuted,
            title = { Text("New Encrypted Folder") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Folder names are encrypted client-side with AES-256-GCM before syncing.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = newFolderName,
                        onValueChange = { newFolderName = it },
                        placeholder = { Text("e.g. Contracts 2026", color = EncrypzSubtleText) },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = EncrypzInput,
                            unfocusedContainerColor = EncrypzInput,
                            focusedBorderColor = EncrypzBlue,
                            unfocusedBorderColor = EncrypzInputBorder,
                            focusedTextColor = EncrypzText,
                            unfocusedTextColor = EncrypzText
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newFolderName.isNotBlank()) {
                            viewModel.createFolder(newFolderName, context)
                            showCreateFolderDialog = false
                        }
                    },
                    enabled = newFolderName.isNotBlank(),
                    shape = RoundedCornerShape(100.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = EncrypzBlue)
                ) {
                    Text("Create Folder")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFolderDialog = false }) {
                    Text("Cancel", color = EncrypzMuted)
                }
            }
        )
    }

    // Dialog: Create Encrypted Secret Note / File
    if (showCreateNoteDialog) {
        var noteTitle by rememberSaveable { mutableStateOf("") }
        var noteBody by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateNoteDialog = false },
            containerColor = EncrypzPanel,
            titleContentColor = EncrypzText,
            textContentColor = EncrypzMuted,
            title = { Text("Create Encrypted File / Secret") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Encrypted immediately with AES-256-GCM (.enc) and saved to your Google Drive.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = noteTitle,
                        onValueChange = { noteTitle = it },
                        label = { Text("File name") },
                        placeholder = { Text("e.g. wallet_backup.txt", color = EncrypzSubtleText) },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = EncrypzInput,
                            unfocusedContainerColor = EncrypzInput,
                            focusedBorderColor = EncrypzBlue,
                            unfocusedBorderColor = EncrypzInputBorder,
                            focusedTextColor = EncrypzText,
                            unfocusedTextColor = EncrypzText
                        )
                    )
                    OutlinedTextField(
                        value = noteBody,
                        onValueChange = { noteBody = it },
                        label = { Text("Secret plaintext content") },
                        placeholder = { Text("Enter sensitive text, keys, or notes...", color = EncrypzSubtleText) },
                        minLines = 3,
                        maxLines = 5,
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = EncrypzInput,
                            unfocusedContainerColor = EncrypzInput,
                            focusedBorderColor = EncrypzBlue,
                            unfocusedBorderColor = EncrypzInputBorder,
                            focusedTextColor = EncrypzText,
                            unfocusedTextColor = EncrypzText
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (noteBody.isNotBlank()) {
                            viewModel.encryptAndAddSecureNote(
                                title = noteTitle.ifBlank { "secret_note.txt" },
                                content = noteBody,
                                context = context,
                                autoSaveToGoogleDrive = true
                            )
                            showCreateNoteDialog = false
                        }
                    },
                    enabled = noteBody.isNotBlank(),
                    shape = RoundedCornerShape(100.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = EncrypzBlue)
                ) {
                    Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Encrypt & Save to Drive")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateNoteDialog = false }) {
                    Text("Cancel", color = EncrypzMuted)
                }
            }
        )
    }

    // Dialog: Export Vault Config JSON (matching Encrypz.UI Profile.tsx)
    if (showConfigBackupDialog) {
        val configJson = remember { viewModel.exportVaultConfigJson() }
        var copied by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showConfigBackupDialog = false },
            containerColor = EncrypzPanel,
            titleContentColor = EncrypzText,
            textContentColor = EncrypzMuted,
            title = { Text("Vault Configuration Backup") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Keep a backup of your wrapped vault configuration. Your master password is still required to unwrap the key.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = EncrypzDeepBlack,
                        border = BorderStroke(1.dp, EncrypzGlassBorder)
                    ) {
                        Text(
                            text = configJson,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            ),
                            color = EncrypzBlueSoft
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        copyToClipboard("Encrypz Vault Config", configJson)
                        copied = true
                    },
                    shape = RoundedCornerShape(100.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (copied) EncrypzEmeraldBright else EncrypzBlue,
                        contentColor = if (copied) EncrypzBlack else EncrypzText
                    )
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (copied) "Copied JSON!" else "Copy Config JSON")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfigBackupDialog = false }) {
                    Text("Close", color = EncrypzMuted)
                }
            }
        )
    }

    // Dialog: Set or Change Quick-Unlock PIN
    if (showPinSetupDialog) {
        var newPin by rememberSaveable { mutableStateOf("") }
        var confirmPin by rememberSaveable { mutableStateOf("") }
        var pinDialogError by rememberSaveable { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = {
                showPinSetupDialog = false
                viewModel.dismissPinSetupPrompt()
            },
            containerColor = EncrypzPanel,
            titleContentColor = EncrypzText,
            textContentColor = EncrypzMuted,
            title = {
                Text(if (state.isPinSet) "Change Quick-Unlock PIN" else "Set Quick-Unlock PIN")
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Enter a 4 to 6 digit PIN to unlock your vault quickly after your first login.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = newPin,
                        onValueChange = {
                            newPin = it.filter { ch -> ch.isDigit() }.take(6)
                            pinDialogError = null
                        },
                        label = { Text("New PIN (4–6 digits)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = EncrypzInput,
                            unfocusedContainerColor = EncrypzInput,
                            focusedBorderColor = EncrypzBlue,
                            unfocusedBorderColor = EncrypzInputBorder,
                            focusedTextColor = EncrypzText,
                            unfocusedTextColor = EncrypzText
                        )
                    )
                    OutlinedTextField(
                        value = confirmPin,
                        onValueChange = {
                            confirmPin = it.filter { ch -> ch.isDigit() }.take(6)
                            pinDialogError = null
                        },
                        label = { Text("Confirm PIN") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = EncrypzInput,
                            unfocusedContainerColor = EncrypzInput,
                            focusedBorderColor = EncrypzBlue,
                            unfocusedBorderColor = EncrypzInputBorder,
                            focusedTextColor = EncrypzText,
                            unfocusedTextColor = EncrypzText
                        )
                    )
                    if (pinDialogError != null) {
                        Text(
                            text = pinDialogError!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = EncrypzDangerLight
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newPin.length !in 4..6) {
                            pinDialogError = "PIN must be 4 to 6 digits."
                        } else if (newPin != confirmPin) {
                            pinDialogError = "PINs do not match."
                        } else {
                            viewModel.setupUnlockPin(newPin, context)
                            showPinSetupDialog = false
                        }
                    },
                    enabled = newPin.length in 4..6 && confirmPin.length in 4..6,
                    shape = RoundedCornerShape(100.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = EncrypzBlue)
                ) {
                    Text("Save PIN")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showPinSetupDialog = false
                        viewModel.dismissPinSetupPrompt()
                    }
                ) {
                    Text("Cancel", color = EncrypzMuted)
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MyVaultTabContent(
    state: VaultUiState,
    viewModel: VaultViewModel,
    photosOnly: Boolean,
    onSearchQueryChange: (String) -> Unit,
    onSelectCategory: (VaultCategoryFilter) -> Unit,
    onToggleViewMode: () -> Unit,
    onBreadcrumbClick: (Int) -> Unit,
    onFolderClick: (VaultFolder) -> Unit,
    onFolderMoreClick: (VaultFolder) -> Unit,
    onFileClick: (VaultFileEntry) -> Unit,
    onAddClick: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 100.dp)
    ) {
        // Search Bar + View Mode Toggle
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = onSearchQueryChange,
                    placeholder = {
                        Text(
                            text = "Search your vault",
                            color = EncrypzMuted
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = EncrypzMuted
                        )
                    },
                    trailingIcon = {
                        if (state.searchQuery.isNotEmpty()) {
                            IconButton(onClick = { onSearchQueryChange("") }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Clear search",
                                    tint = EncrypzMuted
                                )
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = EncrypzSurface,
                        unfocusedContainerColor = EncrypzSurface,
                        focusedBorderColor = EncrypzBorderGlow,
                        unfocusedBorderColor = EncrypzBorder,
                        focusedTextColor = EncrypzText,
                        unfocusedTextColor = EncrypzText,
                        cursorColor = EncrypzBlue
                    )
                )

                // Grid / List View Toggle matching Vault.css .view-toggles
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = EncrypzSurface,
                    border = BorderStroke(1.dp, EncrypzBorder)
                ) {
                    IconButton(onClick = onToggleViewMode) {
                        Icon(
                            imageVector = if (state.viewMode == VaultViewMode.LIST) {
                                Icons.Default.GridView
                            } else {
                                Icons.AutoMirrored.Filled.ViewList
                            },
                            contentDescription = "Switch view mode",
                            tint = EncrypzBlueBright
                        )
                    }
                }
            }
        }

        // Category Filter Chips matching Vault.css .filter-chips
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                (if (photosOnly) {
                    listOf(VaultCategoryFilter.ALL)
                } else {
                    VaultCategoryFilter.entries.filter { it != VaultCategoryFilter.PHOTOS }
                }).forEach { filter ->
                    val isSelected = state.selectedCategory == filter
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .clickable { if (!photosOnly) onSelectCategory(filter) },
                        shape = RoundedCornerShape(20.dp),
                        color = if (isSelected) EncrypzBlue else EncrypzSurface,
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (isSelected) EncrypzBlue else EncrypzBorder
                        )
                    ) {
                        Text(
                            text = if (photosOnly) "Photos & Videos" else filter.label,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                            ),
                            color = if (isSelected) EncrypzText else EncrypzMuted
                        )
                    }
                }
            }
        }

        // Breadcrumbs Bar when inside subfolders
        if (state.breadcrumbs.size > 1) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    state.breadcrumbs.forEachIndexed { index, crumb ->
                        val isLast = index == state.breadcrumbs.lastIndex
                        Text(
                            text = crumb.name,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { onBreadcrumbClick(index) }
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (isLast) EncrypzText else EncrypzBlueBright
                        )
                        if (!isLast) {
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = EncrypzMuted,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }

        // Folders Section
        val visibleFolders = state.visibleFolders
        if (visibleFolders.isNotEmpty()) {
            item {
                SectionHeaderWithBadge(
                    title = "Folders",
                    count = visibleFolders.size
                )
            }

            if (state.viewMode == VaultViewMode.GRID) {
                item {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        maxItemsInEachRow = 2
                    ) {
                        visibleFolders.forEach { folder ->
                            FolderGridCard(
                                folder = folder,
                                onClick = { onFolderClick(folder) },
                                onMoreClick = { onFolderMoreClick(folder) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (visibleFolders.size % 2 == 1) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            } else {
                items(visibleFolders, key = { "folder-${it.id}" }) { folder ->
                    VaultEntryRowCard(
                        title = folder.name,
                        subtitle = "Encrypted folder · ${folder.createdAt}",
                        icon = Icons.Default.Folder,
                        iconTint = EncrypzBlue,
                        onClick = { onFolderClick(folder) },
                        onMoreClick = { onFolderMoreClick(folder) }
                    )
                }
            }
        }

        // Files Section
        val visibleFiles = state.visibleFiles.filter { file ->
            if (photosOnly) {
                file.category == VaultCategoryFilter.PHOTOS ||
                    file.category == VaultCategoryFilter.VIDEOS
            } else {
                file.category != VaultCategoryFilter.PHOTOS &&
                    file.category != VaultCategoryFilter.VIDEOS
            }
        }
        if (visibleFiles.isNotEmpty()) {
            item {
                SectionHeaderWithBadge(
                    title = if (photosOnly) {
                        "Photos & Videos"
                    } else {
                        "Encrypted Files"
                    },
                    count = visibleFiles.size
                )
            }

            if (photosOnly) {
                item {
                    PhotoGalleryGrid(
                        files = visibleFiles,
                        viewModel = viewModel,
                        onFileClick = onFileClick
                    )
                }
            } else if (state.viewMode == VaultViewMode.GRID) {
                item {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        maxItemsInEachRow = 2
                    ) {
                        visibleFiles.forEach { file ->
                            FileGridCard(
                                file = file,
                                viewModel = viewModel,
                                onClick = { onFileClick(file) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (visibleFiles.size % 2 == 1) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            } else {
                items(visibleFiles, key = { "file-${it.id}" }) { file ->
                    VaultEntryRowCard(
                        title = file.cleanFileName,
                        subtitle = "${formatFileSize(file.sizeBytes)} · ${file.uploadedAt} · Tap to preview",
                        icon = iconForCategory(file.category),
                        iconTint = colorForCategory(file.category),
                        file = file,
                        viewModel = viewModel,
                        onClick = { onFileClick(file) },
                        onMoreClick = { onFileClick(file) }
                    )
                }
            }
        }

        // Empty State
        if (visibleFolders.isEmpty() && visibleFiles.isEmpty()) {
            item {
                EncrypzBentoCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 24.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        EncrypzCardIconBox(
                            icon = Icons.Default.Lock,
                            size = 52.dp
                        )
                        Text(
                            text = if (state.searchQuery.isNotBlank()) "No matching encrypted items" else "This folder is empty",
                            style = MaterialTheme.typography.titleMedium,
                            color = EncrypzText
                        )
                        Text(
                            text = "Drop or add files to encrypt them client-side with AES-256-GCM before uploading.",
                            style = MaterialTheme.typography.bodySmall,
                            color = EncrypzMuted
                        )
                        Spacer(Modifier.height(4.dp))
                        Button(
                            onClick = onAddClick,
                            shape = RoundedCornerShape(100.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = EncrypzBlue)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Add Encrypted Files")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecycleBinTabContent(
    state: VaultUiState,
    onRestoreFolder: (String) -> Unit,
    onDeleteFolderPermanently: (String) -> Unit,
    onRestoreFile: (String) -> Unit,
    onDeleteFilePermanently: (String) -> Unit,
    onEmptyRecycleBin: () -> Unit
) {
    val deletedFolders = state.deletedFolders
    val deletedFiles = state.deletedFiles
    val isEmpty = deletedFolders.isEmpty() && deletedFiles.isEmpty()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            EncrypzBentoCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Encrypted Recycle Bin",
                            style = MaterialTheme.typography.titleMedium,
                            color = EncrypzText
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Items remain encrypted in your Google Drive until permanently shredded.",
                            style = MaterialTheme.typography.bodySmall,
                            color = EncrypzMuted
                        )
                    }
                    if (!isEmpty) {
                        Spacer(Modifier.width(12.dp))
                        OutlinedButton(
                            onClick = onEmptyRecycleBin,
                            shape = RoundedCornerShape(100.dp),
                            border = BorderStroke(1.dp, EncrypzDangerBorder),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = EncrypzDangerBg,
                                contentColor = EncrypzDangerLight
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteForever,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Empty Bin", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }

        if (isEmpty) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = null,
                            tint = EncrypzMuted.copy(alpha = 0.4f),
                            modifier = Modifier.size(56.dp)
                        )
                        Text(
                            text = "Recycle bin is empty",
                            style = MaterialTheme.typography.titleMedium,
                            color = EncrypzMuted
                        )
                    }
                }
            }
        } else {
            items(deletedFolders, key = { "del-folder-${it.id}" }) { folder ->
                DeletedVaultItemCard(
                    name = folder.name,
                    subtitle = "Encrypted Folder",
                    icon = Icons.Default.Folder,
                    onRestore = { onRestoreFolder(folder.id) },
                    onDeleteForever = { onDeleteFolderPermanently(folder.id) }
                )
            }
            items(deletedFiles, key = { "del-file-${it.id}" }) { file ->
                DeletedVaultItemCard(
                    name = file.name,
                    subtitle = "${formatFileSize(file.sizeBytes)} · ${file.uploadedAt}",
                    icon = iconForCategory(file.category),
                    onRestore = { onRestoreFile(file.id) },
                    onDeleteForever = { onDeleteFilePermanently(file.id) }
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SecurityProfileTabContent(
    state: VaultUiState,
    onRequestDrivePermission: () -> Unit,
    onToggleScreenshotProtection: (Boolean) -> Unit,
    onConfigurePinClick: () -> Unit,
    onRemovePinClick: () -> Unit,
    onSelectAutoLockTimeout: (AutoLockTimeout) -> Unit,
    onShowBackupConfig: () -> Unit,
    onLockVault: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            EncrypzBentoCard(modifier = Modifier.fillMaxWidth(), isFeatured = true) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        EncrypzCardIconBox(icon = Icons.Outlined.CloudSync, size = 46.dp)
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Google Drive backup", style = MaterialTheme.typography.titleMedium,
                                color = EncrypzText)
                            Text(state.connectedGoogleEmail.ifBlank { "Connect your Google account" },
                                style = MaterialTheme.typography.bodySmall, color = EncrypzMuted)
                        }
                    }
                    HorizontalDivider(color = EncrypzBorder)
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(if (state.isDrivePermissionGranted) Icons.Outlined.VerifiedUser else Icons.Outlined.CloudOff,
                            contentDescription = null, modifier = Modifier.size(22.dp),
                            tint = if (state.isDrivePermissionGranted) EncrypzEmeraldBright else EncrypzBlueBright)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(when {
                                state.isRequestingDrivePermission -> "Setting up Google Drive..."
                                state.isDrivePermissionGranted -> "Automatic backup is ready"
                                else -> "Drive access required"
                            }, style = MaterialTheme.typography.labelLarge, color = EncrypzText)
                            Text("Files are encrypted on your device and saved automatically to Encrypz in My Drive.",
                                style = MaterialTheme.typography.bodySmall, color = EncrypzMuted)
                        }
                    }
                    state.driveSetupError?.let { error ->
                        Text(error, style = MaterialTheme.typography.bodySmall, color = EncrypzDanger,
                            modifier = Modifier.fillMaxWidth()
                                .background(EncrypzDangerBg, RoundedCornerShape(12.dp)).padding(12.dp))
                    }
                    Button(
                        onClick = onRequestDrivePermission,
                        enabled = !state.isRequestingDrivePermission,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = EncrypzBlue,
                            contentColor = Color.White)
                    ) {
                        if (state.isRequestingDrivePermission) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = EncrypzBlueBright)
                        } else {
                            Icon(Icons.Outlined.VerifiedUser, contentDescription = null, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(when {
                            state.isRequestingDrivePermission -> "Connecting..."
                            state.isDrivePermissionGranted -> "Check access & sync files"
                            else -> "Allow Google Drive access"
                        }, style = MaterialTheme.typography.labelLarge)
                    }
                    Text("Google asks for consent only when access is needed. You can retry here if setup is cancelled.",
                        style = MaterialTheme.typography.bodySmall, color = EncrypzMuted)
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = null,
                            modifier = Modifier.size(18.dp), tint = EncrypzMuted)
                        Text("My Drive / Encrypz", style = MaterialTheme.typography.labelMedium, color = EncrypzMuted)
                    }
                    EncrypzSpecsBox(specs = listOf(
                        "Saved to Drive" to "${state.syncedToDriveCount} files",
                        "Encrypted on device" to "${state.activeFileCount} files",
                        "Encryption" to "AES-256-GCM"
                    ))
                }
            }
        }

        // Android User-Friendly Security Hardening Card
        item {
            EncrypzBentoCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        EncrypzCardIconBox(
                            icon = Icons.Default.Shield,
                            size = 38.dp
                        )
                        Column {
                            Text(
                                text = "Android Device Protection",
                                style = MaterialTheme.typography.titleMedium,
                                color = EncrypzText
                            )
                            Text(
                                text = "PIN Quick-Unlock & OS-level privacy safeguards",
                                style = MaterialTheme.typography.bodySmall,
                                color = EncrypzMuted
                            )
                        }
                    }

                    HorizontalDivider(color = EncrypzBorder)

                    // FLAG_SECURE Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.ScreenLockPortrait,
                                contentDescription = null,
                                tint = EncrypzBlueBright,
                                modifier = Modifier.size(22.dp)
                            )
                            Column {
                                Text(
                                    text = "Block Screenshots (FLAG_SECURE)",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = EncrypzText
                                )
                                Text(
                                    text = "Prevents screen capture & hides vault in Android Recents switcher",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = EncrypzMuted
                                )
                            }
                        }
                        Switch(
                            checked = state.screenshotProtectionEnabled,
                            onCheckedChange = onToggleScreenshotProtection,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = EncrypzText,
                                checkedTrackColor = EncrypzBlue,
                                uncheckedThumbColor = EncrypzMuted,
                                uncheckedTrackColor = EncrypzInput
                            )
                        )
                    }

                    HorizontalDivider(color = EncrypzBorder)

                    // Quick-Unlock PIN Management (replaces Biometric Hardware Unlock)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Dialpad,
                                contentDescription = null,
                                tint = EncrypzEmeraldBright,
                                modifier = Modifier.size(22.dp)
                            )
                            Column {
                                Text(
                                    text = "Quick-Unlock PIN",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = EncrypzText
                                )
                                Text(
                                    text = if (state.isPinSet) {
                                        "Active — unlock with your 4–6 digit PIN on login"
                                    } else {
                                        "Set a 4–6 digit PIN for fast unlock after first login"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = EncrypzMuted
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(
                                onClick = onConfigurePinClick,
                                shape = RoundedCornerShape(100.dp),
                                border = BorderStroke(1.dp, EncrypzBorderGlow),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = EncrypzSecondaryBg,
                                    contentColor = EncrypzBlueBright
                                )
                            ) {
                                Text(
                                    text = if (state.isPinSet) "Change PIN" else "Set PIN",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            if (state.isPinSet) {
                                TextButton(onClick = onRemovePinClick) {
                                    Text(
                                        text = "Remove",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = EncrypzDangerLight
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = EncrypzBorder)

                    // Auto-Lock Timeout Selector
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Timer,
                                contentDescription = null,
                                tint = EncrypzBlue,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "Auto-Lock Vault Timeout",
                                style = MaterialTheme.typography.titleSmall,
                                color = EncrypzText
                            )
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AutoLockTimeout.entries.forEach { option ->
                                val selected = state.autoLockTimeout == option
                                Surface(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(100.dp))
                                        .clickable { onSelectAutoLockTimeout(option) },
                                    shape = RoundedCornerShape(100.dp),
                                    color = if (selected) EncrypzSecondaryBg else EncrypzInput,
                                    border = BorderStroke(
                                        1.dp,
                                        if (selected) EncrypzBlue else EncrypzInputBorder
                                    )
                                ) {
                                    Text(
                                        text = option.label,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (selected) EncrypzBlueBright else EncrypzMuted
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Vault Configuration Backup & Session Actions (matching Profile.tsx)
        item {
            EncrypzBentoCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Vault Configuration & Recovery",
                        style = MaterialTheme.typography.titleMedium,
                        color = EncrypzText
                    )
                    Text(
                        text = "Keep a backup of your wrapped vault configuration. Deleting Encrypz's Google Drive app data can permanently remove your vault.",
                        style = MaterialTheme.typography.bodySmall,
                        color = EncrypzMuted
                    )

                    OutlinedButton(
                        onClick = onShowBackupConfig,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp),
                        shape = RoundedCornerShape(100.dp),
                        border = BorderStroke(1.dp, EncrypzBorderGlow),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = EncrypzSecondaryBg,
                            contentColor = EncrypzBlueBright
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Key,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Back up vault configuration (.json)")
                    }

                    Button(
                        onClick = onLockVault,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp),
                        shape = RoundedCornerShape(100.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = EncrypzDangerBg,
                            contentColor = EncrypzDangerLight
                        ),
                        border = BorderStroke(1.dp, EncrypzDangerBorder)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Lock Vault & Clear Session Keys")
                    }
                }
            }
        }

        item {
            EncrypzSecurityBadgesRow(
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun SectionHeaderWithBadge(
    title: String,
    count: Int
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = EncrypzText
        )
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = EncrypzSurface,
            border = BorderStroke(1.dp, EncrypzBorder)
        ) {
            Text(
                text = count.toString(),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = EncrypzMuted
            )
        }
    }
}

@Composable
private fun VaultEntryRowCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    onClick: () -> Unit,
    onMoreClick: () -> Unit,
    file: VaultFileEntry? = null,
    viewModel: VaultViewModel? = null
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = EncrypzSurface,
        border = BorderStroke(1.dp, EncrypzBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (file != null && viewModel != null) {
                VaultFileCardPreviewBox(
                    file = file,
                    viewModel = viewModel,
                    isCompactThumbnail = true,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, EncrypzBorder, RoundedCornerShape(10.dp))
                )
            } else {
                EncrypzCardIconBox(
                    icon = icon,
                    tint = iconTint,
                    size = 42.dp
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = EncrypzText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = EncrypzMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (file?.driveSyncState == DriveSyncState.SYNCED_TO_DRIVE) {
                Icon(
                    imageVector = Icons.Default.CloudDone,
                    contentDescription = "Saved in Google Drive (.enc)",
                    modifier = Modifier.size(16.dp),
                    tint = EncrypzEmeraldBright
                )
            }
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = "Encrypted",
                modifier = Modifier.size(15.dp),
                tint = EncrypzBlue.copy(alpha = 0.7f)
            )
            IconButton(
                onClick = onMoreClick,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Details",
                    tint = EncrypzMuted,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun FolderGridCard(
    folder: VaultFolder,
    onClick: () -> Unit,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = EncrypzSurface,
        border = BorderStroke(1.dp, EncrypzBorder)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(EncrypzSecondaryBg)
                    .padding(vertical = 22.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    tint = EncrypzBlue,
                    modifier = Modifier.size(42.dp)
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = folder.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = EncrypzText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = onMoreClick,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "More",
                        tint = EncrypzMuted,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun FileGridCard(
    file: VaultFileEntry,
    viewModel: VaultViewModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = EncrypzSurface,
        border = BorderStroke(1.dp, EncrypzBorder)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(112.dp)
                    .background(EncrypzInput),
                contentAlignment = Alignment.Center
            ) {
                VaultFileCardPreviewBox(
                    file = file,
                    viewModel = viewModel,
                    isCompactThumbnail = false,
                    modifier = Modifier.fillMaxSize()
                )
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (file.driveSyncState == DriveSyncState.SYNCED_TO_DRIVE) {
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(EncrypzSurface.copy(alpha = 0.88f))
                                .border(1.dp, EncrypzSuccessBorder, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudDone,
                                contentDescription = "Saved in Google Drive",
                                tint = EncrypzEmeraldBright,
                                modifier = Modifier.size(12.dp)
                            )
                        }

                    }
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(EncrypzSurface.copy(alpha = 0.85f))
                            .border(1.dp, EncrypzBorder, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Encrypted",
                            tint = EncrypzBlueBright,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text(
                    text = file.cleanFileName,
                    style = MaterialTheme.typography.titleSmall,
                    color = EncrypzText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (file.driveSyncState == DriveSyncState.SYNCED_TO_DRIVE) {
                        "${formatFileSize(file.sizeBytes)} · Drive (.enc)"
                    } else {
                        "${formatFileSize(file.sizeBytes)} · ${file.category.label}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (file.driveSyncState == DriveSyncState.SYNCED_TO_DRIVE) {
                        EncrypzEmeraldBright
                    } else {
                        EncrypzMuted
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun PhotoGalleryGrid(
    files: List<VaultFileEntry>,
    viewModel: VaultViewModel,
    onFileClick: (VaultFileEntry) -> Unit
) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        maxItemsInEachRow = 3
    ) {
        files.forEach { file ->
            PhotoGalleryTile(
                file = file,
                viewModel = viewModel,
                onFileClick = { onFileClick(file) },
                modifier = Modifier.weight(1f)
            )
        }
        repeat((3 - files.size % 3) % 3) {
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun PhotoGalleryTile(
    file: VaultFileEntry,
    viewModel: VaultViewModel,
    onFileClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(2.dp))
            .background(EncrypzInput)
            .clickable(onClick = onFileClick)
    ) {
        VaultFileCardPreviewBox(
            file = file,
            viewModel = viewModel,
            isCompactThumbnail = false,
            modifier = Modifier.fillMaxSize()
        )
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (file.driveSyncState == DriveSyncState.SYNCED_TO_DRIVE) {
                PhotoBadge(
                    icon = Icons.Default.CloudDone,
                    tint = EncrypzEmeraldBright,
                    background = Color.Black.copy(alpha = 0.58f)
                )
            }
            PhotoBadge(
                icon = Icons.Default.Lock,
                tint = EncrypzBlueBright,
                background = Color.Black.copy(alpha = 0.58f)
            )
            Box {
                PhotoBadge(
                    icon = Icons.Default.MoreVert,
                    tint = Color.White,
                    background = Color.Black.copy(alpha = 0.58f),
                    onClick = { showMenu = true }
                )
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier.background(EncrypzPanel)
                ) {
                    DropdownMenuItem(
                        text = { Text("Open preview", color = EncrypzText) },
                        onClick = {
                            showMenu = false
                            onFileClick()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("File details", color = EncrypzText) },
                        onClick = {
                            showMenu = false
                            onFileClick()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PhotoBadge(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    background: Color = EncrypzSurface.copy(alpha = 0.86f),
    onClick: (() -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .size(25.dp)
            .clip(CircleShape)
            .background(background)
            .border(1.dp, EncrypzBorder, CircleShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(14.dp)
        )
    }
}

@Composable
private fun DeletedVaultItemCard(
    name: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onRestore: () -> Unit,
    onDeleteForever: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = EncrypzSurface,
        border = BorderStroke(1.dp, EncrypzBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            EncrypzCardIconBox(
                icon = icon,
                tint = EncrypzMuted,
                size = 40.dp
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall.copy(
                        textDecoration = TextDecoration.LineThrough
                    ),
                    color = EncrypzMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = EncrypzSubtleText
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = onRestore,
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    shape = RoundedCornerShape(100.dp),
                    border = BorderStroke(1.dp, EncrypzBorderGlow),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = EncrypzSecondaryBg,
                        contentColor = EncrypzBlueBright
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.RestoreFromTrash,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Restore", style = MaterialTheme.typography.labelSmall)
                }
                OutlinedButton(
                    onClick = onDeleteForever,
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    shape = RoundedCornerShape(100.dp),
                    border = BorderStroke(1.dp, EncrypzDangerBorder),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = EncrypzDangerBg,
                        contentColor = EncrypzDangerLight
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteForever,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Delete", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun AddActionRowCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    tint: Color,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = EncrypzSurface,
        border = BorderStroke(1.dp, EncrypzBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            EncrypzCardIconBox(
                icon = icon,
                tint = tint,
                size = 44.dp
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = EncrypzText
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = EncrypzMuted
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = EncrypzMuted
            )
        }
    }
}

private fun iconForCategory(category: VaultCategoryFilter) = when (category) {
    VaultCategoryFilter.PHOTOS -> Icons.Default.Image
    VaultCategoryFilter.VIDEOS -> Icons.Default.Movie
    VaultCategoryFilter.DOCUMENTS -> Icons.Default.Description
    VaultCategoryFilter.ALL -> Icons.AutoMirrored.Filled.InsertDriveFile
}

private fun colorForCategory(category: VaultCategoryFilter) = when (category) {
    VaultCategoryFilter.PHOTOS -> EncrypzGlowCyan
    VaultCategoryFilter.VIDEOS -> EncrypzPurple
    VaultCategoryFilter.DOCUMENTS -> EncrypzBlueBright
    VaultCategoryFilter.ALL -> EncrypzBlue
}

@Preview(showBackground = true)
@Composable
fun VaultScreenPreview() {
    EncrypzTheme {
        VaultScreen(onLogout = {})
    }
}
