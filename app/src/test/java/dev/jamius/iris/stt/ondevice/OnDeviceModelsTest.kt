/*
* Author: Jamius Siam
* Since: 13/11/2026
*/

package dev.jamius.iris.stt.ondevice

import dev.jamius.iris.data.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDeviceModelsTest {

    @Test
    fun idsAreUniqueAndTheDefaultExists() {
        assertEquals(ON_DEVICE_MODELS.size, ON_DEVICE_MODELS.map { it.id }.toSet().size)
        assertEquals(Settings.DEFAULT_ON_DEVICE_STT_MODEL, ON_DEVICE_MODELS.first().id)
        assertEquals("parakeet-unified-en", Settings.DEFAULT_ON_DEVICE_STT_MODEL)
    }

    @Test
    fun filesArePinnedAndWellFormed() {
        val url = Regex("https://huggingface\\.co/[^/]+/[^/]+/resolve/[0-9a-f]{40}/[^/]+")
        for (model in ON_DEVICE_MODELS) {
            assertEquals(model.id, model.files.size, model.files.map { it.name }.toSet().size)
            for (file in model.files) {
                assertTrue(model.url(file), url.matches(model.url(file)))
                assertTrue(model.url(file).endsWith("/${file.name}"))
                assertTrue(file.name, Regex("[0-9a-f]{64}").matches(file.sha256))
                assertTrue(file.name, file.sizeBytes > 0)
            }
            assertEquals(model.id, model.engine == OnDeviceEngine.NEMO_TRANSDUCER, model.joiner != null)
        }
    }

    @Test
    fun totalsMatchTheCatalogue() {
        assertEquals(663_043_117L, onDeviceModel("parakeet-unified-en").sizeBytes)
        assertEquals(670_576_784L, onDeviceModel("parakeet-tdt-v3").sizeBytes)
        assertEquals(375_485_327L, onDeviceModel("whisper-small").sizeBytes)
        assertEquals(141_300_566L, onDeviceModel("moonshine-base-en").sizeBytes)
    }

    @Test
    fun unknownIdIsTheDefaultModel() {
        assertEquals(Settings.DEFAULT_ON_DEVICE_STT_MODEL, onDeviceModel("x").id)
        assertEquals("whisper-small", onDeviceModel("whisper-small").id)
    }

    @Test
    fun whisperLanguagesAreValidated() {
        assertEquals(99, WHISPER_LANGUAGES.size)
        assertEquals("en", whisperLanguage(" EN "))
        assertEquals("haw", whisperLanguage("haw"))
        assertEquals("", whisperLanguage("yue"))
        assertEquals("", whisperLanguage("en-US"))
        assertEquals("", whisperLanguage(""))
    }
}
