package com.deepseek.clone.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path

/**
 * 官方 DeepSeek 蓝色鲸鱼标志（扁平化），整个 App 唯一的插画元素。
 *
 * 原版视觉原则：鲸鱼是与动作用同一种品牌蓝的、唯一插画；小而扁平，
 * 更像签名而非吉祥物——标明 AI 说话者，不抢戏。因此这里用 Canvas 手绘
 * 一条圆润鲸鱼侧影（含上扬尾鳍），避免依赖 emoji 的字体差异与多色渲染。
 */
@Composable
fun WhaleLogo(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val path = Path().apply {
                // 鲸鱼身体：拉长椭圆 + 头部，尾鳍向上扬起
                moveTo(w * 0.82f, h * 0.30f)                 // 尾鳍上端
                cubicTo(w * 0.92f, h * 0.30f, w * 0.98f, h * 0.42f, w * 0.92f, h * 0.52f) // 尾鳍右侧
                cubicTo(w * 0.98f, h * 0.62f, w * 0.90f, h * 0.72f, w * 0.82f, h * 0.70f) // 尾鳍下侧
                cubicTo(w * 0.62f, h * 0.98f, w * 0.38f, h * 0.98f, w * 0.20f, h * 0.70f) // 腹部
                cubicTo(w * 0.06f, h * 0.50f, w * 0.06f, h * 0.34f, w * 0.26f, h * 0.22f) // 头部
                cubicTo(w * 0.40f, h * 0.14f, w * 0.62f, h * 0.14f, w * 0.72f, h * 0.22f) // 背部
                cubicTo(w * 0.76f, h * 0.25f, w * 0.78f, h * 0.28f, w * 0.82f, h * 0.30f) // 连回尾鳍
                close()
            }
            drawPath(path, color = AppColors.Primary)
            // 眼睛：白色圆点
            drawCircle(
                color = Color.White,
                radius = w * 0.035f,
                center = Offset(w * 0.30f, h * 0.38f)
            )
        }
    }
}
