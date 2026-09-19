# Minification is disabled for V1 to keep the native JNI bridge and
# kotlinx-serialization models stable. Add rules here before enabling R8.
-keep class com.shortsclipper.ai.** { *; }
