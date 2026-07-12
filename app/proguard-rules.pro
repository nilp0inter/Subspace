# The OpenAI Java SDK decodes generated API model classes through Jackson.
# Preserve the model surface and metadata when release shrinking is enabled.
-keep,allowoptimization class com.openai.** { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,AnnotationDefault
