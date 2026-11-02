# Third-party notices

Xiyan Agent application and core source in this repository is licensed under GPL-3.0-only; see LICENSE. Copyright (c) 2026 Xiyan Agent contributors.

No source or binary from Mobile Codex or a third-party Termux port is included in this P0 APK. Mobile Codex was reviewed as a research reference only; upstream references and versions are recorded in docs/P0-feasibility.md.

The P0.8 UI refresh studies the layout and interaction patterns of Mobile Codex at commit 0bca7f0eed28eb3a3c9554db38e13d00136b95d0 (https://github.com/nokryong/mobile-codex). Its original code and artwork are GPL-3.0-only. That version's native layout, palette and drawn icons were independently implemented; no upstream web bundle, logo or artwork is packaged. Reference paths are listed in docs/UI-REFRESH-MOBILE-CODEX.zh-CN.md.

The Design System 1.0 refresh uses 33 Lucide functional icons at revision a529c28a8782e7954dde3d1a5e4409e829242b86 (https://github.com/lucide-icons/lucide). Lucide is ISC-licensed and its complete LICENSE includes the MIT notices for listed Feather-derived icons. The original SVGs and complete notices are in third_party/lucide/; PROVENANCE.json records the original and derived hashes. SVG geometry is converted to Android VectorDrawable with a uniform 24-unit viewport, 2-unit rounded strokes and runtime tint. Complete notices are also bundled in the APK at assets/licenses/lucide-LICENSE.txt. Xiyan's crossing-ribbon brand and launcher mark are original. Commercial product screenshots remain research references and are not shipped.

The P0 APK contains application Java code compiled with the Android SDK and uses Android platform APIs. It does not bundle Codex, V8, Rust runtime artifacts, Android NDK libraries, or an external AI SDK.

Build and test tools, not bundled in the APK:

| Component | Version | License/source |
| --- | --- | --- |
| Gradle wrapper/build tool | 8.11.1 | Apache-2.0, https://github.com/gradle/gradle |
| Android Gradle Plugin | 8.9.2 | Apache-2.0, https://android.googlesource.com/platform/tools/base |
| JUnit | 4.13.2 | EPL-1.0, https://github.com/junit-team/junit4 |
| Hamcrest Core (JUnit dependency) | 1.3 | BSD-3-Clause, https://github.com/hamcrest/JavaHamcrest |

The separate runtime experiment downloads official OpenAI Codex source and its locked dependencies to the cloud workspace. Those experimental files are not shipped in the APK. Evidence, upstream provenance, and hashes are kept in docs/runtime/. Any future bundled runtime requires an updated dependency inventory and complete applicable licenses before release.
