# Keep runtime-visible metadata used by Hilt, Moshi, kotlinx.serialization, and LiteRT bindings.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Schemas are serialized to persisted JSON between app versions.
-keep class com.google.ai.edge.gallery.customtasks.vlogpilot.schemas.** { *; }
-keep class com.google.ai.edge.gallery.customtasks.vlogpilot.worker.EventDecisions { *; }
-keep class com.google.ai.edge.gallery.customtasks.vlogpilot.worker.EventInputManifest { *; }
-keep class com.google.ai.edge.gallery.customtasks.vlogpilot.worker.EventSelectionManifest { *; }
-keep class com.google.ai.edge.gallery.customtasks.vlogpilot.worker.EventSelectionCandidate { *; }

# Native/runtime libraries ship their own consumer rules, but keep public entrypoints stable.
-keep class ai.onnxruntime.** { *; }
-keep class com.google.mediapipe.** { *; }
-keep class com.google.ai.edge.litertlm.** { *; }

# Optional MediaPipe graph/profiler proto types are referenced by APIs we do not call.
-dontwarn com.google.mediapipe.proto.CalculatorProfileProto$CalculatorProfile
-dontwarn com.google.mediapipe.proto.GraphTemplateProto$CalculatorGraphTemplate
