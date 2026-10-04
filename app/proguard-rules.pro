# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Compose
-dontwarn androidx.compose.**

# Coil needs no keep rules (its public API is used directly, no reflection).
-dontwarn coil.**

# Rhino (阅读 @js: / <js> rules): reflection + generated classes must survive R8.
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**
-dontwarn org.mozilla.classfile.**

# 阅读 JS 里通过反射调用的辅助对象（java.getString / source.getVariable /
# cookie.setCookie / java.post …）没有任何 Kotlin 调用点，R8 会直接删掉这些方法，
# 导致 release 包里 @js: 规则静默失效 —— 必须整类保留。
-keep class com.wallpaperswitcher.engine.legado.LegadoJsHelpers { *; }
-keep class com.wallpaperswitcher.engine.legado.LegadoCookieHelper { *; }
-keep class com.wallpaperswitcher.engine.legado.LegadoJsResponse { *; }
