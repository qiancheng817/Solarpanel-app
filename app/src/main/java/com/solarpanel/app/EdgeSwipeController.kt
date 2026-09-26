package com.solarpanel.app

import android.app.Activity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.min

/**
 * 屏幕左缘手势：按下边缘热区并向右拖动。
 *
 * - 网页有历史：松手达到阈值 → 整页右滑退出后执行 [onBack]（回退网页历史）；
 * - 已在根页面：松手达到阈值 → 由 [onPrepareExit] 判断是否为"二次确认"，
 *   确认后整页右滑并 [onExit] 退出应用；第一次只给提示、页面回弹。
 *
 * 拖动过程中内容层 translationX 跟手移动，左侧露出暗色遮罩与返回箭头。
 *
 * @param movableViews 需要跟手平移的内容视图（网页、顶栏等）
 * @param canGoBack 当前是否还能在网页历史中后退
 * @param onBack 手势提交后要执行的后退动作
 * @param onPrepareExit 在根页面手势提交时调用；返回 true 表示可以退出（已处理二次确认计时），
 *                      返回 false 表示首次手势，已在实现内给出提示
 * @param onExit 真正退出应用的动作
 */
class EdgeSwipeController(
    private val activity: Activity,
    zone: View,
    private val scrim: View,
    private val arrow: View,
    private val movableViews: () -> List<View>,
    private val canGoBack: () -> Boolean,
    private val onBack: () -> Unit,
    private val onPrepareExit: () -> Boolean,
    private val onExit: () -> Unit,
) {

    private val touchSlop = ViewConfiguration.get(activity).scaledTouchSlop
    private val animationMs = 220L
    private val interpolator = DecelerateInterpolator()

    private var potential = false
    private var engaged = false
    private var startX = 0f
    private var startY = 0f

    private val screenWidth: Int
        get() = activity.resources.displayMetrics.widthPixels

    init {
        zone.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    potential = true
                    engaged = false
                    startX = event.rawX
                    startY = event.rawY
                }

                MotionEvent.ACTION_MOVE -> {
                    if (potential && !engaged) {
                        val dx = event.rawX - startX
                        val dy = event.rawY - startY
                        if (abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                            engaged = true
                        } else if (abs(dy) > touchSlop) {
                            // 纵向滑动，让位给网页滚动
                            potential = false
                        }
                    }
                    if (engaged) {
                        applyDrag(min(event.rawX - startX, screenWidth.toFloat()))
                    }
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    if (engaged) {
                        val dx = (event.rawX - startX).coerceIn(0f, screenWidth.toFloat())
                        release(dx)
                    }
                    potential = false
                    engaged = false
                }
            }
            // 热区消费自己的触摸事件；它只有 26dp 宽，不影响正常页面操作
            true
        }
    }

    private fun activeViews(): List<View> =
        movableViews().filter { it.visibility == View.VISIBLE }

    private fun applyDrag(dx: Float) {
        val progress = (dx / screenWidth).coerceIn(0f, 1f)
        activeViews().forEach { it.translationX = dx }
        scrim.alpha = progress * 0.8f
        arrow.alpha = progress
    }

    private fun release(dx: Float) {
        val threshold = (screenWidth * COMMIT_RATIO)
            .coerceAtLeast(MIN_COMMIT_DP * activity.resources.displayMetrics.density)
        if (dx < threshold) {
            cancelDrag()
            return
        }
        when {
            canGoBack() -> slideOutThen {
                onBack()
                snapBack()
            }
            onPrepareExit() -> slideOutThen {
                onExit()
            }
            else -> cancelDrag()
        }
    }

    private fun cancelDrag() {
        val views = activeViews()
        var pending = views.size + 2
        fun onDone() {
            pending--
            if (pending == 0) {
                views.forEach { it.translationX = 0f }
                scrim.alpha = 0f
                arrow.alpha = 0f
            }
        }
        views.forEach { view ->
            view.animate().translationX(0f)
                .setDuration(animationMs)
                .setInterpolator(interpolator)
                .withEndAction { onDone() }
                .start()
        }
        scrim.animate().alpha(0f).setDuration(animationMs)
            .withEndAction { onDone() }.start()
        arrow.animate().alpha(0f).setDuration(animationMs)
            .withEndAction { onDone() }.start()
    }

    private fun slideOutThen(action: () -> Unit) {
        val target = screenWidth.toFloat()
        val views = activeViews()
        var pending = views.size
        fun onDone() {
            pending--
            if (pending == 0) {
                action()
            }
        }
        views.forEach { view ->
            view.animate().translationX(target)
                .setDuration(animationMs)
                .setInterpolator(interpolator)
                .withEndAction { onDone() }
                .start()
        }
        scrim.animate().alpha(0.8f).setDuration(animationMs).start()
        arrow.animate().alpha(1f).setDuration(animationMs).start()
    }

    /** 动作完成后把内容层瞬间归位（网页已是后退后的页面）。 */
    private fun snapBack() {
        movableViews().forEach { it.translationX = 0f }
        scrim.alpha = 0f
        arrow.alpha = 0f
    }

    companion object {
        /** 滑动距离超过屏宽的该比例才提交手势。 */
        private const val COMMIT_RATIO = 0.28f
        private const val MIN_COMMIT_DP = 80f
    }
}
