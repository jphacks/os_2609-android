package com.google.ar.core.examples.kotlin.helloar

import kotlin.random.Random

/**
 * 範囲の中からランダムに 1 つ選ぶ（Swift の Float.random(in:) にあたる）
 * Kotlin の標準ライブラリには、Float の範囲から選ぶ関数がないため
 */
fun ClosedFloatingPointRange<Float>.random(): Float =
  start + Random.nextFloat() * (endInclusive - start)
