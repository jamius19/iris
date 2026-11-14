# Third-party code and assets

Where each vendored or bundled piece came from. Update the row when bumping a version. Licence texts ship in
`app/src/main/assets/`.

| What | Source | Pin | Licence file |
|---|---|---|---|
| sherpa-onnx AAR | JitPack `com.github.k2-fsa.sherpa-onnx:sherpa-onnx` v2.0.1 (= the GitHub release AAR) | SHA-256 `a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7` | `sherpa_onnx_LICENSE.txt` (Apache-2.0) |
| Sonic (`audio/sonic/Sonic.java`) | https://github.com/waywardgeek/sonic @ `1d7051354f32392a3b3cdb8a8bcfe56e6f8b744f` | upstream SHA-256 `c23fb313fafa9cdab4c9aa291bfdc2426f33a42d0e19e3e8d1cfd3f85258d7df` | `sonic_LICENSE.txt` (Apache-2.0) |
| Silero VAD (`silero_vad.onnx`) | https://github.com/snakers4/silero-vad v6.2.3 | SHA-256 `1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3` | `silero_vad_LICENSE.txt` (MIT) |
| Inter (`res/font/inter.ttf`) | https://github.com/google/fonts @ `3e5667764ccfea32ce0c3d58a3a4756de890adc1`, `ofl/inter/Inter[opsz,wght].ttf` | SHA-256 `29160a80ff49ddcab2c97711247e08b1fab27a484a329ce8b813d820dc559031` | `inter_OFL.txt` |
| Blinker (`res/font/blinker_semibold.ttf`) | same commit, `ofl/blinker/Blinker-SemiBold.ttf` | SHA-256 `a9a701eb18cbd5f786f28f986c16895ffc0d0337e4a9a63131b619d9542e07d4` | `blinker_OFL.txt` |
| Lucide icons (`res/drawable/lucide_*.xml`) | https://github.com/lucide-icons/lucide/tree/1.72.0/icons | 1.72.0 | `lucide_LICENSE.txt` (ISC + Feather MIT) |

## Notes
- **sherpa-onnx**: the checksum is enforced by `gradle/verification-metadata.xml` (sherpa only; other groups are
  trusted by a regex). JitPack is an `exclusiveContent` repository in `settings.gradle.kts`. The AAR ships its own
  ONNX Runtime 1.28.2. Maven Central has no `onnxruntime-android` 1.28.2, and 1.29.0 adds default-on telemetry.
- **Sonic**: only the package line was changed (noted in its header).
- **Inter** is the variable font; one instance per weight (400 / 500 / 600 / 800), opsz left at 14.
- **Lucide**: SVGs converted by hand to vector drawables (viewport 24, white stroke tinted in Compose, circles and
  rects converted to paths). Stroke widths are tuned per use size to match the Figma export. Several icons are
  Feather-derived, hence the MIT block in the licence file.
- **Brand marks** (`provider_*.xml`: Vercel, OpenRouter, OpenAI, Deepgram, Soniox, NVIDIA, Moonshine): path data
  copied from user-supplied SVGs. The marks belong to their owners; no licence file.
- **Logo** (`iris_logo.xml`): converted from the designer's `iris_logo.svg`.
- **On-device models** are downloaded, not bundled: see `docs/providers.md` for repos, commits and licences.
