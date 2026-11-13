/*
* Author: Jamius Siam
* Since: 13/11/2026
*/

package dev.jamius.iris.stt.ondevice

import dev.jamius.iris.data.settings.Settings

/** The sherpa-onnx offline recognizer a model runs on. */
enum class OnDeviceEngine { NEMO_TRANSDUCER, WHISPER, MOONSHINE_V2 }

/** One file of a model, as downloaded from Hugging Face (verified by size and SHA-256). */
data class ModelFile(val name: String, val sizeBytes: Long, val sha256: String)

/**
 * A downloadable on-device model: its files at a pinned Hugging Face commit. [decoder] is the merged decoder for
 * Moonshine; [joiner] is set only for transducers.
 */
data class OnDeviceModel(
    val id: String,
    val label: String,
    val languages: String,
    val engine: OnDeviceEngine,
    val repo: String,
    val commit: String,
    val encoder: ModelFile,
    val decoder: ModelFile,
    val joiner: ModelFile?,
    val tokens: ModelFile,
    val licence: String,
    val licenceUrl: String,
) {
    val files: List<ModelFile> get() = listOfNotNull(encoder, decoder, joiner, tokens)
    val sizeBytes: Long get() = files.sumOf { it.sizeBytes }
    fun url(file: ModelFile) = "https://huggingface.co/$repo/resolve/$commit/${file.name}"
}

