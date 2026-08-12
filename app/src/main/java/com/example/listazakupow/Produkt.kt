package com.example.listazakupow

data class Produkt(
    val id: String = "",
    val nazwa: String,
    val dodal: String,
    var kupione: Boolean = false,
    var kupioneOd: Long = 0L,
    var kolejnosc: Long = 0L,
    var kategoria: String = "glowna"
)