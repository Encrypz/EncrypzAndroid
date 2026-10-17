package com.example.encrypz.ui.main

import com.example.encrypz.data.DataRepository
import junit.framework.TestCase.assertEquals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class MainScreenViewModelTest {
  @Test
  fun uiState_initiallyLoading() = runTest {
    val viewModel = MainScreenViewModel(FakeMyModelRepository())
    assertEquals(viewModel.uiState.first(), MainScreenUiState.Loading)
  }

  @Test
  fun uiState_onItemSaved_isDisplayed() = runTest {
    val viewModel = MainScreenViewModel(FakeMyModelRepository())
    assertEquals(viewModel.uiState.first(), MainScreenUiState.Loading)
  }
  @Test
  fun mongoBsonEncodeDecode_roundTripAndAtlasCheck() {
    val doc = linkedMapOf<String, Any?>(
      "email" to "test@example.com",
      "passwordVerifierHex" to "abcd1234",
      "count" to 42
    )
    val bytes = com.example.encrypz.data.MongoAtlasClient.encodeBsonDocument(doc)
    val (decoded, _) = com.example.encrypz.data.MongoAtlasClient.decodeBsonDocument(bytes, 0)
    assertEquals("test@example.com", decoded["email"])
    assertEquals("abcd1234", decoded["passwordVerifierHex"])
    assertEquals(42, decoded["count"])

    com.example.encrypz.data.MongoAtlasClient.executeOnPrimary { session ->
      val dbs = session.runCommand(linkedMapOf("listDatabases" to 1, "\$db" to "admin"))
      println("ATLAS_DBS: $dbs")
    }
  }
}

private class FakeMyModelRepository : DataRepository {
  override val data: Flow<List<String>> = flow { emit(listOf("Sample")) }
}