/** The on-device models in menu order. */
val ON_DEVICE_MODELS: List<OnDeviceModel> = listOf(
    OnDeviceModel(
        id = Settings.DEFAULT_ON_DEVICE_STT_MODEL,
        label = "Parakeet Unified EN",
        languages = "English",
        engine = OnDeviceEngine.NEMO_TRANSDUCER,
        repo = "csukuangfj2/sherpa-onnx-nemo-parakeet-unified-en-0.6b-int8-non-streaming",
        commit = "8c3a10fb13408c7a7054f6898958bf1c64a8d6c7",
        encoder = ModelFile(
            "encoder.int8.onnx",
            654_040_552,
            "6716910b7a0833997fec7a410494c995d70124001a0e9b66d6370d6aced577e0"
        ),
        decoder = ModelFile(
            "decoder.int8.onnx",
            7_257_753,
            "a5e223392c90e75f8144cdb5eb95af7625db389e39edef2bd1a9c872b3298fe6"
        ),
        joiner = ModelFile(
            "joiner.int8.onnx",
            1_735_860,
            "869f43f7d24595c55581ad3bf249a935fb8a71389fbdaa7504b9f46f93140f8a"
        ),
        tokens = ModelFile("tokens.txt", 8_952, "dc0b4584ab2e4ddbf888425c076c61b736e7356a015250db7d307e6f1a8188ff"),
        licence = "Licensed by NVIDIA Corporation under the NVIDIA Open Model License",
        licenceUrl = "https://www.nvidia.com/en-us/agreements/enterprise-software/nvidia-open-model-license/"
    ),
    OnDeviceModel(
        id = "parakeet-tdt-v3",
        label = "Parakeet TDT v3",
        languages = "25 European languages",
        engine = OnDeviceEngine.NEMO_TRANSDUCER,
        repo = "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8",
        commit = "ed5d745623472f97cfc2e6c2916ee248798fc5a8",
        encoder = ModelFile(
            "encoder.int8.onnx",
            652_282_294,
            "1afbe69c1ffdabee94755f42e4daf1d93549131a3da11356bda84fcef0f2c479"
        ),
        decoder = ModelFile(
            "decoder.int8.onnx",
            11_845_274,
            "0222a86147299d4dee90edf95b6d13827edb42ad8b7c9cf0b2582f030937c0b5"
        ),
        joiner = ModelFile(
            "joiner.int8.onnx",
            6_355_277,
            "cbd02f3696e9944970111fb439d9c3fd365a80374565f13f2b9c7007d253fc98"
        ),
        tokens = ModelFile("tokens.txt", 93_939, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d"),
        licence = "Parakeet TDT 0.6B v3 by NVIDIA, CC BY 4.0, converted to int8 ONNX by sherpa-onnx",
        licenceUrl = "https://creativecommons.org/licenses/by/4.0/"
    ),
    OnDeviceModel(
        id = "whisper-small",
        label = "Whisper Small",
        languages = "99 languages",
        engine = OnDeviceEngine.WHISPER,
        repo = "csukuangfj/sherpa-onnx-whisper-small",
        commit = "8f3c18b358db4d1f2fc1eae49d75cd20989e4309",
        encoder = ModelFile(
            "small-encoder.int8.onnx",
            112_442_483,
            "4cbe7b22fa9026b843b60a68640c747de05bafb1a11b57edc0e66c232d9f33a9"
        ),
        decoder = ModelFile(
            "small-decoder.int8.onnx",
            262_226_114,
            "acad50b5c782696e91b55914cc5ab4f756f1532f76e22aa6fc615f39fb69a8ee"
        ),
        joiner = null,
        tokens = ModelFile(
            "small-tokens.txt",
            816_730,
            "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126"
        ),
        licence = "Whisper by OpenAI, MIT License",
        licenceUrl = "https://github.com/openai/whisper/blob/main/LICENSE"
    ),
    OnDeviceModel(
        id = "moonshine-base-en",
        label = "Moonshine Base EN",
        languages = "English, fastest",
        engine = OnDeviceEngine.MOONSHINE_V2,
        repo = "csukuangfj2/sherpa-onnx-moonshine-base-en-quantized-2026-02-27",
        commit = "8f4d6c58c03d40bcea40043bb7120a878f2bbef6",
        encoder = ModelFile(
            "encoder_model.ort",
            31_326_816,
            "7c66495948d0d08ec1af454cd4b5514862ae6511e94712a60e6d83eaec8dc8cf"
        ),
        decoder = ModelFile(
            "decoder_model_merged.ort",
            109_424_400,
            "d9d7b333af34bc552580576ddcf248a1c6c839e0d3b43b09afb9376ed009899d"
        ),
        joiner = null,
        tokens = ModelFile("tokens.txt", 549_350, "2870d843e14c1e187bf1913a521562a63b53933814bd7f2145120468f494a049"),
        licence = "Moonshine by Moonshine AI, MIT License",
        licenceUrl = "https://huggingface.co/csukuangfj2/sherpa-onnx-moonshine-base-en-quantized-2026-02-27/blob/" +
            "8f4d6c58c03d40bcea40043bb7120a878f2bbef6/LICENSE"
    ),
)

/** The catalogue entry for [id]; an unknown id means the default model. */
fun onDeviceModel(id: String): OnDeviceModel = ON_DEVICE_MODELS.firstOrNull { it.id == id }
    ?: ON_DEVICE_MODELS.first { it.id == Settings.DEFAULT_ON_DEVICE_STT_MODEL }

/**
 * Whisper Small's language codes (its encoder's `all_language_codes` metadata). sherpa-onnx calls
 * `SHERPA_ONNX_EXIT(-1)` (kills the process) on a language Whisper doesn't know, so only these may reach it.
 */
val WHISPER_LANGUAGES: Set<String> = (
    "en zh de es ru ko fr ja pt tr pl ca nl ar sv it id hi fi vi he uk el ms cs ro da hu ta no th ur hr bg lt la mi " +
        "ml cy sk te fa lv bn sr az sl kn et mk br eu is hy ne mn bs kk sq sw gl mr pa si km sn yo so af oc ka be tg " +
        "sd gu am yi lo uz fo ht ps tk nn mt sa lb my bo tl mg as tt haw ln ha ba jw su"
    ).split(" ").toSet()

/** The language setting as Whisper takes it: trimmed and lowercased if Whisper knows it, else "" (auto-detect). */
fun whisperLanguage(setting: String): String = setting.trim().lowercase().takeIf { it in WHISPER_LANGUAGES } ?: ""
