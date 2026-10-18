package com.google.ar.core.examples.kotlin.helloar.battle

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * バトル画面の見た目をそろえるための色・文字・形（iOS の BattleStyle と同じ見た目）
 * 丸みを使わず、角ばった形、ビビッドな色、ぼかさない影で、格闘ゲームやパチンコのようなパキッとした見た目にする
 */
object BattleStyle {
  val pink = Color(1f, 0.1f, 0.55f)
  val cyan = Color(0f, 0.9f, 1f)
  val yellow = Color(1f, 0.92f, 0f)
  val lime = Color(0.6f, 1f, 0f)
  val red = Color(1f, 0.12f, 0.2f)
  /** パネルの地の色（ほぼ黒） */
  val ink = Color(0.04f, 0.04f, 0.1f)

  /** 見出しの文字。丸くない極太の斜体を、横に詰めて使う */
  fun display(size: TextUnit) = TextStyle(
    fontSize = size,
    fontWeight = FontWeight.Black,
    fontStyle = FontStyle.Italic,
    letterSpacing = (-0.03).em,
  )
}

/** 左右の端を斜めに切った四角形（平行四辺形）。バー、パネル、ボタンの形に使う。slant は上の辺を右に、下の辺を左にずらす幅 */
fun slantedRectangle(slant: Dp = 12.dp): Shape = SlantedRectangle(slant)

private class SlantedRectangle(private val slant: Dp) : Shape {
  override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
    val s = with(density) { slant.toPx() }
    return Outline.Generic(
      Path().apply {
        moveTo(s, 0f)
        lineTo(size.width, 0f)
        lineTo(size.width - s, size.height)
        lineTo(0f, size.height)
        close()
      },
    )
  }
}

/**
 * ぼかさずに右下へずらした影。shape の形で、後ろに描く
 * 文字に付けると、文字が 2 重に重なって見えて読みにくいので、パネルやボタンなどの形にだけ付ける
 */
fun Modifier.hardShadow(shape: Shape, color: Color = Color.Black, offset: Dp = 4.dp): Modifier = drawBehind {
  val px = offset.toPx()
  val outline = shape.createOutline(size, layoutDirection, this)
  translate(px, px) {
    drawIntoOutline(outline, color)
  }
}

private fun DrawScope.drawIntoOutline(outline: Outline, color: Color) {
  when (outline) {
    is Outline.Generic -> drawPath(outline.path, color)
    is Outline.Rectangle -> drawRect(color, Offset(outline.rect.left, outline.rect.top), outline.rect.size)
    is Outline.Rounded -> drawRect(color)
  }
}

/** 文字を、斜めに切った帯の上に載せる。カメラの映像の上でも、影を付けずに読めるようにする */
fun Modifier.onBand(color: Color, slant: Dp = 16.dp): Modifier {
  val shape = slantedRectangle(slant)
  return hardShadow(shape)
    .background(color, shape)
    .padding(horizontal = 28.dp, vertical = 4.dp)
}

/** HP などをまとめるパネル。黒い平行四辺形に、色の付いた枠を付ける */
fun Modifier.battlePanel(accent: Color): Modifier {
  val shape = slantedRectangle()
  return background(BattleStyle.ink.copy(alpha = 0.85f), shape)
    .border(2.dp, accent, shape)
    .padding(horizontal = 20.dp, vertical = 12.dp)
}

/** バトル画面のボタン。ビビッドな平行四辺形に黒い文字。押すと影の分だけ沈む */
@Composable
fun BattleButtonComponent(text: String, color: Color = BattleStyle.yellow, onClick: () -> Unit) {
  val interaction = remember { MutableInteractionSource() }
  val isPressed by interaction.collectIsPressedAsState()
  val shape = slantedRectangle(16.dp)
  val shadow = if (isPressed) 0.dp else 5.dp
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .offset(x = 5.dp - shadow, y = 5.dp - shadow)
      .hardShadow(shape, offset = shadow)
      .background(color, shape)
      .border(3.dp, Color.Black, shape)
      .clickable(interactionSource = interaction, indication = null, onClick = onClick)
      .padding(vertical = 14.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(text, style = BattleStyle.display(24.sp), color = Color.Black)
  }
}
