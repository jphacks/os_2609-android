package com.google.ar.core.examples.kotlin.helloar.service

import com.google.ar.core.examples.kotlin.helloar.BuildConfig

/**
 * Supabase の接続先（iOS と同じデータベース）
 * URL と anon key は、コミットしない local.properties に書く（書き方は CLAUDE.md）。ビルドするときに BuildConfig に入る
 * service_role（secret）の鍵はアプリに入れない
 */
object SupabaseConfig {
  val url: String = BuildConfig.SUPABASE_URL
  val anonKey: String = BuildConfig.SUPABASE_ANON_KEY

  /** 接続先が書いてあるか。未設定なら近接プレイヤー対戦の受付を開始しない */
  val isConfigured: Boolean
    get() = url.isNotEmpty() && anonKey.isNotEmpty()
}
