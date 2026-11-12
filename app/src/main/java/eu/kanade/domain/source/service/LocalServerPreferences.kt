package eu.kanade.domain.source.service

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.source.localserver.LocalServerSource
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/**
 * Preferencias de la fuente LocalServerSource.
 *
 * Expone [serverUrl] para que la pantalla de Ajustes pueda leer/escribir la URL
 * del servidor HTTP local usando el mismo [PreferenceStore] que el resto de la
 * app. La clave y el valor por defecto se toman de [LocalServerSource] para
 * garantizar que el ajuste de UI y el source apunten a la misma preferencia.
 */
@Inject
@SingleIn(AppScope::class)
class LocalServerPreferences(
    preferenceStore: PreferenceStore,
) {
    val serverUrl: Preference<String> = preferenceStore.getString(
        LocalServerSource.URL_KEY,
        LocalServerSource.URL_DEFAULT,
    )
}
