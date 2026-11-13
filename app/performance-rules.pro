# The instrumentation APK shares dependencies with the tested APK. Keep entry
# points/names for cross-APK calls, while allowing R8 method body optimizations.
# This applies only to the local, non-debug performance variant, never release.
-keep,allowoptimization class ** { *; }

# Compile-time-only annotation signatures are retained by the test API rule.
-dontwarn javax.lang.model.element.Modifier
