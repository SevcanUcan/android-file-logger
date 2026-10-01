# Instrumentation dependencies are split between the test and target APKs.
# Keep shared runtime classes in the target APK for the release-like test build only.
-keep class androidx.tracing.** { *; }
-keep class kotlin.** { *; }

# The separately compiled instrumentation APK calls the library's public ABI.
-keep public class com.filelogger.** { public *; }
-keep public interface com.filelogger.** { public *; }
-keep public enum com.filelogger.** { public *; }
