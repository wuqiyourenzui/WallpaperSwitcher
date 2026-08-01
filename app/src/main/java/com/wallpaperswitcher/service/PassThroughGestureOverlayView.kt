package com.wallpaperswitcher.service

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View

/**
 * 透明全屏手势检测 View
 * 关键：dispatchTouchEvent 中检测手势后返回 false，
 * 事件不被消费，正常传递给下层应用
 */
class PassThroughGestureOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    onDoubleTap: () -> Unit = {}
) : View(context, attrs, defStyleAttr) {

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                onDoubleTap()
                return true
            }
        }
    )

    /**
     * 拦截所有触摸事件用于手势检测，但不消费
     * 返回 false = 事件继续传递给下层
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        return false // 不消费，让下层应用正常接收触摸
    }
}
