package com.example.sunshine.service

import com.example.sunshine.BuildConfig

/**
 * Supabase プロジェクトの接続先
 * 値は local.properties（コミットしない）から BuildConfig に入れている。書き方は CLAUDE.md
 * - supabase.url：Project Settings → Data API の Project URL（例："https://xxxx.supabase.co"）
 * - supabase.anonKey：Project Settings → API Keys の Legacy タブにある anon（public）key。service_role（secret）は入れない
 */
object SupabaseConfig {
    const val URL = BuildConfig.SUPABASE_URL
    const val ANON_KEY = BuildConfig.SUPABASE_ANON_KEY

    /** 値が空なら、MainActivity は DummyBattleService を使う */
    val isConfigured: Boolean get() = URL.isNotEmpty() && ANON_KEY.isNotEmpty()
}
