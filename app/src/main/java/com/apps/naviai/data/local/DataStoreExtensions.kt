package com.apps.naviai.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

// preferencesDataStore must be a top-level property: DataStore enforces a
// single instance per file, and this delegate guarantees that even if
// multiple repositories are constructed with different Context instances
// (e.g. Application vs. a test context).
val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "navi_ai_settings")
val Context.calibrationDataStore: DataStore<Preferences> by preferencesDataStore(name = "navi_ai_calibration")
