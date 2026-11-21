package com.encrypz.privacy.ui.vault

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.encrypz.privacy.auth.GoogleAuthConfig
import com.encrypz.privacy.crypto.PasswordStrengthLevel
import com.encrypz.privacy.crypto.VaultCrypto
import com.encrypz.privacy.theme.EncrypzBlack
import com.encrypz.privacy.theme.EncrypzBlue
import com.encrypz.privacy.theme.EncrypzBlueBright
import com.encrypz.privacy.theme.EncrypzBlueSoft
import com.encrypz.privacy.theme.EncrypzBorder
import com.encrypz.privacy.theme.EncrypzBorderGlow
import com.encrypz.privacy.theme.EncrypzDanger
import com.encrypz.privacy.theme.EncrypzDangerBg
import com.encrypz.privacy.theme.EncrypzDangerBorder
import com.encrypz.privacy.theme.EncrypzDangerLight
import com.encrypz.privacy.theme.EncrypzDeepBlack
import com.encrypz.privacy.theme.EncrypzEmeraldBright
import com.encrypz.privacy.theme.EncrypzGlassBorder
import com.encrypz.privacy.theme.EncrypzInput
import com.encrypz.privacy.theme.EncrypzInputBorder
import com.encrypz.privacy.theme.EncrypzMuted
import com.encrypz.privacy.theme.EncrypzPanel
import com.encrypz.privacy.theme.EncrypzSecondaryBg
import com.encrypz.privacy.theme.EncrypzSubtleText
import com.encrypz.privacy.theme.EncrypzSuccessBg
import com.encrypz.privacy.theme.EncrypzSuccessBorder
import com.encrypz.privacy.theme.EncrypzSurface
import com.encrypz.privacy.theme.EncrypzText
import com.encrypz.privacy.theme.EncrypzWarning
import com.encrypz.privacy.ui.components.EncrypzAmbientBackground
import com.encrypz.privacy.ui.components.EncrypzBentoCard
import com.encrypz.privacy.ui.components.EncrypzCardIconBox
import com.encrypz.privacy.ui.components.EncrypzFloatingNavBar
import com.encrypz.privacy.ui.components.EncrypzStatusBadge
import com.encrypz.privacy.vault.SafVaultBackend
import com.encrypz.privacy.vault.VaultDescriptor
import com.encrypz.privacy.vault.VaultStorageType
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultListScreen(
    viewModel: VaultViewModel,
    onOpenVault: (VaultDescriptor) -> Unit,
    onVaultCreatedAndUnlocked: () -> Unit,
    onRequestDrivePermission: (String) -> Unit,
    onRequestDrivePermissionViaBrowser: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        viewModel.loadVaults(context)
    }

    var showAddSheet by rememberSaveable { mutableStateOf(false) }
    var showCreateVaultDialog by rememberSaveable { mutableStateOf(false) }
    var showOpenDriveVaultDialog by rememberSaveable { mutableStateOf(false) }
    var vaultToRecover by remember { mutableStateOf<VaultDescriptor?>(null) }
    var vaultToRemove by remember { mutableStateOf<VaultDescriptor?>(null) }
    var recoveryKeyToAck by remember { mutableStateOf<String?>(null) }
    var folderOpenError by rememberSaveable { mutableStateOf<String?>(null) }
    var isOpeningFolderVault by rememberSaveable { mutableStateOf(false) }
    var selectedCreateFolderUri by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedCreateFolderName by rememberSaveable { mutableStateOf<String?>(null) }

    val driveScanEmail = state.connectedGoogleEmail.ifBlank {
        GoogleAuthConfig.getSavedGoogleEmail(context).orEmpty()
    }
    var pendingCreateStorage by remember { mutableStateOf<VaultStorageType?>(null) }

    val createFolderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            selectedCreateFolderUri = uri.toString()
            selectedCreateFolderName = SafVaultBackend.extractFolderDisplayName(context, uri)
        }
    }

    val openExistingFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            isOpeningFolderVault = true
            folderOpenError = null
            scope.launch {
                val res = viewModel.openExistingDeviceFolderVault(context, uri)
                isOpeningFolderVault = false
                res.onSuccess { descriptor ->
                    onOpenVault(descriptor)
                }.onFailure { err ->
                    folderOpenError = err.message ?: "No Encrypz vault found in that folder."
                }
            }
        }
    }

    val accountChooserLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val email = GoogleAuthConfig.extractSelectedEmail(context, result.resultCode, result.data)
        if (!email.isNullOrBlank()) {
            viewModel.connectGoogleAccount(email, context, requestDrivePermissionIfNeeded = true)
            pendingCreateStorage = null
        }
    }

    LaunchedEffect(showOpenDriveVaultDialog, state.isDrivePermissionGranted, driveScanEmail) {
        if (showOpenDriveVaultDialog && state.isDrivePermissionGranted && driveScanEmail.isNotBlank()) {
            viewModel.discoverDriveVaults(context, driveScanEmail)
        }
    }

    val recoveryShown = state.pendingRecoveryKeyDisplay ?: recoveryKeyToAck

    Scaffold(
        containerColor = EncrypzBlack,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddSheet = true },
                shape = RoundedCornerShape(100.dp),
                containerColor = EncrypzBlue,
                contentColor = EncrypzText
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Vault")
                Spacer(Modifier.width(8.dp))
                Text("Add Vault", style = MaterialTheme.typography.labelLarge)
            }
        }
    ) { innerPadding ->
        EncrypzAmbientBackground(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    EncrypzFloatingNavBar(
                        statusText = "100% Local · Offline",
                        versionText = "v2.0.0"
                    )
                }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Encrypted Vaults",
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                            color = EncrypzText
                        )
                        Text(
                            text = "Encrypz Vault v2 · scrypt + AES-256-SIV + AES-256-GCM",
                            style = MaterialTheme.typography.bodySmall,
                            color = EncrypzMuted
                        )
                    }
                }

                folderOpenError?.let { errMsg ->
                    item {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = EncrypzDangerBg,
                            border = BorderStroke(1.dp, EncrypzDangerBorder)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = errMsg,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = EncrypzDangerLight,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = { folderOpenError = null }) {
                                    Text("Dismiss", color = EncrypzText)
                                }
                            }
                        }
                    }
                }

                if (state.vaults.isEmpty()) {
                    item {
                        EncrypzBentoCard(modifier = Modifier.fillMaxWidth(), isFeatured = true) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                EncrypzCardIconBox(icon = Icons.Default.Shield, size = 56.dp)
                                Text(
                                    text = "Set up your Encrypz vault",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = EncrypzText
                                )
                                Text(
                                    text = "Create a new encrypted vault with your master password, or select an existing vault folder on your phone. Works 100% locally — no server or database.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = EncrypzMuted
                                )
                                Button(
                                    onClick = {
                                        folderOpenError = null
                                        selectedCreateFolderUri = null
                                        selectedCreateFolderName = null
                                        showCreateVaultDialog = true
                                    },
                                    modifier = Modifier.fillMaxWidth().height(46.dp),
                                    shape = RoundedCornerShape(100.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = EncrypzBlue)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Create New Vault")
                                }
                                OutlinedButton(
                                    enabled = !isOpeningFolderVault,
                                    onClick = {
                                        folderOpenError = null
                                        openExistingFolderLauncher.launch(null)
                                    },
                                    modifier = Modifier.fillMaxWidth().height(46.dp),
                                    shape = RoundedCornerShape(100.dp),
                                    border = BorderStroke(1.dp, EncrypzBorderGlow),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        containerColor = EncrypzSecondaryBg,
                                        contentColor = EncrypzBlueBright
                                    )
                                ) {
                                    if (isOpeningFolderVault) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Opening Folder...")
                                    } else {
                                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text("Select Existing Vault on Phone")
                                    }
                                }
                                TextButton(
                                    onClick = {
                                        folderOpenError = null
                                        showOpenDriveVaultDialog = true
                                    }
                                ) {
                                    Icon(Icons.Default.CloudDone, contentDescription = null, modifier = Modifier.size(16.dp), tint = EncrypzMuted)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Open Existing Google Drive Vault", color = EncrypzMuted)
                                }
                            }
                        }
                    }
                } else {
                    items(state.vaults, key = { it.id }) { vault ->
                        VaultListItemCard(
                            vault = vault,
                            onClick = {
                                viewModel.selectVault(context, vault.id)
                                onOpenVault(vault)
                            },
                            onRecoverClick = { vaultToRecover = vault },
                            onRemoveClick = { vaultToRemove = vault }
                        )
                    }
                }
            }
        }
    }

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
                Text("Add Encrypz Vault", style = MaterialTheme.typography.titleLarge, color = EncrypzText)
                Text(
                    "Create a brand-new encrypted vault on your phone or open an existing vault folder.",
                    style = MaterialTheme.typography.bodySmall,
                    color = EncrypzMuted
                )
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable {
                            showAddSheet = false
                            folderOpenError = null
                            selectedCreateFolderUri = null
                            selectedCreateFolderName = null
                            showCreateVaultDialog = true
                        },
                    shape = RoundedCornerShape(14.dp),
                    color = EncrypzSurface,
                    border = BorderStroke(1.dp, EncrypzBorder)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        EncrypzCardIconBox(icon = Icons.Default.Add, tint = EncrypzBlue, size = 44.dp)
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Create New Vault", style = MaterialTheme.typography.titleSmall, color = EncrypzText)
                            Text(
                                "Phone Storage, Folder on Phone, or Google Drive · Master Password",
                                style = MaterialTheme.typography.bodySmall,
                                color = EncrypzMuted
                            )
                        }
                    }
                }
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable {
                            showAddSheet = false
                            folderOpenError = null
                            openExistingFolderLauncher.launch(null)
                        },
                    shape = RoundedCornerShape(14.dp),
                    color = EncrypzSurface,
                    border = BorderStroke(1.dp, EncrypzBorder)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        EncrypzCardIconBox(icon = Icons.Default.FolderOpen, tint = EncrypzBlueBright, size = 44.dp)
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Select Existing Vault on Phone", style = MaterialTheme.typography.titleSmall, color = EncrypzText)
                            Text(
                                "Pick a folder on this phone containing vault.encrypz",
                                style = MaterialTheme.typography.bodySmall,
                                color = EncrypzMuted
                            )
                        }
                    }
                }
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable {
                            showAddSheet = false
                            folderOpenError = null
                            showOpenDriveVaultDialog = true
                        },
                    shape = RoundedCornerShape(14.dp),
                    color = EncrypzSurface,
                    border = BorderStroke(1.dp, EncrypzBorder)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        EncrypzCardIconBox(icon = Icons.Default.CloudDone, tint = EncrypzEmeraldBright, size = 44.dp)
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Open Existing Google Drive Vault", style = MaterialTheme.typography.titleSmall, color = EncrypzText)
                            Text(
                                "Scan Drive for vault.encrypz folders",
                                style = MaterialTheme.typography.bodySmall,
                                color = EncrypzMuted
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCreateVaultDialog) {
        var vaultName by rememberSaveable { mutableStateOf("") }
        var storageType by rememberSaveable { mutableStateOf(VaultStorageType.LOCAL) }
        var password by remember { mutableStateOf("") }
        var confirmPassword by remember { mutableStateOf("") }
        var showPass by rememberSaveable { mutableStateOf(false) }
        var createError by remember { mutableStateOf<String?>(null) }
        var isCreating by remember { mutableStateOf(false) }
        val strength = remember(password) { VaultCrypto.evaluatePasswordStrength(password) }

        AlertDialog(
            onDismissRequest = { if (!isCreating) showCreateVaultDialog = false },
            containerColor = EncrypzPanel,
            titleContentColor = EncrypzText,
            textContentColor = EncrypzMuted,
            title = { Text("Create New Vault") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = vaultName,
                        onValueChange = { vaultName = it; createError = null },
                        label = { Text("Vault name") },
                        placeholder = { Text("e.g. Personal Vault", color = EncrypzSubtleText) },
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

                    Text("Storage location", style = MaterialTheme.typography.labelMedium, color = EncrypzMuted)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        StorageOptionChip(
                            label = "Phone Storage",
                            selected = storageType == VaultStorageType.LOCAL,
                            onClick = { storageType = VaultStorageType.LOCAL; createError = null },
                            modifier = Modifier.weight(1f)
                        )
                        StorageOptionChip(
                            label = "Folder on Phone",
                            selected = storageType == VaultStorageType.DEVICE_FOLDER,
                            onClick = { storageType = VaultStorageType.DEVICE_FOLDER; createError = null },
                            modifier = Modifier.weight(1f)
                        )
                        StorageOptionChip(
                            label = "Google Drive",
                            selected = storageType == VaultStorageType.GOOGLE_DRIVE,
                            onClick = { storageType = VaultStorageType.GOOGLE_DRIVE; createError = null },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (storageType == VaultStorageType.LOCAL) {
                        Text(
                            text = "Stored 100% locally on your phone — no server, database, or internet required.",
                            style = MaterialTheme.typography.bodySmall,
                            color = EncrypzEmeraldBright
                        )
                    }

                    if (storageType == VaultStorageType.DEVICE_FOLDER) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = selectedCreateFolderName ?: "No folder selected",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (selectedCreateFolderUri != null) EncrypzEmeraldBright else EncrypzWarning,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = {
                                createError = null
                                createFolderPickerLauncher.launch(null)
                            }) {
                                Text(if (selectedCreateFolderUri == null) "Choose Folder" else "Change")
                            }
                        }
                        Text(
                            text = "All encrypted vault files (vault.encrypz, masterkey.encrypz, vault/) will be stored inside this folder on your phone.",
                            style = MaterialTheme.typography.bodySmall,
                            color = EncrypzMuted
                        )
                    }

                    if (storageType == VaultStorageType.GOOGLE_DRIVE) {
                        DrivePermissionSetupSection(
                            email = driveScanEmail,
                            isPermissionGranted = state.isDrivePermissionGranted,
                            isRequestingPermission = state.isRequestingDrivePermission,
                            driveSetupError = state.driveSetupError,
                            showScanButtonWhenGranted = false,
                            onChooseAccountClick = {
                                pendingCreateStorage = VaultStorageType.GOOGLE_DRIVE
                                try {
                                    accountChooserLauncher.launch(GoogleAuthConfig.createGoogleAccountChooserIntent())
                                } catch (_: Exception) {
                                    if (driveScanEmail.isNotBlank()) {
                                        onRequestDrivePermission(driveScanEmail)
                                    } else {
                                        onRequestDrivePermissionViaBrowser("")
                                    }
                                }
                            },
                            onPrimaryPermissionClick = {
                                createError = null
                                if (driveScanEmail.isBlank()) {
                                    pendingCreateStorage = VaultStorageType.GOOGLE_DRIVE
                                    try {
                                        accountChooserLauncher.launch(GoogleAuthConfig.createGoogleAccountChooserIntent())
                                    } catch (_: Exception) {
                                        onRequestDrivePermissionViaBrowser("")
                                    }
                                } else {
                                    onRequestDrivePermission(driveScanEmail)
                                }
                            },
                            onBrowserPermissionClick = {
                                createError = null
                                onRequestDrivePermissionViaBrowser(driveScanEmail)
                            }
                        )
                    }

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; createError = null },
                        label = { Text("Master password (12+ chars)") },
                        singleLine = true,
                        visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showPass = !showPass }) {
                                Icon(
                                    if (showPass) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = null,
                                    tint = EncrypzMuted
                                )
                            }
                        },
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
                    if (password.isNotEmpty()) {
                        val strengthColor = when (strength.level) {
                            PasswordStrengthLevel.WEAK -> EncrypzDanger
                            PasswordStrengthLevel.FAIR -> EncrypzWarning
                            PasswordStrengthLevel.GOOD -> EncrypzBlueBright
                            PasswordStrengthLevel.EXCELLENT -> EncrypzEmeraldBright
                        }
                        LinearProgressIndicator(
                            progress = { strength.level.fraction },
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(100.dp)),
                            color = strengthColor,
                            trackColor = EncrypzBorder,
                            strokeCap = StrokeCap.Round
                        )
                        Text("${strength.level.label} — ${strength.hint}", style = MaterialTheme.typography.labelSmall, color = strengthColor)
                    }

                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = { confirmPassword = it; createError = null },
                        label = { Text("Confirm master password") },
                        singleLine = true,
                        visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
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
                    createError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = EncrypzDangerLight) }
                }
            },
            confirmButton = {
                Button(
                    enabled = !isCreating && vaultName.isNotBlank() && password.length >= 12,
                    onClick = {
                        if (password != confirmPassword) {
                            createError = "Passwords do not match."
                            return@Button
                        }
                        if (storageType == VaultStorageType.DEVICE_FOLDER && selectedCreateFolderUri.isNullOrBlank()) {
                            createError = "Choose a folder on your phone first."
                            return@Button
                        }
                        if (storageType == VaultStorageType.GOOGLE_DRIVE && driveScanEmail.isBlank()) {
                            createError = "Choose a Google account first."
                            return@Button
                        }
                        isCreating = true
                        createError = null
                        scope.launch {
                            val res = viewModel.createVault(
                                context = context,
                                name = vaultName,
                                storageType = storageType,
                                password = password,
                                googleEmail = driveScanEmail,
                                deviceFolderTreeUri = selectedCreateFolderUri
                            )
                            isCreating = false
                            res.onSuccess { created ->
                                showCreateVaultDialog = false
                                recoveryKeyToAck = created.recoveryKey
                            }.onFailure { err ->
                                createError = err.message ?: "Failed to create vault."
                            }
                        }
                    },
                    shape = RoundedCornerShape(100.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = EncrypzBlue)
                ) {
                    if (isCreating) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text("Deriving Keys...")
                    } else {
                        Text("Create Vault")
                    }
                }
            },
            dismissButton = {
                TextButton(enabled = !isCreating, onClick = { showCreateVaultDialog = false }) {
                    Text("Cancel", color = EncrypzMuted)
                }
            }
        )
    }

    if (recoveryShown != null) {
        var copied by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = {},
            containerColor = EncrypzPanel,
            titleContentColor = EncrypzText,
            textContentColor = EncrypzMuted,
            title = { Text("Save Your Recovery Key") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Write down or copy this recovery key. If you forget your master password, this key is the ONLY way to recover your Encrypz vault (there is no server or password reset).",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = EncrypzDeepBlack,
                        border = BorderStroke(1.dp, EncrypzBorderGlow)
                    ) {
                        Text(
                            text = recoveryShown,
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
                        viewModel.dismissRecoveryKeyDisplay()
                        recoveryKeyToAck = null
                        if (state.isVaultUnlocked) {
                            onVaultCreatedAndUnlocked()
                        }
                    },
                    shape = RoundedCornerShape(100.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = EncrypzBlue)
                ) {
                    Text("I Saved My Recovery Key")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(ClipData.newPlainText("Encrypz Recovery Key", recoveryShown))
                    copied = true
                }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (copied) "Copied!" else "Copy Key", color = EncrypzEmeraldBright)
                }
            }
        )
    }

    if (showOpenDriveVaultDialog) {
        AlertDialog(
            onDismissRequest = { showOpenDriveVaultDialog = false },
            containerColor = EncrypzPanel,
            titleContentColor = EncrypzText,
            textContentColor = EncrypzMuted,
            title = { Text("Open Google Drive Vault") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    DrivePermissionSetupSection(
                        email = driveScanEmail,
                        isPermissionGranted = state.isDrivePermissionGranted,
                        isRequestingPermission = state.isRequestingDrivePermission,
                        driveSetupError = state.driveSetupError,
                        showScanButtonWhenGranted = true,
                        onChooseAccountClick = {
                            pendingCreateStorage = null
                            try {
                                accountChooserLauncher.launch(GoogleAuthConfig.createGoogleAccountChooserIntent())
                            } catch (_: Exception) {
                                if (driveScanEmail.isNotBlank()) {
                                    onRequestDrivePermission(driveScanEmail)
                                } else {
                                    onRequestDrivePermissionViaBrowser("")
                                }
                            }
                        },
                        onPrimaryPermissionClick = {
                            if (driveScanEmail.isBlank()) {
                                pendingCreateStorage = null
                                try {
                                    accountChooserLauncher.launch(GoogleAuthConfig.createGoogleAccountChooserIntent())
                                } catch (_: Exception) {
                                    onRequestDrivePermissionViaBrowser("")
                                }
                            } else if (!state.isDrivePermissionGranted) {
                                onRequestDrivePermission(driveScanEmail)
                            } else {
                                scope.launch { viewModel.discoverDriveVaults(context, driveScanEmail) }
                            }
                        },
                        onBrowserPermissionClick = {
                            onRequestDrivePermissionViaBrowser(driveScanEmail)
                        }
                    )
                    if (state.isDiscoveringDriveVaults) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text("Searching Google Drive...", style = MaterialTheme.typography.bodySmall)
                        }
                    } else if (state.discoveredDriveVaults.isEmpty()) {
                        Text(
                            if (state.isDrivePermissionGranted) {
                                "No existing vaults found in Google Drive. Tap Scan Google Drive for Vaults or create a new vault."
                            } else {
                                "Grant Google Drive access first so Encrypz can scan for your vault folders."
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        state.discoveredDriveVaults.forEach { found ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        val desc = viewModel.registerExistingDriveVault(context, driveScanEmail, found)
                                        showOpenDriveVaultDialog = false
                                        onOpenVault(desc)
                                    },
                                shape = RoundedCornerShape(10.dp),
                                color = EncrypzInput,
                                border = BorderStroke(1.dp, EncrypzBorder)
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(Icons.Default.CloudDone, contentDescription = null, tint = EncrypzEmeraldBright)
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(found.folderName, style = MaterialTheme.typography.titleSmall, color = EncrypzText)
                                        Text(
                                            if (found.formatVersion == 2) "Encrypz Vault v2" else "Encrypz Legacy Vault",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = EncrypzMuted
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showOpenDriveVaultDialog = false }) {
                    Text("Close", color = EncrypzBlueBright)
                }
            }
        )
    }

    vaultToRecover?.let { target ->
        var recKey by remember { mutableStateOf("") }
        var newPass by remember { mutableStateOf("") }
        var confirmNewPass by remember { mutableStateOf("") }
        var recError by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { if (!busy) vaultToRecover = null },
            containerColor = EncrypzPanel,
            titleContentColor = EncrypzText,
            textContentColor = EncrypzMuted,
            title = { Text("Recover \"${target.name}\"") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Enter your Base32 Recovery Key and choose a new master password (12+ chars). Files do not need to be re-encrypted.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = recKey,
                        onValueChange = { recKey = it; recError = null },
                        label = { Text("Recovery Key") },
                        minLines = 2,
                        maxLines = 4,
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
                        value = newPass,
                        onValueChange = { newPass = it; recError = null },
                        label = { Text("New master password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
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
                        value = confirmNewPass,
                        onValueChange = { confirmNewPass = it; recError = null },
                        label = { Text("Confirm new password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
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
                    recError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = EncrypzDangerLight) }
                }
            },
            confirmButton = {
                Button(
                    enabled = !busy && recKey.isNotBlank() && newPass.length >= 12,
                    onClick = {
                        if (newPass != confirmNewPass) {
                            recError = "Passwords do not match."
                            return@Button
                        }
                        busy = true
                        scope.launch {
                            val res = viewModel.recoverVaultWithKey(context, target.id, recKey, newPass)
                            busy = false
                            res.onSuccess {
                                vaultToRecover = null
                                viewModel.selectVault(context, target.id)
                                onOpenVault(target)
                            }.onFailure { err ->
                                recError = err.message ?: "Recovery failed."
                            }
                        }
                    },
                    shape = RoundedCornerShape(100.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = EncrypzBlue)
                ) {
                    Text(if (busy) "Recovering..." else "Reset Password")
                }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { vaultToRecover = null }) {
                    Text("Cancel", color = EncrypzMuted)
                }
            }
        )
    }

    vaultToRemove?.let { target ->
        AlertDialog(
            onDismissRequest = { vaultToRemove = null },
            containerColor = EncrypzPanel,
            titleContentColor = EncrypzText,
            textContentColor = EncrypzMuted,
            title = { Text("Remove \"${target.name}\"?") },
            text = {
                Text(
                    when (target.storageType) {
                        VaultStorageType.LOCAL ->
                            "This removes the vault from your list. Encrypted files in local storage will be kept unless you delete them."
                        VaultStorageType.DEVICE_FOLDER ->
                            "This removes the vault shortcut from this app. Your encrypted files inside the phone folder remain untouched."
                        VaultStorageType.GOOGLE_DRIVE ->
                            "This removes the vault shortcut from this device. Your encrypted files in Google Drive remain untouched."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeVault(context, target.id, deleteLocalFiles = false)
                    vaultToRemove = null
                }) {
                    Text("Remove", color = EncrypzDangerLight)
                }
            },
            dismissButton = {
                TextButton(onClick = { vaultToRemove = null }) {
                    Text("Cancel", color = EncrypzMuted)
                }
            }
        )
    }
}

