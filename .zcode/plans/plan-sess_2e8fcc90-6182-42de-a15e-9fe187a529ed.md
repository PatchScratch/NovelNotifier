TTS: roadmap placement + build plan (no implementation this turn)

Decision: TTS goes on the roadmap for ~v2.3, after しおり (Syosetu reading-position sync) and Kakuyomu reading history. Wayne's technical direction is accepted: offline-first neural TTS via Sherpa-ONNX, with OpenAI-compatible cloud TTS as a key-based option and Android system TTS as automatic fallback.

Roadmap entry to record (memory + README roadmap when built):
- TTS (text-to-speech) for the reader — offline neural (Sherpa-ONNX + Supertonic 3, fallback Kokoro multi-lang), cloud via the existing OpenAI-compatible key, system TTS as fallback.

Build plan for when its time comes (phased, each phase shippable):
1. Verify artifacts live (nothing built on assumptions):
   - Sherpa-ONNX Android AAR coordinates (JitPack/prebuilt), JNI bindings, streaming callback API.
   - Supertonic 3: existence on sherpa's model zoo, license (critical — models must allow redistribution or first-use download), real EN+JP quality on-device, size. If it fails any check → Kokoro multi-lang v1.0 models.
   - OpenAI-compatible cloud TTS endpoint shape (/audio/speech parity across OpenRouter-compatible providers).
2. Engine + model management:
   - TtsEngine wrapper (init, synthesize-to-PCM streaming, AudioTrack playback), prefs: engine off/system/neural/cloud, voice, speed.
   - Model download helper copying the JMdict toggle UX: Settings-gated first-enable with progress (~100–350MB into filesDir), deletable, never in the APK.
3. Reader integration:
   - "Speak chapter" in reader ⋮ + play/pause/stop row while active; speaks the current layout (JP+EN / JP / EN from xlate_layout; "speak translated text" toggle).
   - Sentence/paragraph chunking from extractResult.paragraphs (+ stored translation); paragraph highlight + auto-scroll while speaking; auto-advance to next chapter via the existing prev/next model.
4. Background playback:
   - Foreground service (EpubBulkService pattern) + MediaSession notification (play/pause/next/stop), lock-screen safe.
5. UX extras + persistence:
   - Playback speed, sleep timer, voice picker; persist via existing novel_prefs.
6. Cloud TTS toggle:
   - Reuse xlate_endpoint/xlate_key; per-chapter cost note in Settings copy; cache audio per chapter (filesDir, excluded from backups) so replays are free.
7. Tests: chunker, language-selection rule, pref plumbing; live device verification (both languages, screen-off, speed control) per house verification standard.

This turn's only action items: record the roadmap decision in project memory (deferred — plan mode) and mention nothing in READMEs yet (documented when the feature ships).