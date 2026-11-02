# Third-party notices

Xiyan Agent application and core source in this repository is licensed under GPL-3.0-only; see LICENSE. Copyright (c) 2026 Xiyan Agent contributors.

No source or binary from Mobile Codex or a third-party Termux port is included in this P0 APK. Mobile Codex was reviewed as a research reference only; upstream references and versions are recorded in docs/P0-feasibility.md.

The P0 APK contains application Java code compiled with the Android SDK and uses Android platform APIs. It does not bundle Codex, V8, Rust runtime artifacts, Android NDK libraries, or an external AI SDK.

Build and test tools, not bundled in the APK:

| Component | Version | License/source |
| --- | --- | --- |
| Gradle wrapper/build tool | 8.11.1 | Apache-2.0, https://github.com/gradle/gradle |
| Android Gradle Plugin | 8.9.2 | Apache-2.0, https://android.googlesource.com/platform/tools/base |
| JUnit | 4.13.2 | EPL-1.0, https://github.com/junit-team/junit4 |
| Hamcrest Core (JUnit dependency) | 1.3 | BSD-3-Clause, https://github.com/hamcrest/JavaHamcrest |

The separate runtime experiment downloads official OpenAI Codex source and its locked dependencies to the cloud workspace. Those experimental files are not shipped in the APK. Evidence, upstream provenance, and hashes are kept in docs/runtime/. Any future bundled runtime requires an updated dependency inventory and complete applicable licenses before release.