@Composable
private fun DrivePermissionSetupSection(
    email: String,
    isPermissionGranted: Boolean,
    isRequestingPermission: Boolean,
    driveSetupError: String?,
    showScanButtonWhenGranted: Boolean = false,
    onChooseAccountClick: () -> Unit,
    onPrimaryPermissionClick: () -> Unit,
    onBrowserPermissionClick: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = email.ifBlank { "No Google account selected" },
                style = MaterialTheme.typography.bodySmall,
                color = if (email.isNotBlank()) EncrypzEmeraldBright else EncrypzWarning,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onChooseAccountClick) {
                Text(if (email.isBlank()) "Choose Account" else "Switch")
            }
        }

        if (isPermissionGranted) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = EncrypzSuccessBg,
                border = BorderStroke(1.dp, EncrypzSuccessBorder)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = EncrypzEmeraldBright,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Google Drive permission granted",
                        style = MaterialTheme.typography.bodySmall,
                        color = EncrypzEmeraldBright
                    )
                }
            }
            if (showScanButtonWhenGranted) {
                OutlinedButton(
                    onClick = onPrimaryPermissionClick,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Scan Google Drive for Vaults")
                }
            }
        } else if (isRequestingPermission) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = EncrypzBlue.copy(alpha = 0.14f),
                border = BorderStroke(1.dp, EncrypzBorderGlow)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = EncrypzBlueBright
                    )
                    Text(
                        text = "Opening Google Drive permission...",
                        style = MaterialTheme.typography.bodySmall,
                        color = EncrypzText,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            TextButton(
                onClick = onBrowserPermissionClick,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("Open in Browser instead", style = MaterialTheme.typography.labelSmall, color = EncrypzBlueBright)
            }
        } else {
            OutlinedButton(
                onClick = onPrimaryPermissionClick,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Allow Google Drive Access")
            }
            TextButton(
                onClick = onBrowserPermissionClick,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("Authorize in Browser", style = MaterialTheme.typography.labelSmall, color = EncrypzBlueBright)
            }
        }

        if (!driveSetupError.isNullOrBlank()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = EncrypzDangerBg,
                border = BorderStroke(1.dp, EncrypzDangerBorder)
            ) {
                Text(
                    text = driveSetupError,
                    style = MaterialTheme.typography.bodySmall,
                    color = EncrypzDangerLight,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun StorageOptionChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) EncrypzBlue else EncrypzInput,
        border = BorderStroke(1.dp, if (selected) EncrypzBlue else EncrypzInputBorder)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = if (selected) Color.White else EncrypzMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun VaultListItemCard(
    vault: VaultDescriptor,
    onClick: () -> Unit,
    onRecoverClick: () -> Unit,
    onRemoveClick: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
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
                icon = when (vault.storageType) {
                    VaultStorageType.GOOGLE_DRIVE -> Icons.Default.CloudDone
                    VaultStorageType.DEVICE_FOLDER -> Icons.Default.Folder
                    VaultStorageType.LOCAL -> Icons.Default.PhoneAndroid
                },
                tint = when (vault.storageType) {
                    VaultStorageType.GOOGLE_DRIVE, VaultStorageType.DEVICE_FOLDER -> EncrypzEmeraldBright
                    VaultStorageType.LOCAL -> EncrypzBlueBright
                },
                size = 46.dp
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = vault.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = EncrypzText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = when (vault.storageType) {
                        VaultStorageType.GOOGLE_DRIVE -> "Google Drive · ${vault.googleAccountEmail.orEmpty()}"
                        VaultStorageType.DEVICE_FOLDER -> "Phone Folder (Offline)"
                        VaultStorageType.LOCAL -> "Phone Storage (Offline)"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = EncrypzMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    EncrypzStatusBadge(
                        text = if (vault.formatVersion == 2) "Encrypz Vault v2 · EPZ2" else "Legacy Vault",
                        isActive = vault.formatVersion == 2
                    )
                }
            }
            Icon(Icons.Default.Lock, contentDescription = "Locked", tint = EncrypzMuted, modifier = Modifier.size(18.dp))
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Vault options", tint = EncrypzMuted)
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier.background(EncrypzPanel)
                ) {
                    DropdownMenuItem(
                        text = { Text("Unlock vault", color = EncrypzText) },
                        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = EncrypzBlueBright) },
                        onClick = { showMenu = false; onClick() }
                    )
                    if (vault.formatVersion == 2) {
                        DropdownMenuItem(
                            text = { Text("Recover with Recovery Key", color = EncrypzText) },
                            leadingIcon = { Icon(Icons.Default.Key, contentDescription = null, tint = EncrypzEmeraldBright) },
                            onClick = { showMenu = false; onRecoverClick() }
                        )
                    }
                    HorizontalDivider(color = EncrypzBorder)
                    DropdownMenuItem(
                        text = { Text("Remove from list", color = EncrypzDangerLight) },
                        leadingIcon = { Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = EncrypzDangerLight) },
                        onClick = { showMenu = false; onRemoveClick() }
                    )
                }
            }
        }
    }
}
