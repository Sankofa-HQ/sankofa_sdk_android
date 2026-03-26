# Keep all public Sankofa SDK classes so developers can reference them by name
-keep class dev.sankofa.sdk.Sankofa { *; }
-keep class dev.sankofa.sdk.SankofaConfig { *; }

# Room – keep entity and DAO classes
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keep @androidx.room.Database class * { *; }

# WorkManager – keep worker class name so it can be instantiated by class name
-keep class dev.sankofa.sdk.network.SyncWorker { *; }

# Gson – keep data model fields serialized/deserialized via reflection
-keepattributes Signature
-keepattributes *Annotation*
-keep class sun.misc.Unsafe { *; }
