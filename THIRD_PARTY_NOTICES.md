# Third-party components

WebSurf uses AndroidX Core, AndroidX WebKit, AndroidX Media3, their transitive AndroidX dependencies, and the Android/Java platform APIs. AndroidX components are distributed under the [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0). Dependency coordinates and versions are recorded in `app/build.gradle`.

Java compatibility support is provided by [desugar_jdk_libs](https://github.com/google/desugar_jdk_libs), including OpenJDK-derived components under GPLv2 with the Classpath Exception and accompanying notices. Gradle Wrapper is distributed under Apache License 2.0. JUnit is used only in tests under EPL 1.0; AndroidX Test uses Apache License 2.0.

System WebView is supplied by the device and uses the Chromium project; its notices and licenses are provided with that system component.

WebSurf's UI colors and layout conventions follow the user's MediaMaster project. WebSurf's line icons and launcher artwork are original vector drawings. No QQ Browser code, graphics, fonts, native libraries or online scripts are included in the application or source delivery.

REA and JADX were used as analysis references/tools only. They are not application dependencies. Android fixture media are locally generated test patterns and synthetic audio, included only in test sources.
