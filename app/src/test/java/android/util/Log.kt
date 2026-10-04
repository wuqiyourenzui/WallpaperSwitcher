package android.util

/**
 * 单元测试用的 `android.util.Log` 桩：AGP 默认给的是「not mocked」抛异常的版本，
 * 于是任何走到 AppLog 的代码路径（例如 `@js:` 规则里的一次 `AppLog.w`）都会让
 * 测试在无关的地方炸掉，真正的规则输出反而看不到。
 *
 * 只在 test 源集里存在，不参与打包。
 */
object Log {
    @JvmStatic fun d(tag: String?, msg: String?): Int = 0
    @JvmStatic fun d(tag: String?, msg: String?, tr: Throwable?): Int = 0
    @JvmStatic fun i(tag: String?, msg: String?): Int = 0
    @JvmStatic fun i(tag: String?, msg: String?, tr: Throwable?): Int = 0
    @JvmStatic fun w(tag: String?, msg: String?): Int = 0
    @JvmStatic fun w(tag: String?, msg: String?, tr: Throwable?): Int = 0
    @JvmStatic fun w(tag: String?, tr: Throwable?): Int = 0
    @JvmStatic fun e(tag: String?, msg: String?): Int = 0
    @JvmStatic fun e(tag: String?, msg: String?, tr: Throwable?): Int = 0
    @JvmStatic fun v(tag: String?, msg: String?): Int = 0
    @JvmStatic fun v(tag: String?, msg: String?, tr: Throwable?): Int = 0
    @JvmStatic fun wtf(tag: String?, msg: String?): Int = 0
    @JvmStatic fun wtf(tag: String?, tr: Throwable?): Int = 0
    @JvmStatic fun isLoggable(tag: String?, level: Int): Boolean = false
    @JvmStatic fun getStackTraceString(tr: Throwable?): String = tr?.stackTraceToString().orEmpty()
}
