package com.samarth.calibreios

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform