package com.stormreachhaven.stormreachgame.controller

import android.graphics.Color
import com.stormreachhaven.stormreachgame.model.*
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class GameController(
    val store: GameStore,
    private val emit: (GameEvent) -> Unit,
    private val changed: () -> Unit
) {
    var screen = GameScreen.LOADING
    var selectedZone = 0
    var selectedGem = 0
    var tutorialPage = 0
    val session = ForgeSession()
    var reward = ForgeReward()

    val zones = listOf(
        Zone("STORMREACH HAVEN", "Calm skies", "Stormreach_Haven_Background_asset.webp", 1),
        Zone("MOUNT OLYMPUS", "Cloud storm", "Mount_Olympus_Background_asset.webp", 2),
        Zone("TEMPLE OF ZEUS", "Thunder temple", "Temple_of_Zeus_Background_asset.webp", 2),
        Zone("AETHER ISLES", "Unstable currents", "Aether_Isles_Background_asset.webp", 3),
        Zone("VALLEY OF THUNDER", "Valley of thunder", "Valley_of_Thunder_Background_asset.webp", 4),
        Zone("CRYSTAL CAVERNS", "Crystal storm", "Crystal_Caverns_Background_asset.webp", 4),
        Zone("PALACE OF THE GODS", "Divine tempest", "Palace_of_the_Gods_Background_asset.webp", 5),
        Zone("CELESTIAL STORM", "Celestial storm", "Celestial_Storm_Background_asset.webp", 5)
    )

    val gems = listOf(
        Gem("RUBY", "Resists overload", Color.rgb(220, 32, 48), 0),
        Gem("SAPPHIRE", "Rewards perfect streaks", Color.rgb(30, 102, 255), 0),
        Gem("EMERALD", "Charges faster", Color.rgb(0, 190, 100), 2),
        Gem("AMETHYST", "Wider Perfect window", Color.rgb(145, 52, 220), 3, 2)
    )

    fun navigate(destination: GameScreen, click: Boolean = true) {
        if (click) emit(GameEvent.CLICK)
        screen = destination
        changed()
    }

    fun finishLoading() {
        if (store.bool("session_active", false)) {
            restoreActiveSession()
            screen = GameScreen.GAME
        } else {
            screen = if (store.bool("tutorial_done", false)) GameScreen.MENU else GameScreen.TUTORIAL
        }
        changed()
    }

    fun startGame() {
        session.apply {
            charge = 0
            perfects = 0
            perfectStreak = 0
            maxPerfectStreak = 0
            misses = 0
            lastHit = ""
            requiredCharge = 5 + selectedZone / 2 + gems[selectedGem].extraCycles
            targetPoint = randomTarget()
            cycleStarted = System.currentTimeMillis()
        }
        emit(GameEvent.STRIKE)
        screen = GameScreen.GAME
        store.putBool("session_active", true)
        persistSession()
        changed()
    }

    fun timingHit(marker: Float) {
        val normalHalf = normalWindow()
        val perfectHalf = perfectWindow()
        val distance = abs(marker - session.targetPoint)
        when {
            distance <= perfectHalf -> {
                val forgeBonus = if (store.int("forge_level", 1) >= 3) 1 else 0
                session.charge += (if (selectedGem == 2) 3 else 2) + forgeBonus
                session.perfects++
                session.perfectStreak++
                session.maxPerfectStreak = max(session.maxPerfectStreak, session.perfectStreak)
                if (session.perfectStreak % 3 == 0) session.charge++
                session.lastHit = "PERFECT!"
                emit(GameEvent.PERFECT)
            }
            distance <= normalHalf -> {
                session.charge += if (selectedGem == 2) 2 else 1
                session.perfectStreak = 0
                session.lastHit = "HIT"
                emit(GameEvent.STRIKE)
            }
            distance > .38f -> {
                val protected = selectedGem == 0 || store.int("forge_level", 1) >= 4
                session.charge = max(0, session.charge - if (protected) 0 else 1)
                session.misses++
                session.perfectStreak = 0
                session.lastHit = "OVERLOAD"
                emit(GameEvent.ERROR)
            }
            else -> {
                session.misses++
                session.perfectStreak = 0
                session.lastHit = "WEAK HIT"
                emit(GameEvent.ERROR)
            }
        }
        session.lastHitAt = System.currentTimeMillis()
        session.targetPoint = randomTarget()
        session.cycleStarted = System.currentTimeMillis()
        if (session.misses >= 5) {
            session.charge = max(0, session.charge - 2)
            session.misses = 2
        }
        if (session.charge >= session.requiredCharge) finishGame()
        else persistSession()
        changed()
    }

    fun divineStrike() {
        val count = store.int("divine", 2)
        if (count <= 0) return
        store.putInt("divine", count - 1)
        session.charge += ceil(session.requiredCharge * .30).toInt()
        session.lastHit = "DIVINE STRIKE!"
        session.lastHitAt = System.currentTimeMillis()
        emit(GameEvent.DIVINE)
        if (session.charge >= session.requiredCharge) finishGame()
        else persistSession()
        changed()
    }

    private fun finishGame() {
        val ratio = session.perfects.toFloat() / max(1, session.perfects + session.misses)
        val quality = when {
            ratio >= .72f && session.misses <= 1 -> "DIVINE"
            ratio >= .45f -> "AWAKENED"
            ratio >= .15f -> "CHARGED"
            else -> "RAW"
        }
        val multiplier = when (quality) { "DIVINE" -> 3; "AWAKENED" -> 2; else -> 1 }
        reward = ForgeReward(
            quality = quality,
            energy = (40 + selectedZone * 12 + selectedGem * 8) * multiplier,
            fragments = (2 + selectedZone / 2) * multiplier + if (session.maxPerfectStreak >= 5) 1 else 0,
            stones = max(1, multiplier - 1 + selectedZone / 3),
            relics = if (quality == "DIVINE") 1 else 0
        )
        store.add("energy", reward.energy)
        store.add("fragments", reward.fragments)
        store.add("stones", reward.stones)
        store.add("relics", reward.relics)
        store.add("processed", 1)
        store.add("gem_${selectedGem}_${quality.lowercase()}", 1)
        if (session.perfects >= 4 && store.int("divine") < maxDivine()) store.add("divine", 1)
        emit(GameEvent.REWARD)
        store.putBool("session_active", false)
        screen = GameScreen.RESULT
    }

    fun restoreHaven() {
        val level = store.int("haven_level")
        if (level >= 8 || !store.spend("stones", restoreCost())) return
        store.putInt("haven_level", level + 1)
        emit(GameEvent.RESTORE)
        changed()
    }

    fun upgradeForge() {
        val level = store.int("forge_level", 1)
        if (level >= 5 || !store.spend("energy", upgradeCost())) return
        store.putInt("forge_level", level + 1)
        if (level + 1 == 5) store.add("divine", 1)
        emit(GameEvent.RESTORE)
        changed()
    }

    fun completeTutorial() {
        store.putBool("tutorial_done", true)
        navigate(GameScreen.MENU)
    }

    fun abandonSession(destination: GameScreen) {
        store.putBool("session_active", false)
        navigate(destination)
    }

    fun normalWindow(): Float {
        val forgeBonus = if (store.int("forge_level", 1) >= 2) .018f else 0f
        return max(.09f, .17f - selectedZone * .009f) + forgeBonus
    }

    fun perfectWindow(): Float {
        val base = if (selectedGem == 3) .085f else max(.035f, .065f - selectedZone * .004f)
        val forgeBonus = if (store.int("forge_level", 1) >= 2) .008f else 0f
        return base + forgeBonus
    }
    fun cycleDuration(): Long = max(900L, 2100L - selectedZone * 145L)
    fun unlockedZones(): Int = min(8, max(1, store.int("haven_level") + 1))
    fun restoreCost(): Int = 2 + store.int("haven_level") * 2
    fun upgradeCost(): Int = store.int("forge_level", 1) * 180
    fun maxDivine(): Int = 2 + if (store.int("forge_level", 1) >= 5) 1 else 0

    fun qualityForProgress(): String = when {
        session.charge >= session.requiredCharge -> "DIVINE"
        session.charge >= session.requiredCharge * .66f -> "AWAKENED"
        session.charge >= session.requiredCharge * .30f -> "CHARGED"
        else -> "RAW"
    }

    private fun randomTarget(): Float = .27f + ((System.nanoTime() ushr 5) % 4600) / 10000f

    private fun persistSession() {
        store.putInt("session_zone", selectedZone)
        store.putInt("session_gem", selectedGem)
        store.putInt("session_charge", session.charge)
        store.putInt("session_required", session.requiredCharge)
        store.putInt("session_perfects", session.perfects)
        store.putInt("session_streak", session.perfectStreak)
        store.putInt("session_max_streak", session.maxPerfectStreak)
        store.putInt("session_misses", session.misses)
        store.putFloat("session_target", session.targetPoint)
        store.putLong("session_started", session.cycleStarted)
    }

    private fun restoreActiveSession() {
        selectedZone = store.int("session_zone").coerceIn(zones.indices)
        selectedGem = store.int("session_gem").coerceIn(gems.indices)
        session.apply {
            charge = store.int("session_charge")
            requiredCharge = max(1, store.int("session_required", 5))
            perfects = store.int("session_perfects")
            perfectStreak = store.int("session_streak")
            maxPerfectStreak = store.int("session_max_streak")
            misses = store.int("session_misses")
            targetPoint = store.float("session_target", .5f).coerceIn(.15f, .85f)
            cycleStarted = System.currentTimeMillis()
            lastHit = "FORGE RESTORED"
            lastHitAt = System.currentTimeMillis()
        }
    }
}
