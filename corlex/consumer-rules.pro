# Corlex needs almost no keep rules: the crash reporter sends frames exactly as the JVM gave them (obfuscated); the server retraces
# them with the mapping.txt uploaded by the `ru.corlex.symbols` Gradle plugin.
#
# Keep line numbers and file names so the retrace can show them:
-keepattributes SourceFile,LineNumberTable

# WorkManager stores the worker's class name; an obfuscated name would break pending work after an update.
-keep class ru.corlex.internal.DeliveryWorker
