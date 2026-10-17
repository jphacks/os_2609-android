package com.example.sunshine.model

/**
 * 対戦する地点
 * id は iOS の Location.swift の Location.registered と同じにする。違うと、同じ場所にいてもマッチングしない
 */
data class Location(val id: String, val name: String) {
    companion object {
        val registered = listOf(
            Location("entrance", "入口"),
            Location("booth_a", "ブースA"),
            Location("booth_b", "ブースB"),
            Location("stairs", "階段"),
        )
    }
}
