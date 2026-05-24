# Keep Hilt generated entry points and Compose metadata stable for release minification.
-keep class dagger.hilt.** { *; }
-keep class hilt_aggregated_deps.** { *; }
-keep class androidx.compose.** { *; }

# Keep app model classes used by future persistence/serialization migrations.
-keep class com.nuttavern.data.model.** { *; }

# Gson reads/writes persisted settings by field name. These classes must remain
# stable across minified internal builds, otherwise updates can make saved
# Provider/Assistant JSON unreadable.
-keepclassmembers class com.nuttavern.data.local.SettingsDataStore$ProviderConfigJson { *; }
