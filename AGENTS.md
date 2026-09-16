# Jewel Tooling

Use Kotlin and two-space indentation. User-visible strings belong in messages/JewelToolingBundle.properties.
Use Kotlin Analysis API resolution; do not infer Compose annotations from short names or impose Gradle/Bazel-specific project detection.
Keep analysis symbols inside their session and preserve cancellation. Unsupported inference must produce unknown, not stable.
Use the installed Gradle compact-output wrapper / build-brief. Validate with test and buildPlugin before handing off a ZIP.
Do not widen IDE compatibility without compiling and testing against that baseline.
