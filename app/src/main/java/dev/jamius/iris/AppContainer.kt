/*
* Author: Jamius Siam
* Since: 11/11/2026
*/

package dev.jamius.iris

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import dev.jamius.iris.data.settings.SecretStore
import dev.jamius.iris.data.settings.SettingsRepository

/** Manual DI: app-wide singletons, created once by [IrisApp]. */
class AppContainer(context: Context) {

    // "secrets" is excluded from backup in res/xml/backup_rules.xml and data_extraction_rules.xml.
    val secretStore = SecretStore(
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("secrets") }
    )

    val settingsRepository = SettingsRepository(
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("settings") },
        secretStore
    )
}
