# Third-party components

WebSurf uses AndroidX Core, AndroidX WebKit, AndroidX Media3, their transitive AndroidX dependencies, and the Android/Java platform APIs. AndroidX components are distributed under the [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0). Dependency coordinates and versions are recorded in `app/build.gradle`.

Java compatibility support is provided by [desugar_jdk_libs](https://github.com/google/desugar_jdk_libs), including OpenJDK-derived components under GPLv2 with the Classpath Exception and accompanying notices. Gradle Wrapper is distributed under Apache License 2.0. JUnit is used only in tests under EPL 1.0; AndroidX Test uses Apache License 2.0.

System WebView is supplied by the device and uses the Chromium project; its notices and licenses are provided with that system component.

WebSurf's UI colors and layout conventions follow the user's MediaMaster project. Its interface icons are imported from [Lucide](https://github.com/lucide-icons/lucide/tree/be09619c1c1124ad09c19fc3e87ee3b939409bbd/icons), pinned to commit `be09619c1c1124ad09c19fc3e87ee3b939409bbd`, and mechanically converted from the original SVG geometry to Android vectors. Lucide uses the ISC license; Feather-derived icons retain the accompanying MIT notice. Original SVGs, per-file hashes, provenance and the complete upstream license are included in [`assets/icons/lucide`](assets/icons/lucide/), especially [`provenance.json`](assets/icons/lucide/provenance.json) and [`LICENSE`](assets/icons/lucide/LICENSE). The same license is bundled in the app at `app/src/main/assets/licenses/Lucide-LICENSE.txt`.

The launcher logo remains original WebSurf artwork. No QQ Browser code, graphics, fonts, native libraries or online scripts are included in the application or source delivery.

REA and JADX were used as analysis references/tools only. They are not application dependencies. Android fixture media are locally generated test patterns and synthetic audio, included only in test sources.
