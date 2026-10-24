# ===========================================================================
# RooMCutNext R8 rules (v1.6.0 — minify first enabled)
#
# 策略：业务包（@Serializable 项目模型、手写 JSON 边界、Compose UI）整体
# 保留 —— 它只占 ~2MB，保留它可把反射/序列化风险压到零；体积收益全部
# 来自库侧裁剪（material-icons-extended、Compose 未用分支、Media3 未用
# 端点等）。映射文件随构建产出（build/outputs/mapping/release/）。
# ===========================================================================

# ---- 业务包：整体保留（含生成的 $$serializer） ----
-keep class com.RooMCutNext.** { *; }

# ---- kotlinx.serialization ----
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---- Media3 ----
# Media3 AAR 自带 consumer 规则；这里兜底 Transformer/Effect 的 @UnstableApi
# 反射入口与 JNI 侧（Cronet 不在依赖内，dontwarn 掉）。
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**
-dontwarn com.google.android.exoplayer2.**

# ---- OkHttp / Okio（Coil 传递引入） ----
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# ---- Coroutines debug probe（release 无用但被引用） ----
-dontwarn kotlinx.coroutines.debug.**

# ---- Compose：默认规则足够；保留 Preview 注解处理痕迹 ----
-dontwarn androidx.compose.**

# ---- 枚举 values()/valueOf()（默认模板已含，显式钉死） ----
-keepclassmembers enum com.RooMCutNext.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
