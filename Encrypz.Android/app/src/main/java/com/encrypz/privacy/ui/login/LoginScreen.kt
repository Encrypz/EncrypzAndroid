package com.encrypz.privacy.ui.login

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.encrypz.privacy.R
import com.encrypz.privacy.auth.GoogleAuthConfig
import com.encrypz.privacy.crypto.PasswordStrengthLevel
import com.encrypz.privacy.crypto.VaultCrypto
import com.encrypz.privacy.theme.EncrypzBlack
import com.encrypz.privacy.theme.EncrypzBlue
import com.encrypz.privacy.theme.EncrypzBlueBright
import com.encrypz.privacy.theme.EncrypzBorder
import com.encrypz.privacy.theme.EncrypzBorderGlow
import com.encrypz.privacy.theme.EncrypzDanger
import com.encrypz.privacy.theme.EncrypzDangerBg
import com.encrypz.privacy.theme.EncrypzDangerBorder
import com.encrypz.privacy.theme.EncrypzDangerLight
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
import com.encrypz.privacy.theme.EncrypzText
import com.encrypz.privacy.theme.EncrypzTheme
import com.encrypz.privacy.theme.EncrypzWarning
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.encrypz.privacy.ui.vault.UnlockAttemptResult

private enum class AuthInfoDialog {
    HELP_CENTRE,
    TERMS_OF_SERVICE
}

@Suppress("DEPRECATION")
@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    modifier: Modifier = Modifier,
    vaultName: String = "",
    isLocalVault: Boolean = false,
    initialGoogleSignedIn: Boolean = false,
    connectedAccountEmail: String = "",
    isDrivePermissionGranted: Boolean = false,
    driveSetupError: String? = null,
    accountError: String? = null,
    isRequestingDrivePermission: Boolean = false,
    hasExistingVaultAccount: Boolean = false,
    isCheckingAccountStatus: Boolean = false,
    hasCompletedFirstLogin: Boolean = false,
    isPinSet: Boolean = false,
    onUnlockWithPassword: (suspend (String, Boolean) -> UnlockAttemptResult)? = null,
    onUnlockWithPin: ((String) -> Boolean)? = null,
    onSetupPinAfterFirstLogin: ((String) -> Boolean)? = null,
    onRequestDrivePermission: (() -> Unit)? = null,
    onRequestDrivePermissionViaBrowser: (() -> Unit)? = null,
    onGoogleSignInChanged: ((Boolean, String?) -> Unit)? = null,
    onBackToVaultList: (() -> Unit)? = null,
    onRecoverWithKey: (suspend (String, String) -> Result<Unit>)? = null,
    fingerprintContent: @Composable () -> Unit = {}
) {
    val context = LocalContext.current
    var isGoogleSignedIn by rememberSaveable { mutableStateOf(initialGoogleSignedIn && connectedAccountEmail.isNotBlank()) }
    var activeEmail by rememberSaveable { mutableStateOf(connectedAccountEmail) }
    var isCreatingVault by rememberSaveable { mutableStateOf(false) }
    var usePinMode by rememberSaveable { mutableStateOf(hasCompletedFirstLogin && isPinSet) }
    var showPinSetupStepAfterFirstLogin by rememberSaveable { mutableStateOf(false) }
    var showRecoveryDialog by rememberSaveable { mutableStateOf(false) }
    val isVaultSelected = vaultName.isNotBlank() || isLocalVault
    val canShowUnlockForm = isLocalVault || isGoogleSignedIn

    var masterPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var confirmPasswordVisible by rememberSaveable { mutableStateOf(false) }

    var pinInput by remember { mutableStateOf("") }
    var newPinInput by remember { mutableStateOf("") }
    var confirmNewPinInput by remember { mutableStateOf("") }

    var errorMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var infoMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var activeInfoDialog by rememberSaveable { mutableStateOf<AuthInfoDialog?>(null) }

    // Keep activeEmail in sync if OAuth resolved/updated the email
    LaunchedEffect(connectedAccountEmail) {
        if (connectedAccountEmail.isNotBlank() && connectedAccountEmail != activeEmail) {
            activeEmail = connectedAccountEmail
            isGoogleSignedIn = true
        }
    }

    // Restore previously selected Google account from SharedPreferences only when no local vault is selected
    LaunchedEffect(isLocalVault) {
        if (!isLocalVault && !isVaultSelected && activeEmail.isBlank()) {
            val savedEmail = GoogleAuthConfig.getSavedGoogleEmail(context)
            if (!savedEmail.isNullOrBlank()) {
                activeEmail = savedEmail
                isGoogleSignedIn = true
                onGoogleSignInChanged?.invoke(true, savedEmail)
            }
        }
    }

    // Automatically direct unregistered Google email accounts to Account Creation,
    // or enable PIN unlock if the vault/account has already completed first login and set a PIN.
    LaunchedEffect(canShowUnlockForm, activeEmail, hasExistingVaultAccount, isCheckingAccountStatus, hasCompletedFirstLogin, isPinSet) {
        if (canShowUnlockForm && !isCheckingAccountStatus && !showPinSetupStepAfterFirstLogin) {
            if (!hasExistingVaultAccount && !isVaultSelected) {
                isCreatingVault = true
                usePinMode = false
                infoMessage = "Set a master password to create a new vault in Google Drive, or switch to 'Unlock existing' if you already have one."
            } else {
                isCreatingVault = false
                infoMessage = null
                if (hasCompletedFirstLogin && isPinSet) {
                    usePinMode = true
                }
            }
        }
    }

    // Native Android Google Account Picker launcher — returns the exact email selected by the user
    // and immediately requests Google Drive permission for the "Encrypz" folder
    val googleAccountChooserLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val selectedEmail = GoogleAuthConfig.extractSelectedEmail(
            context = context,
            resultCode = result.resultCode,
            data = result.data
        )
        if (!selectedEmail.isNullOrBlank()) {
            activeEmail = selectedEmail
            isGoogleSignedIn = true
            errorMessage = null
            infoMessage = null
            masterPassword = ""
            confirmPassword = ""
            pinInput = ""
            onGoogleSignInChanged?.invoke(true, selectedEmail)
        }
    }

    val passwordStrength = remember(masterPassword) {
        VaultCrypto.evaluatePasswordStrength(masterPassword)
    }

    val loginScope = rememberCoroutineScope()
    var isSubmitting by remember { mutableStateOf(false) }
    val currentAccountError by androidx.compose.runtime.rememberUpdatedState(accountError)
    val handleMasterPasswordUnlock: () -> Unit = {
        if (!isSubmitting) {
            loginScope.launch {
                isSubmitting = true
                try {
                    errorMessage = null
                    if (masterPassword.isBlank()) {
                        errorMessage = "Please enter your master password."
                    } else if (isCreatingVault && masterPassword.length < 12) {
                        errorMessage = "Use at least 12 characters for your new vault master password."
                    } else if (isCreatingVault && masterPassword != confirmPassword) {
                        errorMessage = "The master passwords do not match."
                    } else {
                        val result = onUnlockWithPassword?.invoke(masterPassword, isCreatingVault)
                        ?: UnlockAttemptResult.SUCCESS
                        when (result) {
                            UnlockAttemptResult.SUCCESS -> {
                                if (!isPinSet) {
                                    // First login completed — prompt user to set their Quick-Unlock PIN for future logins
                                    showPinSetupStepAfterFirstLogin = true
                                    errorMessage = null
                                    infoMessage = null
                                } else {
                                    onLoginSuccess()
                                }
                            }
                            UnlockAttemptResult.ACCOUNT_NOT_CREATED -> {
                                isCreatingVault = true
                                usePinMode = false
                                errorMessage = null
                                infoMessage = "No existing vault found in Google Drive for $activeEmail. Set and confirm a master password to create a new vault."
                            }
                            UnlockAttemptResult.SERVICE_ERROR -> {
                                errorMessage = currentAccountError ?: "Unable to open vault. Please retry."
                            }
                            UnlockAttemptResult.WRONG_PASSWORD -> {
                                errorMessage = "Wrong master password. Please enter the correct master password."
                            }
                            else -> {
                                errorMessage = "Wrong master password. Please enter the correct master password."
                            }
                        }
                    }
                } catch (e: IllegalStateException) {
                    errorMessage = e.message ?: "Unable to unlock vault. Please retry."
                } finally { isSubmitting = false }
            }
        }
    }


    val handlePinUnlock = {
        errorMessage = null
        val trimmedPin = pinInput.trim()
        if (trimmedPin.length != 6 || !trimmedPin.all { it in '0'..'9' }) {
            errorMessage = "Please enter your 6 digit PIN."
        } else {
            val unlocked = onUnlockWithPin?.invoke(trimmedPin) ?: false
            if (unlocked) {
                onLoginSuccess()
            } else {
                errorMessage = "Wrong PIN. Please enter the correct PIN."
                pinInput = ""
            }
        }
    }

    val handleSaveNewPinAfterFirstLogin = {
        errorMessage = null
        val cleanPin = newPinInput.trim()
        val cleanConfirm = confirmNewPinInput.trim()
        if (cleanPin.length != 6 || !cleanPin.all { it in '0'..'9' }) {
            errorMessage = "PIN must be 6 digits."
        } else if (cleanPin != cleanConfirm) {
            errorMessage = "PINs do not match. Please re-enter."
        } else {
            val saved = onSetupPinAfterFirstLogin?.invoke(cleanPin) ?: true
            if (saved) {
                showPinSetupStepAfterFirstLogin = false
                onLoginSuccess()
            } else {
                errorMessage = "Unable to save PIN. Please enter 6 digits."
            }
        }
    }

    if (canShowUnlockForm && usePinMode && isPinSet && !showPinSetupStepAfterFirstLogin && !isCreatingVault) {
        PinUnlockScreen(
            email = if (isLocalVault) vaultName.ifBlank { "Local Vault" } else activeEmail,
            pin = pinInput,
            error = errorMessage,
            onDigit = { if (pinInput.length < 6) { pinInput += it; errorMessage = null } },
            onBackspace = { pinInput = pinInput.dropLast(1); errorMessage = null },
            onUnlock = handlePinUnlock,
            onUsePassword = { usePinMode = false; pinInput = ""; errorMessage = null },
            modifier = modifier,
            fingerprintContent = fingerprintContent
        )
        return
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(EncrypzBlack)
            .background(
                Brush.radialGradient(
                    colors = listOf(EncrypzBlue.copy(alpha = 0.10f), Color.Transparent),
                    center = Offset(180f, 200f),
                    radius = 900f
                )
            )
            .background(
                Brush.radialGradient(
                    colors = listOf(EncrypzBlue.copy(alpha = 0.07f), Color.Transparent),
                    center = Offset(900f, 1600f),
                    radius = 850f
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            if (canShowUnlockForm && hasExistingVaultAccount && !isCreatingVault) fingerprintContent()
            // Top Header matching Encrypz.UI AuthLayout.tsx (.auth-header)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 440.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.encrypz_logo),
                        contentDescription = "Encrypz Logo",
                        modifier = Modifier.size(30.dp),
                        contentScale = ContentScale.Fit
                    )
                    Text(
                        text = "ENCRYPZ",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.8.sp
                        ),
                        color = EncrypzText
                    )
                }

                if (onBackToVaultList != null) {
                    Text(
                        text = "All Vaults",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            textDecoration = TextDecoration.Underline
                        ),
                        color = EncrypzBlueBright,
                        modifier = Modifier.clickable { onBackToVaultList() }
                    )
                } else {
                    Text(
                        text = "Zero-Knowledge Vault",
                        style = MaterialTheme.typography.labelMedium,
                        color = EncrypzMuted
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // Centered Auth Card matching Encrypz.UI Login.tsx (.auth-login-card)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 440.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = EncrypzPanel,
                    border = BorderStroke(1.dp, EncrypzGlassBorder),
                    shadowElevation = 20.dp
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 32.dp)
                    ) {
                        // Brand Icon + Title Header
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.encrypz_icon),
                                contentDescription = "Encrypz App Icon",
                                modifier = Modifier
                                    .size(46.dp)
                                    .shadow(12.dp, CircleShape, spotColor = EncrypzBlue)
                                    .clip(CircleShape),
                                contentScale = ContentScale.Crop
                            )
                            Column {
                                Text(
                                    text = when {
                                        showPinSetupStepAfterFirstLogin -> "Set Quick-Unlock PIN"
                                        vaultName.isNotBlank() -> vaultName
                                        !canShowUnlockForm && isCreatingVault -> "Create your vault"
                                        !canShowUnlockForm -> "Welcome to Encrypz"
                                        isCreatingVault -> "Create your vault"
                                        usePinMode && isPinSet -> "Unlock with PIN"
                                        else -> "Unlock your vault"
                                    },
                                    style = MaterialTheme.typography.headlineSmall.copy(
                                        fontSize = 22.sp,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = EncrypzText
                                )
                                Text(
                                    text = "Encrypz Vault v2 · AES-256-GCM",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = EncrypzBlueBright
                                )
                            }
                        }

                        Spacer(Modifier.height(14.dp))

                        Text(
                            text = when {
                                showPinSetupStepAfterFirstLogin ->
                                    "First unlock complete! Set a 6 digit PIN so you can unlock your vault quickly next time."
                                isLocalVault ->
                                    "Offline local device vault — enter your master password to unwrap the masterkey."
                                canShowUnlockForm && isCreatingVault ->
                                    "Create a strong master password to encrypt your Encrypz masterkey."
                                canShowUnlockForm && usePinMode && isPinSet ->
                                    "Enter your 6 digit Quick-Unlock PIN to open your encrypted vault."
                                canShowUnlockForm ->
                                    "Enter your master password to unlock your encrypted vault."
                                else ->
                                    "Continue with your Google account to access or create your encrypted Google Drive vault."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = EncrypzMuted
                        )

                        Spacer(Modifier.height(20.dp))

                        if (showPinSetupStepAfterFirstLogin) {
                            // Step shown right after First Login with Master Password to set up Quick-Unlock PIN
                            Text(
                                text = "New Quick-Unlock PIN (6 digits)",
                                style = MaterialTheme.typography.labelMedium,
                                color = EncrypzMuted,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                            OutlinedTextField(
                                value = newPinInput,
                                onValueChange = { value ->
                                    val digitsOnly = value.filter { it in '0'..'9' }.take(6)
                                    newPinInput = digitsOnly
                                    errorMessage = null
                                },
                                placeholder = {
                                    Text("Enter 6 digit PIN", color = EncrypzSubtleText)
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Dialpad,
                                        contentDescription = null,
                                        tint = EncrypzBlueBright,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.NumberPassword,
                                    imeAction = ImeAction.Next
                                ),
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                shape = RoundedCornerShape(8.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = EncrypzInput,
                                    unfocusedContainerColor = EncrypzInput,
                                    focusedBorderColor = EncrypzBlue,
                                    unfocusedBorderColor = EncrypzInputBorder,
                                    focusedTextColor = EncrypzText,
                                    unfocusedTextColor = EncrypzText,
                                    cursorColor = EncrypzBlue
                                )
                            )

                            Spacer(Modifier.height(14.dp))

                            Text(
                                text = "Confirm Quick-Unlock PIN",
                                style = MaterialTheme.typography.labelMedium,
                                color = EncrypzMuted,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                            OutlinedTextField(
                                value = confirmNewPinInput,
                                onValueChange = { value ->
                                    val digitsOnly = value.filter { it in '0'..'9' }.take(6)
                                    confirmNewPinInput = digitsOnly
                                    errorMessage = null
                                },
                                placeholder = {
                                    Text("Re-enter 6 digit PIN", color = EncrypzSubtleText)
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Key,
                                        contentDescription = null,
                                        tint = EncrypzMuted,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.NumberPassword,
                                    imeAction = ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(
                                    onDone = { handleSaveNewPinAfterFirstLogin() }
                                ),
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                shape = RoundedCornerShape(8.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = EncrypzInput,
                                    unfocusedContainerColor = EncrypzInput,
                                    focusedBorderColor = EncrypzBlue,
                                    unfocusedBorderColor = EncrypzInputBorder,
                                    focusedTextColor = EncrypzText,
                                    unfocusedTextColor = EncrypzText,
                                    cursorColor = EncrypzBlue
                                )
                            )

                            Spacer(Modifier.height(20.dp))

                            Button(
                                onClick = handleSaveNewPinAfterFirstLogin,
                                enabled = newPinInput.length == 6 && confirmNewPinInput.length == 6,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(46.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.White,
                                    contentColor = Color(0xFF0D0F12),
                                    disabledContainerColor = Color.White.copy(alpha = 0.25f),
                                    disabledContentColor = Color.White.copy(alpha = 0.5f)
                                )
                            ) {
                                Text(
                                    text = "Enable PIN Unlock & Open Vault",
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontWeight = FontWeight.SemiBold
                                    )
                                )
                            }

                            Spacer(Modifier.height(8.dp))

                            TextButton(
                                onClick = {
                                    showPinSetupStepAfterFirstLogin = false
                                    onLoginSuccess()
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "Skip for now",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = EncrypzMuted
                                )
                            }
                        } else if (!canShowUnlockForm) {
                            // Only "Continue with Google" button (Sign in with manual email removed as requested)
                            Button(
                                onClick = {
                                    errorMessage = null
                                    try {
                                        val chooserIntent = GoogleAuthConfig.createGoogleAccountChooserIntent()
                                        googleAccountChooserLauncher.launch(chooserIntent)
                                    } catch (_: Exception) {
                                        onRequestDrivePermission?.invoke()
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.White,
                                    contentColor = Color(0xFF0D0F12)
                                )
                            ) {
                                GoogleGLogoIcon(modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = "Continue with Google",
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFF0D0F12)
                                    )
                                )
                            }

                            Spacer(Modifier.height(22.dp))
                            HorizontalDivider(color = EncrypzBorder)
                            Spacer(Modifier.height(16.dp))

                            // Switch between Sign In and Create Vault matching Login.tsx (.auth-switch-prompt)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (isCreatingVault) "Already have a vault? " else "New to Encrypz? ",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = EncrypzMuted
                                )
                                Text(
                                    text = if (isCreatingVault) "Sign in" else "Create a vault",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        textDecoration = TextDecoration.Underline
                                    ),
                                    color = EncrypzText,
                                    modifier = Modifier.clickable {
                                        isCreatingVault = !isCreatingVault
                                        errorMessage = null
                                    }
                                )
                            }
                        } else {
                            if (isLocalVault) {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp),
                                    color = EncrypzSuccessBg,
                                    border = BorderStroke(1.dp, EncrypzSuccessBorder)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            modifier = Modifier.weight(1f),
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
                                                text = "Local Device Storage (Offline)",
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontWeight = FontWeight.Medium
                                                ),
                                                color = EncrypzText,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        if (onBackToVaultList != null) {
                                            Text(
                                                text = "Switch",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontWeight = FontWeight.SemiBold
                                                ),
                                                color = EncrypzBlueBright,
                                                modifier = Modifier
                                                    .padding(start = 8.dp)
                                                    .clickable { onBackToVaultList() }
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.height(10.dp))
                            } else {
                                // Connected Google Account row showing the user's real selected email
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp),
                                    color = EncrypzSuccessBg,
                                    border = BorderStroke(1.dp, EncrypzSuccessBorder)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            modifier = Modifier.weight(1f),
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
                                                text = activeEmail,
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontWeight = FontWeight.Medium
                                                ),
                                                color = EncrypzText,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Text(
                                            text = if (onBackToVaultList != null) "All vaults" else "Use another",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontWeight = FontWeight.SemiBold
                                            ),
                                            color = EncrypzBlueBright,
                                            modifier = Modifier
                                                .padding(start = 8.dp)
                                                .clickable {
                                                    if (onBackToVaultList != null) {
                                                        onBackToVaultList()
                                                    } else {
                                                        try {
                                                            GoogleAuthConfig.createGoogleSignInClient(context).signOut()
                                                        } catch (_: Exception) {}
                                                        GoogleAuthConfig.clearSavedGoogleEmail(context)
                                                        isGoogleSignedIn = false
                                                        activeEmail = ""
                                                        masterPassword = ""
                                                        confirmPassword = ""
                                                        pinInput = ""
                                                        errorMessage = null
                                                        infoMessage = null
                                                        onGoogleSignInChanged?.invoke(false, null)
                                                    }
                                                }
                                        )
                                    }
                                }

                                Spacer(Modifier.height(10.dp))

                                // Google Drive Folder Permission status & action row
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = !isDrivePermissionGranted && !isRequestingDrivePermission) {
                                            onRequestDrivePermission?.invoke()
                                        },
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isDrivePermissionGranted) {
                                        EncrypzSuccessBg.copy(alpha = 0.7f)
                                    } else if (driveSetupError != null) {
                                        EncrypzDangerBg.copy(alpha = 0.7f)
                                    } else {
                                        EncrypzBlue.copy(alpha = 0.14f)
                                    },
                                    border = BorderStroke(
                                        1.dp,
                                        when {
                                            isDrivePermissionGranted -> EncrypzSuccessBorder
                                            driveSetupError != null -> EncrypzDangerBorder
                                            else -> EncrypzBorderGlow
                                        }
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            modifier = Modifier.weight(1f),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            if (isRequestingDrivePermission) {
                                                androidx.compose.material3.CircularProgressIndicator(
                                                    modifier = Modifier.size(14.dp),
                                                    strokeWidth = 2.dp,
                                                    color = EncrypzBlueBright
                                                )
                                            } else {
                                                Icon(
                                                    imageVector = if (isDrivePermissionGranted) Icons.Default.CheckCircle else Icons.Default.Info,
                                                    contentDescription = null,
                                                    tint = when {
                                                        isDrivePermissionGranted -> EncrypzEmeraldBright
                                                        driveSetupError != null -> EncrypzDangerLight
                                                        else -> EncrypzBlueBright
                                                    },
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            }
                                            Text(
                                                text = when {
                                                    driveSetupError != null -> driveSetupError
                                                    isDrivePermissionGranted -> "Google Drive permission ready"
                                                    isRequestingDrivePermission -> "Requesting Google Drive permission..."
                                                    else -> "Google Drive permission required"
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = when {
                                                    isDrivePermissionGranted -> EncrypzEmeraldBright
                                                    driveSetupError != null -> EncrypzDangerLight
                                                    else -> EncrypzText
                                                }
                                            )
                                        }
                                        if (!isDrivePermissionGranted && !isRequestingDrivePermission) {
                                            Text(
                                                text = "Grant",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontWeight = FontWeight.SemiBold,
                                                    textDecoration = TextDecoration.Underline
                                                ),
                                                color = EncrypzBlueBright,
                                                modifier = Modifier.padding(start = 8.dp)
                                            )
                                        } else if (!isDrivePermissionGranted && onRequestDrivePermissionViaBrowser != null) {
                                            Text(
                                                text = "Browser",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontWeight = FontWeight.SemiBold,
                                                    textDecoration = TextDecoration.Underline
                                                ),
                                                color = EncrypzBlueBright,
                                                modifier = Modifier
                                                    .padding(start = 8.dp)
                                                    .clickable { onRequestDrivePermissionViaBrowser() }
                                            )
                                        }
                                    }
                                }
                                if (!isDrivePermissionGranted && driveSetupError != null && onRequestDrivePermissionViaBrowser != null) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 6.dp),
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        Text(
                                            text = "Authorize in Browser instead",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontWeight = FontWeight.SemiBold,
                                                textDecoration = TextDecoration.Underline
                                            ),
                                            color = EncrypzBlueBright,
                                            modifier = Modifier.clickable { onRequestDrivePermissionViaBrowser() }
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(16.dp))

                            if (!isCreatingVault && usePinMode && hasCompletedFirstLogin && isPinSet) {
                                // UNLOCK WITH PIN UI (Available after first login)
                                Text(
                                    text = "Enter your PIN",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.SemiBold
                                    ),
                                    color = EncrypzMuted,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(
                                    text = "Use the keypad below to unlock your vault",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = EncrypzSubtleText,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 4.dp)
                                )

                                Spacer(Modifier.height(18.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    repeat(6) { index ->
                                        Box(
                                            modifier = Modifier
                                                .padding(horizontal = 7.dp)
                                                .size(14.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    if (index < pinInput.length) {
                                                        EncrypzBlueBright
                                                    } else {
                                                        EncrypzInputBorder
                                                    }
                                                )
                                        )
                                    }
                                }

                                // Interactive Numeric PIN Pad for fast one-handed unlock
                                PinNumberPad(
                                    onDigitClick = { digit ->
                                        if (pinInput.length < 6) {
                                            pinInput += digit
                                            errorMessage = null
                                        }
                                    },
                                    onBackspaceClick = {
                                        if (pinInput.isNotEmpty()) {
                                            pinInput = pinInput.dropLast(1)
                                            errorMessage = null
                                        }
                                    },
                                    onClearClick = {
                                        pinInput = ""
                                        errorMessage = null
                                    }
                                )

                                Spacer(Modifier.height(16.dp))

                                Button(
                                    onClick = handlePinUnlock,
                                    enabled = pinInput.length == 6,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(46.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color.White,
                                        contentColor = Color(0xFF0D0F12),
                                        disabledContainerColor = Color.White.copy(alpha = 0.25f),
                                        disabledContentColor = Color.White.copy(alpha = 0.5f)
                                    )
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = null,
                                        modifier = Modifier.size(17.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "Unlock with PIN",
                                        style = MaterialTheme.typography.labelLarge.copy(
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    )
                                }

                                Spacer(Modifier.height(12.dp))

                                OutlinedButton(
                                    onClick = {
                                        usePinMode = false
                                        errorMessage = null
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(42.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, EncrypzInputBorder),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        containerColor = EncrypzInput,
                                        contentColor = EncrypzText
                                    )
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = null,
                                        tint = EncrypzMuted,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "Use master password instead",
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            } else {
                                // MASTER PASSWORD LOGIN OR ACCOUNT CREATION UI
                                Text(
                                    text = "Master password",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = EncrypzMuted,
                                    modifier = Modifier.padding(bottom = 6.dp)
                                )
                                OutlinedTextField(
                                    value = masterPassword,
                                    onValueChange = {
                                        masterPassword = it
                                        errorMessage = null
                                    },
                                    placeholder = {
                                        Text(
                                            text = if (isCreatingVault) "At least 12 characters" else "Enter master password",
                                            color = EncrypzSubtleText
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Lock,
                                            contentDescription = null,
                                            tint = EncrypzMuted,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    },
                                    trailingIcon = {
                                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                            Icon(
                                                imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                                contentDescription = if (passwordVisible) "Hide password" else "Show password",
                                                tint = EncrypzMuted,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    },
                                    visualTransformation = if (passwordVisible) {
                                        VisualTransformation.None
                                    } else {
                                        PasswordVisualTransformation()
                                    },
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Password,
                                        imeAction = if (isCreatingVault) ImeAction.Next else ImeAction.Done
                                    ),
                                    keyboardActions = KeyboardActions(
                                        onDone = { if (!isCreatingVault) handleMasterPasswordUnlock() }
                                    ),
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    shape = RoundedCornerShape(8.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedContainerColor = EncrypzInput,
                                        unfocusedContainerColor = EncrypzInput,
                                        focusedBorderColor = EncrypzBlue,
                                        unfocusedBorderColor = EncrypzInputBorder,
                                        focusedTextColor = EncrypzText,
                                        unfocusedTextColor = EncrypzText,
                                        cursorColor = EncrypzBlue
                                    )
                                )

                                // Compact password strength meter when creating a new vault
                                AnimatedVisibility(visible = isCreatingVault && masterPassword.isNotEmpty()) {
                                    val strengthColor = when (passwordStrength.level) {
                                        PasswordStrengthLevel.WEAK -> EncrypzDanger
                                        PasswordStrengthLevel.FAIR -> EncrypzWarning
                                        PasswordStrengthLevel.GOOD -> EncrypzBlueBright
                                        PasswordStrengthLevel.EXCELLENT -> EncrypzEmeraldBright
                                    }
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 8.dp)
                                    ) {
                                        LinearProgressIndicator(
                                            progress = { passwordStrength.level.fraction },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(4.dp)
                                                .clip(RoundedCornerShape(100.dp)),
                                            color = strengthColor,
                                            trackColor = EncrypzBorder,
                                            strokeCap = StrokeCap.Round
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = "${passwordStrength.level.label} — ${passwordStrength.hint}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = strengthColor
                                        )
                                    }
                                }

                                // Confirm Master Password field when creating a vault
                                AnimatedVisibility(visible = isCreatingVault) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 14.dp)
                                    ) {
                                        Text(
                                            text = "Confirm master password",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = EncrypzMuted,
                                            modifier = Modifier.padding(bottom = 6.dp)
                                        )
                                        OutlinedTextField(
                                            value = confirmPassword,
                                            onValueChange = {
                                                confirmPassword = it
                                                errorMessage = null
                                            },
                                            placeholder = {
                                                Text(
                                                    text = "Re-enter master password",
                                                    color = EncrypzSubtleText
                                                )
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = Icons.Default.Key,
                                                    contentDescription = null,
                                                    tint = EncrypzMuted,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            },
                                            trailingIcon = {
                                                IconButton(onClick = { confirmPasswordVisible = !confirmPasswordVisible }) {
                                                    Icon(
                                                        imageVector = if (confirmPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                                        contentDescription = if (confirmPasswordVisible) "Hide password" else "Show password",
                                                        tint = EncrypzMuted,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            },
                                            visualTransformation = if (confirmPasswordVisible) {
                                                VisualTransformation.None
                                            } else {
                                                PasswordVisualTransformation()
                                            },
                                            keyboardOptions = KeyboardOptions(
                                                keyboardType = KeyboardType.Password,
                                                imeAction = ImeAction.Done
                                            ),
                                            keyboardActions = KeyboardActions(onDone = { handleMasterPasswordUnlock() }),
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true,
                                            shape = RoundedCornerShape(8.dp),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedContainerColor = EncrypzInput,
                                                unfocusedContainerColor = EncrypzInput,
                                                focusedBorderColor = EncrypzBlue,
                                                unfocusedBorderColor = EncrypzInputBorder,
                                                focusedTextColor = EncrypzText,
                                                unfocusedTextColor = EncrypzText,
                                                cursorColor = EncrypzBlue
                                            )
                                        )
                                    }
                                }

                                Spacer(Modifier.height(20.dp))

                                // Primary Action Button (.auth-btn-white-full / primary)
                                Button(
                                    onClick = handleMasterPasswordUnlock,
                                    enabled = masterPassword.isNotEmpty() && !isCheckingAccountStatus && !isSubmitting,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(46.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color.White,
                                        contentColor = Color(0xFF0D0F12),
                                        disabledContainerColor = Color.White.copy(alpha = 0.25f),
                                        disabledContentColor = Color.White.copy(alpha = 0.5f)
                                    )
                                ) {
                                    Text(
                                        text = when {
                                            isSubmitting -> if (isCreatingVault) "Creating vault..." else "Unlocking vault..."
                                            isCheckingAccountStatus -> "Checking Google Drive vault..."
                                            isCreatingVault -> "Create vault"
                                            else -> "Unlock vault"
                                        },
                                        style = MaterialTheme.typography.labelLarge.copy(
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    )
                                }

                                // Option to switch back to PIN unlock if PIN is configured after first login
                                if (!isCreatingVault && hasCompletedFirstLogin && isPinSet) {
                                    Spacer(Modifier.height(10.dp))
                                    OutlinedButton(
                                        onClick = {
                                            usePinMode = true
                                            errorMessage = null
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(44.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        border = BorderStroke(1.dp, EncrypzInputBorder),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            containerColor = EncrypzInput,
                                            contentColor = EncrypzText
                                        )
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Dialpad,
                                            contentDescription = "Unlock with PIN",
                                            tint = EncrypzBlueBright,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = "Unlock with PIN",
                                            style = MaterialTheme.typography.labelLarge
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(18.dp))
                            HorizontalDivider(color = EncrypzBorder)
                            Spacer(Modifier.height(14.dp))

                            if (isVaultSelected && onRecoverWithKey != null) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Forgot password? ",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = EncrypzMuted
                                    )
                                    Text(
                                        text = "Recover with Recovery Key",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            textDecoration = TextDecoration.Underline
                                        ),
                                        color = EncrypzBlueBright,
                                        modifier = Modifier.clickable {
                                            showRecoveryDialog = true
                                        }
                                    )
                                }
                            } else {
                                // Switch between Unlock and Create Vault
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (isCreatingVault) "Already have a vault? " else "Setting up a new vault? ",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = EncrypzMuted
                                    )
                                    Text(
                                        text = if (isCreatingVault) "Unlock existing" else "Create new vault",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            textDecoration = TextDecoration.Underline
                                        ),
                                        color = EncrypzText,
                                        modifier = Modifier.clickable {
                                            isCreatingVault = !isCreatingVault
                                            errorMessage = null
                                            infoMessage = null
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // Authentication feedback stays in the app, never in system notifications.
            val loginFeedback = errorMessage ?: driveSetupError ?: infoMessage
            loginFeedback?.let { message ->
                Text(text = message,
                    color = if (errorMessage != null || driveSetupError != null) MaterialTheme.colorScheme.error else EncrypzMuted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().widthIn(max = 440.dp).padding(bottom = 12.dp))
            }

            // Minimal Bottom Footer matching Encrypz.UI AuthLayout.tsx (.auth-footer)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 440.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "V 2.0.0",
                    style = MaterialTheme.typography.labelSmall,
                    color = EncrypzSubtleText
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Help Centre",
                        style = MaterialTheme.typography.labelSmall,
                        color = EncrypzSubtleText,
                        modifier = Modifier.clickable {
                            activeInfoDialog = AuthInfoDialog.HELP_CENTRE
                        }
                    )
                    Text(
                        text = "Terms of Service",
                        style = MaterialTheme.typography.labelSmall,
                        color = EncrypzSubtleText,
                        modifier = Modifier.clickable {
                            activeInfoDialog = AuthInfoDialog.TERMS_OF_SERVICE
                        }
                    )
                }
            }
        }

        if (showRecoveryDialog && onRecoverWithKey != null) {
            var recoveryKeyInput by remember { mutableStateOf("") }
            var recoveryNewPass by remember { mutableStateOf("") }
            var recoveryConfirmPass by remember { mutableStateOf("") }
            var recoveryError by remember { mutableStateOf<String?>(null) }
            var recovering by remember { mutableStateOf(false) }

            AlertDialog(
                onDismissRequest = { if (!recovering) showRecoveryDialog = false },
                containerColor = EncrypzPanel,
                titleContentColor = EncrypzText,
                textContentColor = EncrypzMuted,
                title = { Text("Recover Vault with Key") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "Paste your Base32 Recovery Key and enter a new master password (12+ chars).",
                            style = MaterialTheme.typography.bodySmall
                        )
                        OutlinedTextField(
                            value = recoveryKeyInput,
                            onValueChange = { recoveryKeyInput = it; recoveryError = null },
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
                            value = recoveryNewPass,
                            onValueChange = { recoveryNewPass = it; recoveryError = null },
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
                            value = recoveryConfirmPass,
                            onValueChange = { recoveryConfirmPass = it; recoveryError = null },
                            label = { Text("Confirm password") },
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
                        recoveryError?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = EncrypzDangerLight)
                        }
                    }
                },
                confirmButton = {
                    Button(
                        enabled = !recovering && recoveryKeyInput.isNotBlank() && recoveryNewPass.length >= 12,
                        onClick = {
                            if (recoveryNewPass != recoveryConfirmPass) {
                                recoveryError = "Passwords do not match."
                                return@Button
                            }
                            recovering = true
                            loginScope.launch {
                                val res = onRecoverWithKey.invoke(recoveryKeyInput, recoveryNewPass)
                                recovering = false
                                res.onSuccess {
                                    showRecoveryDialog = false
                                    infoMessage = "Master password updated. Unlock with your new password."
                                    usePinMode = false
                                }.onFailure { err ->
                                    recoveryError = err.message ?: "Recovery failed."
                                }
                            }
                        },
                        shape = RoundedCornerShape(100.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = EncrypzBlue)
                    ) {
                        Text(if (recovering) "Recovering..." else "Reset Password")
                    }
                },
                dismissButton = {
                    TextButton(enabled = !recovering, onClick = { showRecoveryDialog = false }) {
                        Text("Cancel", color = EncrypzMuted)
                    }
                }
            )
        }

        // Info Modal matching Encrypz.UI AuthLayout.tsx
        activeInfoDialog?.let { dialogType ->
            AlertDialog(
                onDismissRequest = { activeInfoDialog = null },
                containerColor = EncrypzPanel,
                titleContentColor = EncrypzText,
                textContentColor = EncrypzMuted,
                title = {
                    Text(
                        text = when (dialogType) {
                            AuthInfoDialog.HELP_CENTRE -> "Encrypz Help Centre"
                            AuthInfoDialog.TERMS_OF_SERVICE -> "Terms of Service"
                        },
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        when (dialogType) {
                            AuthInfoDialog.HELP_CENTRE -> {
                                Text(
                                    text = "How does local zero-knowledge encryption work?",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = EncrypzText
                                )
                                Text(
                                    text = "Everything runs 100% on your phone with no external server or database. Your files are encrypted locally on this device using AES-256-GCM and AES-256-SIV before saving locally or syncing directly to your own Google Drive.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    text = "Quick-Unlock PIN & Recovery Key",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = EncrypzText
                                )
                                Text(
                                    text = "After unlocking with your master password, you can set a 6-digit PIN for fast local unlocks. Use your Base32 Recovery Key if you ever forget your master password.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            AuthInfoDialog.TERMS_OF_SERVICE -> {
                                Text(
                                    text = "Zero-Knowledge Privacy Principles",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = EncrypzText
                                )
                                Text(
                                    text = "Your vault is protected with cryptographic keys derived strictly on your device (scrypt + RFC 3394 AES Key Wrap). Because Encrypz uses no server or database, lost passwords can only be recovered with your saved Recovery Key.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    text = "Local offline vaults stay on your phone, and Google Drive vaults remain directly under your own Google account.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { activeInfoDialog = null }) {
                        Text("Close", color = EncrypzBlueBright)
                    }
                }
            )
        }
    }
}

@Composable
private fun PinNumberPad(
    onDigitClick: (String) -> Unit,
    onBackspaceClick: () -> Unit,
    onClearClick: () -> Unit
) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("C", "0", "⌫")
    )
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { key ->
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                when (key) {
                                    "C" -> onClearClick()
                                    "⌫" -> onBackspaceClick()
                                    else -> onDigitClick(key)
                                }
                            },
                        shape = RoundedCornerShape(8.dp),
                        color = EncrypzInput,
                        border = BorderStroke(1.dp, EncrypzInputBorder)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (key == "⌫") {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Backspace,
                                    contentDescription = "Backspace",
                                    tint = EncrypzMuted,
                                    modifier = Modifier.size(16.dp)
                                )
                            } else {
                                Text(
                                    text = key,
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.SemiBold
                                    ),
                                    color = if (key == "C") EncrypzMuted else EncrypzText,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GoogleGLogoIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val strokeWidth = size.minDimension * 0.22f
        val diameter = size.minDimension - strokeWidth
        val topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f)
        val arcSize = Size(diameter, diameter)

        // Red top arc
        drawArc(
            color = Color(0xFFEA4335),
            startAngle = 215f,
            sweepAngle = 100f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
        )
        // Yellow left arc
        drawArc(
            color = Color(0xFFFBBC05),
            startAngle = 140f,
            sweepAngle = 75f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
        )
        // Green bottom arc
        drawArc(
            color = Color(0xFF34A853),
            startAngle = 45f,
            sweepAngle = 95f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
        )
        // Blue right arc + crossbar
        drawArc(
            color = Color(0xFF4285F4),
            startAngle = -15f,
            sweepAngle = 60f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
        )
        drawLine(
            color = Color(0xFF4285F4),
            start = Offset(size.width * 0.52f, size.height * 0.5f),
            end = Offset(size.width * 0.94f, size.height * 0.5f),
            strokeWidth = strokeWidth
        )
    }
}

@Preview(showBackground = true)
@Composable
fun LoginScreenPreview() {
    EncrypzTheme {
        LoginScreen(onLoginSuccess = {})
    }
}

@Preview(showBackground = true)
@Composable
fun LoginScreenUnlockedStepPreview() {
    EncrypzTheme {
        LoginScreen(
            onLoginSuccess = {},
            initialGoogleSignedIn = true,
            connectedAccountEmail = "user@gmail.com",
            hasExistingVaultAccount = true,
            hasCompletedFirstLogin = true,
            isPinSet = true
        )
    }
}
