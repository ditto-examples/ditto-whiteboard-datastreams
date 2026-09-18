# Whiteboard-specific R8 rules.
#
# AndroidX, Kotlin, coroutines, serialization, and Ditto supply consumer rules. Keep this file
# surgical so R8 can optimize the rest of the 200+ MiB dependency graph.

# protobuf-javalite resolves generated message fields by their original names at runtime.
-keep,allowoptimization,allowobfuscation class com.ditto.whiteboard.protocol.proto.** { *; }

# Optional OpenTelemetry types are referenced by Ditto but are not part of this app's runtime.
-dontwarn com.google.auto.value.**
-dontwarn com.google.errorprone.annotations.MustBeClosed
-dontwarn io.opentelemetry.api.incubator.**
-dontwarn javax.annotation.Nullable
-dontwarn javax.annotation.concurrent.Immutable
-dontwarn javax.annotation.concurrent.ThreadSafe
-dontwarn org.osgi.annotation.bundle.**
