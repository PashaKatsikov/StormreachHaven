package com.stormreachhaven.stormreachgame.model

enum class GameScreen {
    LOADING, MENU, ZONES, GEMS, GAME, RESULT, HAVEN, UPGRADES, COLLECTION, SETTINGS, TUTORIAL
}

data class Zone(
    val name: String,
    val subtitle: String,
    val background: String,
    val difficulty: Int
)

data class Gem(
    val name: String,
    val trait: String,
    val color: Int,
    val unlockLevel: Int,
    val extraCycles: Int = 0
)

data class ForgeSession(
    var charge: Int = 0,
    var requiredCharge: Int = 6,
    var perfects: Int = 0,
    var perfectStreak: Int = 0,
    var maxPerfectStreak: Int = 0,
    var misses: Int = 0,
    var targetPoint: Float = .5f,
    var cycleStarted: Long = System.currentTimeMillis(),
    var lastHit: String = "",
    var lastHitAt: Long = 0L
)

data class ForgeReward(
    val quality: String = "RAW",
    val energy: Int = 0,
    val fragments: Int = 0,
    val stones: Int = 0,
    val relics: Int = 0
)

enum class GameEvent {
    CLICK, STRIKE, PERFECT, DIVINE, ERROR, REWARD, RESTORE
}
