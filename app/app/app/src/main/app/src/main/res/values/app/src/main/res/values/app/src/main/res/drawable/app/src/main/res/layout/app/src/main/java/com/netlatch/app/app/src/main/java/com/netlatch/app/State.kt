package com.netlatch.app

object State {
    @Volatile var running = false
    @Volatile var lag = false
    @Volatile var endpoints = 0
}
