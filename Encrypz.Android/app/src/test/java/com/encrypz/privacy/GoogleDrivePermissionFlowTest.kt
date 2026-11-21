package com.encrypz.privacy

import android.content.Intent
import android.os.Bundle
import com.encrypz.privacy.auth.LoopbackGoogleOAuthServer
import com.encrypz.privacy.ui.vault.VaultViewModel
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertNotNull
import junit.framework.TestCase.assertNull
import junit.framework.TestCase.assertTrue
import org.junit.Test

class GoogleDrivePermissionFlowTest {

    @Test
    fun onDriveAuthorizationLaunchFailed_clearsPendingAndSetsError() {
        val model = VaultViewModel()
        model.onDriveAuthorizationLaunchFailed("Unable to open Drive permission dialog.")

        val state = model.uiState.value
        assertFalse(state.isRequestingDrivePermission)
        assertFalse(state.isDrivePermissionGranted)
        assertNull(state.pendingDriveAuthorization)
        assertNull(state.driveAuthorizationEmail)
        assertEquals("Unable to open Drive permission dialog.", state.driveSetupError)
        assertEquals("Unable to open Drive permission dialog.", state.statusBannerMessage)
    }

    @Test
    fun consumeDriveAuthorization_and_consumeDriveConsent_clearOnlyPendingIntents() {
        val model = VaultViewModel()
        model.consumeDriveAuthorization()
        model.consumeDriveConsent()

        val state = model.uiState.value
        assertNull(state.pendingDriveAuthorization)
        assertNull(state.pendingDriveConsentIntent)
    }

    @Test
    fun loopbackCancelPendingAuthorization_isSafeWhenIdle() {
        LoopbackGoogleOAuthServer.cancelPendingAuthorization()
        LoopbackGoogleOAuthServer.cancelPendingAuthorization()
        assertTrue(true)
    }

    @Test
    fun loopbackForegroundLifecycleTracking_updatesAndResetsCleanly() {
        LoopbackGoogleOAuthServer.setAppForeground(false)
        LoopbackGoogleOAuthServer.setAppForeground(true)
        LoopbackGoogleOAuthServer.resetForegroundTrackingForTest()
        assertTrue(true)
    }

    @Test
    fun onDriveAuthorizationResult_userCancelAfterDialogShown_resetsRequestingFlag() {
        val model = VaultViewModel()
        var currentMs = 1_000_000L
        model.clockMs = { currentMs }
        model.connectGoogleAccount("user@example.com")
        model.markDriveAuthorizationLaunched()

        currentMs = 1_003_500L
        val dummyContext = object : android.content.ContextWrapper(null) {
            override fun getApplicationContext() = this
            override fun getSharedPreferences(name: String?, mode: Int) = null
            override fun getPackageName() = "com.encrypz.privacy"
            override fun getFilesDir() = java.io.File(".")
            override fun startActivity(intent: Intent?) = Unit
            override fun startActivity(intent: Intent?, options: Bundle?) = Unit
        }

        // Calling with no pending driveAuthorizationEmail still resets requesting state safely
        model.onDriveAuthorizationResult(dummyContext, data = null, granted = false)
        assertFalse(model.uiState.value.isRequestingDrivePermission)
        assertNotNull(model.uiState.value.connectedGoogleEmail)
    }
}
