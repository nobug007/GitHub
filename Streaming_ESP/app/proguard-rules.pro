# ONNX Runtime 은 JNI 로 클래스를 찾아 들어가므로 난독화 대상에서 제외한다.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
