package com.stormreachhaven.stormreachgame

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import com.stormreachhaven.stormreachgame.controller.GameController
import com.stormreachhaven.stormreachgame.model.GameEvent
import com.stormreachhaven.stormreachgame.model.GameScreen
import com.stormreachhaven.stormreachgame.model.GameStore
import com.stormreachhaven.stormreachgame.ui.BtnKind
import com.stormreachhaven.stormreachgame.ui.Icon
import com.stormreachhaven.stormreachgame.ui.Palette
import com.stormreachhaven.stormreachgame.ui.UiKit
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private typealias Screen = GameScreen

private class Target(val rect: RectF, val action: () -> Unit)

class StormreachView(context: Context) : View(context) {

    private val store = GameStore(context)
    private val ui = UiKit(resources)
    private val bitmaps = ConcurrentHashMap<String, Bitmap>()
    private val targets = ArrayList<Target>()

    @Volatile private var released = false
    @Volatile private var loading = 0f
    private val startedAt = System.nanoTime()
    private var pressX = 0f
    private var pressY = 0f
    private var pressing = false
    private var resetArmed = false
    private var insetTop = 0f
    private var insetBottom = 0f

    private var soundPool: SoundPool? = null
    private val sounds = HashMap<String, Int>()
    private var ambience: MediaPlayer? = null

    private val controller = GameController(store, { event ->
        play(eventSound(event))
        when (event) {
            GameEvent.PERFECT, GameEvent.DIVINE, GameEvent.RESTORE, GameEvent.REWARD -> haptic(true)
            GameEvent.STRIKE, GameEvent.ERROR -> haptic(false)
            GameEvent.CLICK -> Unit
        }
    }, { invalidate() })

    private var screen: Screen
        get() = controller.screen
        set(value) { controller.screen = value }
    private var selectedZone: Int
        get() = controller.selectedZone
        set(value) { controller.selectedZone = value }
    private var selectedGem: Int
        get() = controller.selectedGem
        set(value) { controller.selectedGem = value }
    private var tutorialPage: Int
        get() = controller.tutorialPage
        set(value) { controller.tutorialPage = value }

    private val zones get() = controller.zones
    private val gems get() = controller.gems
    private val session get() = controller.session
    private val reward get() = controller.reward

    private var soundEnabled = store.bool("sound")
    private var musicEnabled = store.bool("music")
    private var effectsEnabled = store.bool("effects")
    private var hapticsEnabled = store.bool("haptics")

    init {
        isFocusable = true
        keepScreenOn = true
        loadAssets()
    }

    // ------------------------------------------------------------------ assets

    private fun loadAssets() {
        thread(name = "Stormreach assets") {
            val names = listOf(
                "Vertical_Loading_Screen.webp", "Game_Name.webp",
                "Stormreach_Haven_Background_asset.webp", "Mount_Olympus_Background_asset.webp",
                "Temple_of_Zeus_Background_asset.webp", "Aether_Isles_Background_asset.webp",
                "Valley_of_Thunder_Background_asset.webp", "Crystal_Caverns_Background_asset.webp",
                "Palace_of_the_Gods_Background_asset.webp", "Celestial_Storm_Background_asset.webp",
                "Gemstones_Ruby_Sapphire_Emerald_Amethyst_asset.webp",
                "Celestial_Cloud_Clusters_Set_asset.webp",
                "Lightning_Bolt_Effects_Set_asset.webp",
                "Thunder_Forge_asset.webp", "Gemstone_Altar_asset.webp",
                "Zeus_Main_Character_asset.webp", "Stormreach_Haven_Core_asset.webp",
                "Divine_Lightning_Core_asset.webp", "Storm_Crystal_asset.webp",
                "Olympus_Energy_Orb_asset.webp", "Divine_Gem_Fragment_asset.webp",
                "Haven_Restoration_Stone_asset.webp", "Golden_Divine_Relic_asset.webp",
                "Zeus_Thunder_Symbol_asset.webp", "Ancient_Lightning_Seal_asset.webp"
            )
            names.forEachIndexed { index, name ->
                if (released) return@thread
                try {
                    context.assets.open(name).use { stream ->
                        BitmapFactory.decodeStream(stream)?.let { prepareBitmap(name, it) }
                    }
                } catch (_: Exception) { }
                loading = (index + 1f) / (names.size + 1f)
                postInvalidate()
            }
            if (released) return@thread
            setupAudio()
            loading = 1f
            postDelayed({
                if (released) return@postDelayed
                controller.finishLoading()
                resumeAudio()
            }, 420)
        }
    }

    private fun prepareBitmap(name: String, bitmap: Bitmap) {
        when (name) {
            "Gemstones_Ruby_Sapphire_Emerald_Amethyst_asset.webp" -> slice(bitmap, 2, 2, "gem_")
            "Celestial_Cloud_Clusters_Set_asset.webp" -> slice(bitmap, 2, 2, "cloud_")
            "Lightning_Bolt_Effects_Set_asset.webp" -> slice(bitmap, 4, 1, "bolt_")
            else -> {
                val keepFrame = name.contains("_Background_") || name.contains("Loading_Screen")
                bitmaps[name] = if (keepFrame) bitmap else trimTransparent(bitmap)
            }
        }
    }

    private fun slice(sheet: Bitmap, columns: Int, rows: Int, prefix: String) {
        val cellW = sheet.width / columns
        val cellH = sheet.height / rows
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                val cell = Bitmap.createBitmap(sheet, column * cellW, row * cellH, cellW, cellH)
                bitmaps["$prefix${row * columns + column}"] = trimTransparent(cell)
            }
        }
        sheet.recycle()
    }

    private fun trimTransparent(source: Bitmap): Bitmap {
        if (!source.hasAlpha()) return source
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        var left = source.width
        var top = source.height
        var right = -1
        var bottom = -1
        pixels.forEachIndexed { index, color ->
            if (Color.alpha(color) > 12) {
                val x = index % source.width
                val y = index / source.width
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
        if (right < left || bottom < top) return source
        val pad = 6
        left = max(0, left - pad)
        top = max(0, top - pad)
        right = min(source.width - 1, right + pad)
        bottom = min(source.height - 1, bottom + pad)
        if (left == 0 && top == 0 && right == source.width - 1 && bottom == source.height - 1) return source
        val cropped = Bitmap.createBitmap(source, left, top, right - left + 1, bottom - top + 1)
        source.recycle()
        return cropped
    }

    // ------------------------------------------------------------------- audio

    private fun setupAudio() {
        if (released) return
        try {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val pool = SoundPool.Builder().setMaxStreams(6).setAudioAttributes(attributes).build()
            soundPool = pool
            mapOf(
                "click" to "Button_Click_asset.mp3",
                "perfect" to "Perfect_Thunder_Timing_asset.mp3",
                "strike" to "Lightning_Strike_asset.mp3",
                "divine" to "Divine_Strike_asset.mp3",
                "error" to "Error_Failed_Timing_asset.mp3",
                "reward" to "Gemstone_Forging_Complete_asset.mp3",
                "restore" to "Haven_Restoration_asset.mp3"
            ).forEach { (key, file) ->
                context.assets.openFd(file).use { sounds[key] = pool.load(it, 1) }
            }
            context.assets.openFd("Thunderstorm_Ambience_asset.mp3").use { descriptor ->
                ambience = MediaPlayer().apply {
                    setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
                    isLooping = true
                    setVolume(.3f, .3f)
                    prepare()
                }
            }
        } catch (_: Exception) { }
    }

    private fun play(name: String) {
        if (soundEnabled) sounds[name]?.let { soundPool?.play(it, 1f, 1f, 1, 0, 1f) }
    }

    private fun haptic(strong: Boolean) {
        if (!hapticsEnabled) return
        performHapticFeedback(
            if (strong) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.VIRTUAL_KEY,
            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
        )
    }

    private fun eventSound(event: GameEvent): String = when (event) {
        GameEvent.CLICK -> "click"
        GameEvent.STRIKE -> "strike"
        GameEvent.PERFECT -> "perfect"
        GameEvent.DIVINE -> "divine"
        GameEvent.ERROR -> "error"
        GameEvent.REWARD -> "reward"
        GameEvent.RESTORE -> "restore"
    }

    fun pauseAudio() {
        try { if (ambience?.isPlaying == true) ambience?.pause() } catch (_: Exception) { }
    }

    fun resumeAudio() {
        if (screen != Screen.LOADING && musicEnabled) {
            try { if (ambience?.isPlaying == false) ambience?.start() } catch (_: Exception) { }
        }
    }

    fun release() {
        released = true
        soundPool?.release()
        ambience?.release()
        bitmaps.values.forEach { if (!it.isRecycled) it.recycle() }
        bitmaps.clear()
    }

    fun handleBack(): Boolean {
        if (screen == Screen.GAME) {
            controller.abandonSession(Screen.GEMS)
            return true
        }
        val destination = when (screen) {
            Screen.GEMS -> Screen.ZONES
            Screen.ZONES, Screen.RESULT, Screen.HAVEN, Screen.UPGRADES,
            Screen.COLLECTION, Screen.SETTINGS, Screen.TUTORIAL -> Screen.MENU
            Screen.LOADING, Screen.MENU, Screen.GAME -> return false
        }
        controller.navigate(destination)
        return true
    }

    // -------------------------------------------------------------------- draw

    /**
     * Bars are hidden, so the system reports zero gesture insets. The bottom swipe strip is
     * still active, so a minimum margin is always reserved to keep the nav bar tappable.
     */
    @Suppress("DEPRECATION")
    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        var bottom = ui.dp(20f)
        var top = 0f
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val gestures = insets.getInsets(WindowInsets.Type.mandatorySystemGestures())
            bottom = max(bottom, gestures.bottom.toFloat())
        } else if (android.os.Build.VERSION.SDK_INT >= 29) {
            bottom = max(bottom, insets.mandatorySystemGestureInsets.bottom.toFloat())
        }
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            insets.displayCutout?.let { top = min(height * .06f, it.safeInsetTop.toFloat()) }
        }
        insetTop = top
        insetBottom = min(height * .08f, bottom)
        return super.onApplyWindowInsets(insets)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Palette.INK)
        canvas.save()
        canvas.translate(0f, insetTop)
        ui.w = width.toFloat()
        ui.h = height - insetTop - insetBottom
        ui.time = (System.nanoTime() - startedAt) / 1_000_000_000f
        targets.clear()
        when (screen) {
            Screen.LOADING -> drawLoading(canvas)
            Screen.MENU -> drawMenu(canvas)
            Screen.ZONES -> drawZones(canvas)
            Screen.GEMS -> drawGems(canvas)
            Screen.GAME -> drawGame(canvas)
            Screen.RESULT -> drawResult(canvas)
            Screen.HAVEN -> drawHaven(canvas)
            Screen.UPGRADES -> drawUpgrades(canvas)
            Screen.COLLECTION -> drawCollection(canvas)
            Screen.SETTINGS -> drawSettings(canvas)
            Screen.TUTORIAL -> drawTutorial(canvas)
        }
        canvas.restore()
        val animated = screen == Screen.LOADING || screen == Screen.MENU ||
            screen == Screen.GAME || screen == Screen.RESULT || screen == Screen.GEMS
        if (animated) postInvalidateOnAnimation()
    }

    private fun drawLoading(c: Canvas) {
        ui.backdrop(c, bmp("Vertical_Loading_Screen.webp"), 118)
        val pulse = .5f + .5f * sin(ui.time * 1.9f)
        val logo = bmp("Game_Name.webp")
        if (logo != null) {
            ui.glow(c, ui.w / 2f, ui.h * .165f, ui.w * .46f, Palette.GOLD, (32 + 34 * pulse).toInt())
            ui.sprite(c, logo, ui.r(.12f, .065f, .88f, .265f))
        } else {
            ui.titleText(c, "STORMREACH HAVEN", ui.w / 2f, ui.h * .16f, ui.ts(30f))
        }

        val panel = ui.r(.07f, .795f, .93f, .95f)
        ui.panel(c, panel)
        val label = "Loading"
        val size = ui.ts(16f)
        val labelWidth = ui.measure(label, size, spacing = .1f)
        val dotRadius = ui.dp(4f)
        val dotGap = ui.dp(10f)
        val group = labelWidth + ui.dp(12f) + dotRadius * 6 + dotGap * 2
        val startX = ui.w / 2f - group / 2f
        val rowY = panel.top + panel.height() * .28f
        ui.text(c, label, startX, rowY, size, Palette.TEXT, spacing = .1f, middle = true)
        var dotX = startX + labelWidth + ui.dp(12f) + dotRadius
        repeat(3) { index ->
            val cursor = ((ui.time * 2.4f) % 3f) - index
            val active = cursor >= 0f && cursor < 1f
            val lift = if (active) sin(cursor * Math.PI.toFloat()) else 0f
            if (active) ui.glow(c, dotX, rowY, dotRadius * 3.4f, Palette.GOLD_LIGHT, (110 * lift).toInt())
            ui.circle(
                c, dotX, rowY - dotRadius * lift, dotRadius * (1f + .3f * lift),
                if (active) Palette.GOLD_LIGHT else Color.argb(110, 190, 205, 225)
            )
            dotX += dotRadius * 2 + dotGap
        }
        val bar = RectF(
            panel.left + ui.dp(20f), panel.top + panel.height() * .48f,
            panel.right - ui.dp(20f), panel.top + panel.height() * .61f
        )
        ui.text(
            c, "${(loading * 100).toInt()}%", bar.right, bar.top - ui.dp(11f),
            ui.ts(10f), Palette.GOLD, Paint.Align.RIGHT, spacing = .1f, middle = true
        )
        ui.progress(c, bar, loading, Palette.CYAN, Palette.GOLD)
        ui.multiline(
            c, TIPS[(ui.time / 3.4f).toInt() % TIPS.size], panel.centerX(),
            bar.bottom + ui.dp(20f), panel.width() - ui.dp(46f), ui.ts(10f), Palette.TEXT_DIM
        )
    }

    private fun drawMenu(c: Canvas) {
        ui.backdrop(c, bmp("Stormreach_Haven_Background_asset.webp"), 150)
        drawClouds(c)
        val pulse = .5f + .5f * sin(ui.time * 1.6f)
        val logo = bmp("Game_Name.webp")
        if (logo != null) {
            ui.glow(c, ui.w / 2f, ui.h * .15f, ui.w * .44f, Palette.GOLD, (30 + 30 * pulse).toInt())
            ui.sprite(c, logo, ui.r(.13f, .05f, .87f, .245f))
        } else {
            ui.titleText(c, "STORMREACH HAVEN", ui.w / 2f, ui.h * .15f, ui.ts(30f))
        }
        hud(c, .255f)

        val hero = ui.r(.045f, .33f, .955f, .565f)
        ui.panel(c, hero)
        bmp("Zeus_Main_Character_asset.webp")?.let {
            ui.sprite(c, it, RectF(hero.left + ui.dp(12f), hero.top + ui.dp(10f), hero.left + hero.width() * .45f, hero.bottom - ui.dp(10f)))
        }
        val right = RectF(hero.left + hero.width() * .47f, hero.top, hero.right - ui.dp(14f), hero.bottom)
        val havenLevel = store.int("haven_level")
        bmp("Stormreach_Haven_Core_asset.webp")?.let {
            ui.glow(c, right.centerX(), right.top + right.height() * .29f, right.width() * .42f, Palette.CYAN, (46 + 26 * pulse).toInt())
            ui.sprite(c, it, RectF(right.left, right.top + right.height() * .04f, right.right, right.top + right.height() * .54f))
        }
        ui.text(
            c, "CELESTIAL HAVEN", right.centerX(), right.top + right.height() * .63f,
            ui.ts(10f), Palette.CYAN, Paint.Align.CENTER, spacing = .16f, middle = true
        )
        ui.text(
            c, "$havenLevel / 8 SITES", right.centerX(), right.top + right.height() * .755f,
            ui.ts(14f), Palette.TEXT, Paint.Align.CENTER, spacing = .06f, middle = true
        )
        ui.progress(
            c, RectF(right.left, right.top + right.height() * .845f, right.right, right.top + right.height() * .9f),
            havenLevel / 8f, Palette.CYAN, Palette.GOLD, 8
        )

        button(c, .06f, .595f, .94f, .685f, "START FORGING", BtnKind.GOLD, icon = Icon.BOLT, size = ui.ts(18f)) {
            controller.navigate(Screen.ZONES)
        }
        button(c, .06f, .705f, .49f, .775f, "HAVEN", BtnKind.BLUE, icon = Icon.HOME, size = ui.ts(12f)) {
            controller.navigate(Screen.HAVEN)
        }
        button(c, .51f, .705f, .94f, .775f, "FORGE", BtnKind.BLUE, icon = Icon.FORGE, size = ui.ts(12f)) {
            controller.navigate(Screen.UPGRADES)
        }

        val strip = ui.r(.06f, .795f, .94f, .875f)
        ui.slate(c, strip, ui.dp(13f))
        val stats = listOf(
            "GEMS" to store.int("processed").toString(),
            "ZONES" to "${controller.unlockedZones()}/8",
            "DIVINE" to "${store.int("divine", 2)}/${controller.maxDivine()}"
        )
        stats.forEachIndexed { index, (caption, value) ->
            val cx = strip.left + strip.width() * (.1667f + index * .3333f)
            ui.text(c, value, cx, strip.top + strip.height() * .36f, ui.ts(16f), Palette.GOLD_LIGHT, Paint.Align.CENTER, spacing = .04f, middle = true)
            ui.text(c, caption, cx, strip.top + strip.height() * .71f, ui.ts(8.5f), Palette.TEXT_DIM, Paint.Align.CENTER, heavy = false, spacing = .18f, middle = true)
            if (index > 0) {
                val x = strip.left + strip.width() * index / 3f
                ui.roundRect(c, RectF(x, strip.top + strip.height() * .22f, x + ui.dp(1f), strip.bottom - strip.height() * .22f), 0f, Color.argb(90, 210, 170, 80))
            }
        }
        bottomNav(c, Screen.MENU)
    }

    private fun drawZones(c: Canvas) {
        ui.backdrop(c, bmp(zones[selectedZone].background), 128)
        ui.topBar(c, .095f, "SELECT ZONE")
        backButton(c, Screen.MENU)
        val unlocked = controller.unlockedZones()
        zones.forEachIndexed { index, zone ->
            val top = .125f + index * .0855f
            val box = ui.r(.05f, top, .95f, top + .0765f)
            val open = index < unlocked
            val selected = index == selectedZone
            if (selected) ui.glow(c, box.centerX(), box.centerY(), box.width() * .52f, Palette.GOLD, 55)
            ui.slate(c, box, ui.dp(13f), highlight = selected, dim = !open)
            if (selected) {
                ui.roundRect(
                    c, RectF(box.left + ui.dp(4f), box.top + ui.dp(9f), box.left + ui.dp(8f), box.bottom - ui.dp(9f)),
                    ui.dp(2f), Palette.GOLD_LIGHT
                )
            }
            val cy = box.centerY()
            val badgeX = box.left + box.width() * .105f
            val badgeR = box.height() * .3f
            if (open) {
                ui.badge(c, badgeX, cy, badgeR, difficultyColor(zone.difficulty), "${index + 1}")
            } else {
                ui.circle(c, badgeX, cy, badgeR, Color.argb(140, 18, 30, 50))
                ui.icon(c, RectF(badgeX - badgeR * .5f, cy - badgeR * .5f, badgeX + badgeR * .5f, cy + badgeR * .5f), Icon.LOCK, Palette.TEXT_DIM)
            }
            val textLeft = box.left + box.width() * .2f
            ui.text(
                c, zone.name, textLeft, cy - box.height() * .16f, ui.ts(12.5f),
                if (open) Palette.TEXT else Palette.TEXT_DIM, spacing = .08f, middle = true
            )
            ui.text(
                c, if (open) zone.subtitle else "Locked · restore the haven",
                textLeft, cy + box.height() * .2f, ui.ts(9.5f),
                if (open) Palette.CYAN else Color.argb(205, 158, 178, 205), heavy = false, spacing = .03f, middle = true
            )
            if (open) {
                ui.pips(c, box.right - box.width() * .12f, cy, 5, zone.difficulty, ui.dp(3.4f), ui.dp(4.5f), difficultyColor(zone.difficulty))
                tap(box) { selectedZone = index; play("click"); invalidate() }
            }
        }
        button(c, .12f, .84f, .88f, .92f, "CHOOSE GEM", BtnKind.GOLD, icon = Icon.GEM, size = ui.ts(16f)) {
            controller.navigate(Screen.GEMS)
        }
    }

    private fun drawGems(c: Canvas) {
        ui.backdrop(c, bmp(zones[selectedZone].background), 138)
        ui.topBar(c, .095f, "SELECT GEM")
        backButton(c, Screen.ZONES)
        val zone = zones[selectedZone]
        val chip = ui.r(.05f, .112f, .95f, .162f)
        ui.slate(c, chip, ui.dp(11f))
        ui.text(c, "ZONE", chip.left + ui.dp(14f), chip.centerY(), ui.ts(9f), Palette.TEXT_DIM, spacing = .18f, middle = true)
        ui.text(c, zone.name, chip.left + ui.dp(14f) + ui.measure("ZONE", ui.ts(9f), spacing = .18f) + ui.dp(12f), chip.centerY(), ui.ts(11f), Palette.TEXT, spacing = .06f, middle = true)
        ui.pips(c, chip.right - chip.width() * .11f, chip.centerY(), 5, zone.difficulty, ui.dp(3.2f), ui.dp(4.2f), difficultyColor(zone.difficulty))

        val forgeLevel = store.int("forge_level", 1)
        val pulse = .5f + .5f * sin(ui.time * 2.2f)
        gems.forEachIndexed { index, gem ->
            val top = .185f + index * .135f
            val box = ui.r(.05f, top, .95f, top + .12f)
            val open = forgeLevel >= gem.unlockLevel
            val selected = index == selectedGem && open
            if (selected) ui.glow(c, box.centerX(), box.centerY(), box.width() * .5f, gem.color, 60)
            ui.slate(c, box, ui.dp(14f), highlight = selected, dim = !open)
            val socketR = box.height() * .36f
            val socketX = box.left + box.width() * .145f
            ui.socket(c, socketX, box.centerY(), socketR, if (open) gem.color else Color.GRAY)
            if (open && selected) ui.glow(c, socketX, box.centerY(), socketR * (1.05f + .12f * pulse), gem.color, (70 + 50 * pulse).toInt())
            drawGem(c, index, RectF(socketX - socketR * .78f, box.centerY() - socketR * .78f, socketX + socketR * .78f, box.centerY() + socketR * .78f), open)
            val textLeft = box.left + box.width() * .29f
            ui.text(
                c, gem.name, textLeft, box.top + box.height() * .3f, ui.ts(17f),
                if (open) ui.lighten(gem.color, .35f) else Palette.TEXT_DIM, spacing = .1f, middle = true
            )
            if (open) {
                ui.multilineLeft(c, gem.trait, textLeft, box.top + box.height() * .58f, box.width() * .62f, ui.ts(9.5f), Palette.TEXT)
                ui.text(c, if (index == selectedGem) "SELECTED" else "READY", textLeft, box.bottom - box.height() * .16f, ui.ts(8.5f), if (index == selectedGem) Palette.GOLD_LIGHT else Palette.TEXT_DIM, spacing = .2f, middle = true)
                tap(box) { selectedGem = index; play("click"); invalidate() }
            } else {
                ui.icon(c, RectF(box.right - box.width() * .13f, box.centerY() - box.height() * .13f, box.right - box.width() * .06f, box.centerY() + box.height() * .13f), Icon.LOCK, Palette.TEXT_DIM)
                ui.text(c, "Forge level ${gem.unlockLevel}", textLeft, box.top + box.height() * .62f, ui.ts(9.5f), Color.argb(210, 160, 180, 205), heavy = false, spacing = .03f, middle = true)
            }
        }
        button(c, .1f, .765f, .9f, .85f, "START PROCESSING", BtnKind.GREEN, icon = Icon.BOLT, size = ui.ts(16f)) {
            controller.startGame()
        }
        ui.text(
            c, "Gem charge: ${5 + selectedZone / 2 + gems[selectedGem].extraCycles} strikes",
            ui.w / 2f, ui.h * .885f, ui.ts(10f), Palette.TEXT_DIM, Paint.Align.CENTER, heavy = false, spacing = .06f, middle = true
        )
    }

    private fun drawGame(c: Canvas) {
        ui.backdrop(c, bmp(zones[selectedZone].background), 118)
        val now = System.currentTimeMillis()
        val cycle = controller.cycleDuration()
        val phase = ((now - session.cycleStarted) % cycle).toFloat() / cycle
        ui.topBar(c, .088f, gems[selectedGem].name)
        ui.text(
            c, zones[selectedZone].name, ui.w / 2f, ui.h * .073f, ui.ts(8.5f),
            Palette.CYAN, Paint.Align.CENTER, heavy = false, spacing = .2f, middle = true
        )

        val panel = ui.r(.05f, .1f, .95f, .202f)
        ui.panel(c, panel, ui.dp(14f), ornaments = false)
        val padding = ui.dp(16f)
        ui.text(c, "GEM CHARGE", panel.left + padding, panel.top + panel.height() * .24f, ui.ts(9.5f), Palette.TEXT_DIM, spacing = .18f, middle = true)
        ui.text(
            c, controller.qualityForProgress(), panel.right - padding, panel.top + panel.height() * .24f,
            ui.ts(11.5f), qualityColor(controller.qualityForProgress()), Paint.Align.RIGHT, spacing = .14f, middle = true
        )
        ui.progress(
            c, RectF(panel.left + padding, panel.top + panel.height() * .44f, panel.right - padding, panel.top + panel.height() * .62f),
            session.charge.toFloat() / session.requiredCharge, Palette.CYAN, Palette.GOLD, session.requiredCharge
        )
        ui.text(c, "${session.charge} / ${session.requiredCharge}", panel.left + padding, panel.top + panel.height() * .82f, ui.ts(11f), Palette.TEXT, spacing = .04f, middle = true)
        ui.text(c, "PERFECT", panel.right - padding - ui.dp(46f), panel.top + panel.height() * .82f, ui.ts(8.5f), Palette.TEXT_DIM, Paint.Align.RIGHT, spacing = .16f, middle = true)
        ui.pips(c, panel.right - padding - ui.dp(20f), panel.top + panel.height() * .82f, 3, session.perfectStreak % 3, ui.dp(3.4f), ui.dp(5f), Palette.GOLD)

        val stage = ui.r(.03f, .215f, .97f, .625f)
        bmp("Thunder_Forge_asset.webp")?.let {
            ui.sprite(c, it, RectF(stage.left, stage.top + stage.height() * .16f, stage.right, stage.bottom))
        }
        val gemCx = ui.w / 2f
        val gemCy = stage.top + stage.height() * .47f
        val pulse = .5f + .5f * sin(ui.time * 2.4f)
        if (effectsEnabled) {
            ui.glow(c, gemCx, gemCy, ui.w * (.17f + .03f * pulse), Palette.CYAN, (62 + 40 * pulse).toInt())
        }
        val bob = sin(ui.time * 1.8f) * ui.h * .005f
        val gemSize = ui.w * .135f
        drawGem(c, selectedGem, RectF(gemCx - gemSize, gemCy - gemSize + bob, gemCx + gemSize, gemCy + gemSize + bob), true)
        val sinceHit = (now - session.lastHitAt).toFloat()
        if (effectsEnabled && sinceHit < 260) {
            bmp("bolt_${session.perfects % 4}")?.let {
                ui.sprite(c, it, RectF(ui.w * .16f, stage.top - stage.height() * .05f, ui.w * .84f, gemCy + gemSize * .4f), (255 * (1f - sinceHit / 260f)).toInt())
            }
        }
        if (sinceHit < 900 && session.lastHit.isNotEmpty()) {
            val pop = 1f + .32f * (1f - min(1f, sinceHit / 170f))
            val fade = 1f - max(0f, (sinceHit / 900f - .6f) / .4f)
            val base = verdictColor(session.lastHit)
            val size = ui.ts(21f) * pop
            val cy = ui.h * .6f
            val halfWidth = ui.measure(session.lastHit, size, spacing = .12f) / 2f + ui.dp(22f)
            ui.glow(c, ui.w / 2f, cy, halfWidth * 1.5f, base, (85 * fade).toInt())
            ui.roundRect(
                c, RectF(ui.w / 2f - halfWidth, cy - size * .85f, ui.w / 2f + halfWidth, cy + size * .85f),
                size * .85f, Color.argb((205 * fade).toInt(), 3, 9, 20)
            )
            ui.text(
                c, session.lastHit, ui.w / 2f, cy, size,
                Color.argb((255 * fade).toInt(), Color.red(base), Color.green(base), Color.blue(base)),
                Paint.Align.CENTER, spacing = .12f, middle = true
            )
        }

        ui.timingTrack(
            c, ui.r(.07f, .655f, .93f, .705f),
            session.targetPoint, controller.normalWindow(), controller.perfectWindow(), phase
        )
        ui.text(
            c, "STRIKE WHEN THE MARKER IS IN GOLD", ui.w / 2f, ui.h * .729f,
            ui.ts(8.5f), Palette.TEXT_DIM, Paint.Align.CENTER, heavy = false, spacing = .14f, middle = true
        )
        button(c, .07f, .748f, .93f, .848f, "LIGHTNING STRIKE", BtnKind.GOLD, icon = Icon.BOLT, size = ui.ts(19f)) {
            controller.timingHit(phase)
        }
        val divine = store.int("divine", 2)
        val divineBox = ui.r(.18f, .866f, .82f, .938f)
        ui.button(c, divineBox, "DIVINE STRIKE", BtnKind.BLUE, divine > 0, down(divineBox), Icon.SPARK, ui.ts(13f))
        ui.pips(c, divineBox.right - ui.dp(26f), divineBox.centerY(), controller.maxDivine(), divine, ui.dp(3.2f), ui.dp(4.2f), Palette.GOLD_LIGHT)
        if (divine > 0) tap(divineBox) { controller.divineStrike() }
        iconButton(c, .862f, .017f, .962f, .071f, Icon.PAUSE) { controller.abandonSession(Screen.MENU) }
    }

    private fun drawResult(c: Canvas) {
        ui.backdrop(c, bmp(zones[selectedZone].background), 165)
        val tint = qualityColor(reward.quality)
        if (effectsEnabled) ui.rays(c, ui.w / 2f, ui.h * .34f, ui.w * .85f, tint, 14, ui.time * 10f)
        ui.text(c, "GEM FORGED", ui.w / 2f, ui.h * .085f, ui.ts(13f), Palette.TEXT, Paint.Align.CENTER, spacing = .22f, middle = true)
        ui.ribbon(c, ui.r(.2f, .115f, .8f, .175f), reward.quality, tint)
        val pulse = .5f + .5f * sin(ui.time * 2f)
        ui.glow(c, ui.w / 2f, ui.h * .32f, ui.w * (.3f + .03f * pulse), tint, (75 + 35 * pulse).toInt())
        drawGem(c, selectedGem, ui.r(.22f, .2f, .78f, .445f), true)
        ui.text(c, gems[selectedGem].name, ui.w / 2f, ui.h * .475f, ui.ts(16f), ui.lighten(gems[selectedGem].color, .4f), Paint.Align.CENTER, spacing = .14f, middle = true)

        val panel = ui.r(.06f, .505f, .94f, .655f)
        ui.panel(c, panel)
        ui.text(c, "REWARD", panel.centerX(), panel.top + panel.height() * .19f, ui.ts(10f), Palette.GOLD_LIGHT, Paint.Align.CENTER, spacing = .26f, middle = true)
        ui.divider(c, panel.centerX(), panel.top + panel.height() * .33f, panel.width() * .36f)
        val rewards = listOf(
            Triple("Olympus_Energy_Orb_asset.webp", reward.energy, Palette.CYAN),
            Triple("Divine_Gem_Fragment_asset.webp", reward.fragments, Palette.VIOLET),
            Triple("Haven_Restoration_Stone_asset.webp", reward.stones, Palette.TEXT),
            Triple("Golden_Divine_Relic_asset.webp", reward.relics, Palette.GOLD)
        )
        rewards.forEachIndexed { index, (name, value, color) ->
            val cx = panel.left + panel.width() * (.125f + index * .25f)
            val iconCy = panel.top + panel.height() * .58f
            val iconHalf = panel.height() * .17f
            bmp(name)?.let { ui.sprite(c, it, RectF(cx - iconHalf, iconCy - iconHalf, cx + iconHalf, iconCy + iconHalf)) }
            ui.text(c, "+$value", cx, panel.bottom - panel.height() * .15f, ui.ts(13f), color, Paint.Align.CENTER, spacing = .04f, middle = true)
        }
        ui.text(
            c, "Perfect: ${session.perfects}   ·   Best streak: ${session.maxPerfectStreak}",
            ui.w / 2f, ui.h * .687f, ui.ts(11f), Palette.TEXT_DIM, Paint.Align.CENTER, heavy = false, spacing = .05f, middle = true
        )
        button(c, .06f, .715f, .485f, .795f, "AGAIN", BtnKind.BLUE, icon = Icon.REPEAT, size = ui.ts(13f)) {
            controller.startGame()
        }
        button(c, .515f, .715f, .94f, .795f, "HAVEN", BtnKind.GREEN, icon = Icon.HOME, size = ui.ts(13f)) {
            controller.navigate(Screen.HAVEN)
        }
        button(c, .2f, .82f, .8f, .885f, "MAIN MENU", BtnKind.DARK, size = ui.ts(12f)) {
            controller.navigate(Screen.MENU)
        }
    }

    private fun drawHaven(c: Canvas) {
        ui.backdrop(c, bmp("Stormreach_Haven_Background_asset.webp"), 122)
        ui.topBar(c, .095f, "STORMREACH HAVEN")
        backButton(c, Screen.MENU)
        hud(c, .105f)
        val restored = store.int("haven_level")
        val strip = ui.r(.05f, .175f, .95f, .245f)
        ui.slate(c, strip, ui.dp(12f))
        ui.text(c, "RESTORATION", strip.left + ui.dp(14f), strip.top + strip.height() * .32f, ui.ts(9.5f), Palette.TEXT_DIM, spacing = .18f, middle = true)
        ui.text(c, "$restored / 8", strip.right - ui.dp(14f), strip.top + strip.height() * .32f, ui.ts(12f), Palette.GOLD_LIGHT, Paint.Align.RIGHT, spacing = .08f, middle = true)
        ui.progress(
            c, RectF(strip.left + ui.dp(14f), strip.top + strip.height() * .58f, strip.right - ui.dp(14f), strip.top + strip.height() * .76f),
            restored / 8f, Palette.CYAN, Palette.GOLD, 8
        )
        HAVEN_PARTS.forEachIndexed { index, name ->
            val top = .258f + index * .063f
            val box = ui.r(.05f, top, .95f, top + .055f)
            val done = index < restored
            val next = index == restored
            ui.slate(c, box, ui.dp(11f), highlight = next, dim = !done && !next)
            val badgeX = box.left + box.width() * .075f
            val badgeR = box.height() * .32f
            if (done) ui.badge(c, badgeX, box.centerY(), badgeR, Palette.GREEN, glyph = Icon.CHECK)
            else if (next) ui.badge(c, badgeX, box.centerY(), badgeR, Palette.GOLD, "${index + 1}")
            else {
                ui.circle(c, badgeX, box.centerY(), badgeR, Color.argb(130, 18, 30, 50))
                ui.icon(c, RectF(badgeX - badgeR * .5f, box.centerY() - badgeR * .5f, badgeX + badgeR * .5f, box.centerY() + badgeR * .5f), Icon.LOCK, Palette.TEXT_DIM)
            }
            ui.text(
                c, name, box.left + box.width() * .15f, box.centerY(), ui.ts(11f),
                if (done || next) Palette.TEXT else Palette.TEXT_DIM, spacing = .05f, middle = true
            )
            if (next) {
                val cost = controller.restoreCost()
                val enough = store.int("stones") >= cost
                ui.text(
                    c, "$cost stones", box.right - ui.dp(14f), box.centerY(), ui.ts(9.5f),
                    if (enough) Palette.CYAN else Palette.RED, Paint.Align.RIGHT, spacing = .06f, middle = true
                )
            } else if (done) {
                ui.text(c, "DONE", box.right - ui.dp(14f), box.centerY(), ui.ts(8.5f), Palette.GREEN, Paint.Align.RIGHT, spacing = .2f, middle = true)
            }
        }
        val cost = controller.restoreCost()
        val canRestore = restored < 8 && store.int("stones") >= cost
        button(
            c, .1f, .775f, .9f, .855f,
            if (restored >= 8) "HAVEN RESTORED" else "RESTORE FOR $cost",
            BtnKind.GOLD, enabled = canRestore, icon = if (restored >= 8) null else Icon.HOME, size = ui.ts(14f)
        ) { controller.restoreHaven() }
        bottomNav(c, Screen.HAVEN)
    }

    private fun drawUpgrades(c: Canvas) {
        ui.backdrop(c, bmp("Temple_of_Zeus_Background_asset.webp"), 138)
        ui.topBar(c, .095f, "THUNDER FORGE")
        backButton(c, Screen.MENU)
        hud(c, .105f)
        val level = store.int("forge_level", 1)
        val strip = ui.r(.05f, .175f, .95f, .255f)
        ui.slate(c, strip, ui.dp(12f))
        bmp("Zeus_Thunder_Symbol_asset.webp")?.let {
            ui.sprite(c, it, RectF(strip.left + ui.dp(12f), strip.top + ui.dp(9f), strip.left + strip.height() * .82f, strip.bottom - ui.dp(9f)))
        }
        ui.text(c, "FORGE LEVEL", strip.left + strip.width() * .2f, strip.top + strip.height() * .34f, ui.ts(10f), Palette.TEXT_DIM, spacing = .16f, middle = true)
        ui.text(c, "$level / 5", strip.left + strip.width() * .2f, strip.top + strip.height() * .7f, ui.ts(15f), Palette.GOLD_LIGHT, spacing = .06f, middle = true)
        ui.pips(c, strip.right - strip.width() * .18f, strip.centerY(), 5, level, ui.dp(5f), ui.dp(7f), Palette.GOLD)
        UPGRADES.forEachIndexed { index, upgrade ->
            val top = .272f + index * .088f
            val box = ui.r(.05f, top, .95f, top + .078f)
            val active = index < level
            val next = index == level
            ui.slate(c, box, ui.dp(12f), highlight = next, dim = !active && !next)
            val badgeX = box.left + box.width() * .085f
            val badgeR = box.height() * .29f
            if (active) ui.badge(c, badgeX, box.centerY(), badgeR, Palette.GREEN, glyph = Icon.CHECK)
            else if (next) ui.badge(c, badgeX, box.centerY(), badgeR, Palette.GOLD, glyph = Icon.FORGE)
            else {
                ui.circle(c, badgeX, box.centerY(), badgeR, Color.argb(130, 18, 30, 50))
                ui.icon(c, RectF(badgeX - badgeR * .5f, box.centerY() - badgeR * .5f, badgeX + badgeR * .5f, box.centerY() + badgeR * .5f), Icon.LOCK, Palette.TEXT_DIM)
            }
            val textLeft = box.left + box.width() * .17f
            ui.text(c, upgrade.first, textLeft, box.top + box.height() * .33f, ui.ts(11.5f), if (active || next) Palette.TEXT else Palette.TEXT_DIM, spacing = .05f, middle = true)
            ui.text(c, upgrade.second, textLeft, box.top + box.height() * .68f, ui.ts(9f), Color.argb(190, 160, 185, 215), heavy = false, spacing = .03f, middle = true)
            val status = if (active) "ACTIVE" else if (next) "${controller.upgradeCost()} ⚡" else "LOCKED"
            ui.text(
                c, status, box.right - ui.dp(14f), box.centerY(), ui.ts(9f),
                if (active) Palette.GREEN else if (next) Palette.CYAN else Palette.TEXT_DIM,
                Paint.Align.RIGHT, spacing = .12f, middle = true
            )
        }
        val cost = controller.upgradeCost()
        button(
            c, .1f, .745f, .9f, .825f,
            if (level >= 5) "MAXIMUM LEVEL" else "UPGRADE FOR $cost",
            BtnKind.GOLD, enabled = level < 5 && store.int("energy") >= cost,
            icon = if (level >= 5) null else Icon.FORGE, size = ui.ts(14f)
        ) { controller.upgradeForge() }
        bottomNav(c, Screen.UPGRADES)
    }

    private fun drawCollection(c: Canvas) {
        ui.backdrop(c, bmp("Crystal_Caverns_Background_asset.webp"), 142)
        ui.topBar(c, .095f, "COLLECTION")
        backButton(c, Screen.MENU)
        val strip = ui.r(.05f, .112f, .95f, .172f)
        ui.slate(c, strip, ui.dp(12f))
        val totals = gems.indices.sumOf { index -> QUALITIES.sumOf { store.int("gem_${index}_${it.second}") } }
        val divineTotal = gems.indices.sumOf { store.int("gem_${it}_divine") }
        ui.text(c, "TOTAL GEMS", strip.left + ui.dp(16f), strip.centerY(), ui.ts(9.5f), Palette.TEXT_DIM, spacing = .16f, middle = true)
        ui.text(c, "$totals", strip.left + strip.width() * .42f, strip.centerY(), ui.ts(14f), Palette.TEXT, spacing = .04f, middle = true)
        ui.text(c, "DIVINE", strip.left + strip.width() * .6f, strip.centerY(), ui.ts(9.5f), Palette.TEXT_DIM, spacing = .16f, middle = true)
        ui.text(c, "$divineTotal", strip.right - ui.dp(16f), strip.centerY(), ui.ts(14f), Palette.GOLD_LIGHT, Paint.Align.RIGHT, spacing = .04f, middle = true)
        gems.forEachIndexed { index, gem ->
            val column = index % 2
            val row = index / 2
            val box = ui.r(.05f + column * .475f, .195f + row * .285f, .475f + column * .475f, .455f + row * .285f)
            ui.panel(c, box, ui.dp(14f), ornaments = false)
            val socketR = box.width() * .27f
            ui.socket(c, box.centerX(), box.top + box.height() * .27f, socketR, gem.color)
            drawGem(
                c, index,
                RectF(box.centerX() - socketR * .8f, box.top + box.height() * .27f - socketR * .8f, box.centerX() + socketR * .8f, box.top + box.height() * .27f + socketR * .8f),
                true
            )
            ui.text(c, gem.name, box.centerX(), box.top + box.height() * .52f, ui.ts(13f), ui.lighten(gem.color, .35f), Paint.Align.CENTER, spacing = .12f, middle = true)
            ui.divider(c, box.centerX(), box.top + box.height() * .59f, box.width() * .32f)
            QUALITIES.forEachIndexed { qIndex, quality ->
                val qColumn = qIndex % 2
                val qRow = qIndex / 2
                val cx = box.left + box.width() * (.28f + qColumn * .44f)
                val cy = box.top + box.height() * (.68f + qRow * .13f)
                ui.text(c, quality.first, cx - ui.dp(6f), cy, ui.ts(8f), Palette.TEXT_DIM, Paint.Align.RIGHT, heavy = false, spacing = .08f, middle = true)
                ui.text(c, "${store.int("gem_${index}_${quality.second}")}", cx + ui.dp(6f), cy, ui.ts(9.5f), qualityColor(quality.second.uppercase()), spacing = .04f, middle = true)
            }
        }
        val relic = ui.r(.05f, .775f, .95f, .855f)
        ui.slate(c, relic, ui.dp(12f))
        bmp("Golden_Divine_Relic_asset.webp")?.let {
            ui.sprite(c, it, RectF(relic.left + ui.dp(14f), relic.top + ui.dp(10f), relic.left + relic.height() * .8f, relic.bottom - ui.dp(10f)))
        }
        ui.text(c, "DIVINE RELICS", relic.left + relic.width() * .2f, relic.centerY(), ui.ts(10.5f), Palette.TEXT, spacing = .12f, middle = true)
        ui.text(c, "${store.int("relics")}", relic.right - ui.dp(16f), relic.centerY(), ui.ts(17f), Palette.GOLD_LIGHT, Paint.Align.RIGHT, spacing = .04f, middle = true)
        bottomNav(c, Screen.COLLECTION)
    }

    private fun drawSettings(c: Canvas) {
        ui.backdrop(c, bmp("Aether_Isles_Background_asset.webp"), 148)
        ui.topBar(c, .095f, "SETTINGS")
        backButton(c, Screen.MENU)
        toggleRow(c, "MUSIC", "Storm ambience", musicEnabled, .115f) {
            musicEnabled = !musicEnabled
            store.putBool("music", musicEnabled)
            if (musicEnabled) resumeAudio() else pauseAudio()
        }
        toggleRow(c, "SOUND", "Hits and interface", soundEnabled, .195f) {
            soundEnabled = !soundEnabled
            store.putBool("sound", soundEnabled)
        }
        toggleRow(c, "EFFECTS", "Lightning, glow, clouds", effectsEnabled, .275f) {
            effectsEnabled = !effectsEnabled
            store.putBool("effects", effectsEnabled)
        }
        toggleRow(c, "HAPTICS", "Feedback on strikes", hapticsEnabled, .355f) {
            hapticsEnabled = !hapticsEnabled
            store.putBool("haptics", hapticsEnabled)
        }
        val language = ui.r(.05f, .44f, .95f, .505f)
        ui.slate(c, language, ui.dp(12f))
        ui.text(c, "LANGUAGE", language.left + ui.dp(18f), language.centerY(), ui.ts(11.5f), Palette.TEXT, spacing = .16f, middle = true)
        ui.text(c, "ENGLISH", language.right - ui.dp(18f), language.centerY(), ui.ts(11.5f), Palette.GOLD_LIGHT, Paint.Align.RIGHT, spacing = .1f, middle = true)
        button(c, .05f, .525f, .95f, .59f, "HOW TO PLAY", BtnKind.BLUE, icon = Icon.GEM, size = ui.ts(12f)) {
            tutorialPage = 0
            controller.navigate(Screen.TUTORIAL)
        }
        button(c, .05f, .605f, .48f, .67f, "PRIVACY", BtnKind.DARK, size = ui.ts(11f)) {
            openLegal("PRIVACY POLICY", LegalActivity.PRIVACY_URL, "privacy-policy.html")
        }
        button(c, .52f, .605f, .95f, .67f, "SUPPORT", BtnKind.DARK, size = ui.ts(11f)) {
            openLegal("SUPPORT", LegalActivity.SUPPORT_URL, "support.html")
        }
        button(
            c, .05f, .685f, .95f, .75f,
            if (resetArmed) "TAP AGAIN TO CONFIRM" else "RESET PROGRESS",
            if (resetArmed) BtnKind.GOLD else BtnKind.DARK, size = ui.ts(12f)
        ) {
            play("click")
            if (resetArmed) {
                store.clear()
                musicEnabled = true
                soundEnabled = true
                effectsEnabled = true
                hapticsEnabled = true
                resetArmed = false
                controller.navigate(Screen.MENU)
            } else resetArmed = true
            invalidate()
        }
        ui.text(c, "STORMREACH HAVEN", ui.w / 2f, ui.h * .795f, ui.ts(11f), Palette.GOLD, Paint.Align.CENTER, spacing = .3f, middle = true)
        ui.text(c, "Offline · no ads · version 1.0.0", ui.w / 2f, ui.h * .83f, ui.ts(9.5f), Palette.TEXT_DIM, Paint.Align.CENTER, heavy = false, spacing = .06f, middle = true)
        bottomNav(c, Screen.SETTINGS)
    }

    private fun drawTutorial(c: Canvas) {
        ui.backdrop(c, bmp(TUTORIAL_BG[tutorialPage]), 142)
        ui.topBar(c, .095f, "HOW TO PLAY")
        if (tutorialPage > 0) iconButton(c, .03f, .022f, .135f, .078f, Icon.CHEVRON_LEFT) {
            play("click")
            tutorialPage--
            invalidate()
        }
        val frame = ui.r(.08f, .13f, .92f, .53f)
        ui.panel(c, frame)
        bmp(TUTORIAL_ART[tutorialPage])?.let {
            ui.sprite(c, it, RectF(frame.left + ui.dp(18f), frame.top + ui.dp(18f), frame.right - ui.dp(18f), frame.bottom - ui.dp(18f)))
        }
        ui.text(c, TUTORIAL_TITLES[tutorialPage], ui.w / 2f, ui.h * .585f, ui.ts(18f), Palette.GOLD_LIGHT, Paint.Align.CENTER, spacing = .16f, middle = true)
        ui.divider(c, ui.w / 2f, ui.h * .615f, ui.w * .22f)
        ui.multiline(c, TUTORIAL_TEXTS[tutorialPage], ui.w / 2f, ui.h * .648f, ui.w * .78f, ui.ts(11.5f), Palette.TEXT)
        ui.pips(c, ui.w / 2f, ui.h * .775f, 4, tutorialPage + 1, ui.dp(4.5f), ui.dp(9f), Palette.GOLD)
        button(
            c, .12f, .805f, .88f, .885f,
            if (tutorialPage == 3) "START GAME" else "NEXT",
            BtnKind.GOLD, icon = if (tutorialPage == 3) Icon.BOLT else null, size = ui.ts(16f)
        ) {
            play("click")
            if (tutorialPage < 3) {
                tutorialPage++
                invalidate()
            } else controller.completeTutorial()
        }
        if (tutorialPage < 3) {
            val skip = ui.r(.3f, .9f, .7f, .95f)
            ui.text(c, "SKIP", skip.centerX(), skip.centerY(), ui.ts(10f), Palette.TEXT_DIM, Paint.Align.CENTER, spacing = .2f, middle = true)
            tap(skip) { controller.completeTutorial() }
        }
    }

    // ---------------------------------------------------------- shared widgets

    private fun hud(c: Canvas, top: Float) {
        val box = ui.r(.045f, top, .955f, top + .062f)
        ui.panel(c, box, ui.dp(13f), ornaments = false)
        val items = listOf(
            Triple("Olympus_Energy_Orb_asset.webp", store.int("energy"), Palette.CYAN),
            Triple("Divine_Gem_Fragment_asset.webp", store.int("fragments"), Palette.VIOLET),
            Triple("Haven_Restoration_Stone_asset.webp", store.int("stones"), Palette.TEXT),
            Triple("Golden_Divine_Relic_asset.webp", store.int("relics"), Palette.GOLD)
        )
        val cellWidth = box.width() / 4f
        items.forEachIndexed { index, (name, value, color) ->
            val left = box.left + cellWidth * index
            val iconSize = box.height() * .66f
            val iconLeft = left + cellWidth * .13f
            val iconBox = RectF(iconLeft, box.centerY() - iconSize / 2f, iconLeft + iconSize, box.centerY() + iconSize / 2f)
            val sprite = bmp(name)
            if (sprite != null) ui.sprite(c, sprite, iconBox) else ui.icon(c, iconBox, Icon.SPARK, color)
            ui.text(c, short(value), iconBox.right + ui.dp(7f), box.centerY(), ui.ts(12f), color, spacing = .04f, middle = true)
            if (index > 0) {
                ui.roundRect(
                    c, RectF(left, box.top + box.height() * .24f, left + ui.dp(1f), box.bottom - box.height() * .24f),
                    0f, Color.argb(80, 210, 170, 80)
                )
            }
        }
    }

    private fun bottomNav(c: Canvas, active: Screen) {
        val box = RectF(0f, ui.h * .905f, ui.w, ui.h)
        ui.roundRect(c, box, 0f, Color.argb(238, 4, 14, 30))
        ui.roundRect(c, RectF(0f, box.top, ui.w, box.top + ui.dp(2f)), 0f, Palette.GOLD)
        val items = listOf(
            Triple(Icon.HOME, Screen.MENU, "HOME"),
            Triple(Icon.FORGE, Screen.UPGRADES, "FORGE"),
            Triple(Icon.GEM, Screen.COLLECTION, "GEMS"),
            Triple(Icon.GEAR, Screen.SETTINGS, "SETTINGS")
        )
        items.forEachIndexed { index, (glyph, destination, label) ->
            val cell = RectF(ui.w * index / 4f, box.top, ui.w * (index + 1) / 4f, ui.h)
            val selected = destination == active
            if (selected) {
                ui.roundRect(
                    c, RectF(cell.left + cell.width() * .18f, cell.top + ui.dp(2f), cell.right - cell.width() * .18f, cell.top + ui.dp(5f)),
                    ui.dp(2f), Palette.GOLD_LIGHT
                )
                ui.glow(c, cell.centerX(), cell.top + cell.height() * .42f, cell.width() * .38f, Palette.GOLD, 60)
            }
            val iconSize = cell.height() * .34f
            ui.icon(
                c,
                RectF(cell.centerX() - iconSize / 2f, cell.top + cell.height() * .24f, cell.centerX() + iconSize / 2f, cell.top + cell.height() * .24f + iconSize),
                glyph, if (selected) Palette.GOLD_LIGHT else Color.argb(210, 150, 172, 200)
            )
            ui.text(
                c, label, cell.centerX(), cell.top + cell.height() * .78f, ui.ts(7.5f),
                if (selected) Palette.GOLD_LIGHT else Palette.TEXT_DIM,
                Paint.Align.CENTER, heavy = false, spacing = .12f, middle = true
            )
            tap(cell) { controller.navigate(destination) }
        }
    }

    private fun toggleRow(c: Canvas, title: String, hint: String, value: Boolean, top: Float, action: () -> Unit) {
        val box = ui.r(.05f, top, .95f, top + .075f)
        ui.slate(c, box, ui.dp(12f), highlight = value)
        ui.text(c, title, box.left + ui.dp(18f), box.top + box.height() * .34f, ui.ts(11.5f), Palette.TEXT, spacing = .14f, middle = true)
        ui.text(c, hint, box.left + ui.dp(18f), box.top + box.height() * .7f, ui.ts(9f), Palette.TEXT_DIM, heavy = false, spacing = .03f, middle = true)
        val toggle = RectF(box.right - ui.dp(74f), box.centerY() - ui.dp(15f), box.right - ui.dp(16f), box.centerY() + ui.dp(15f))
        ui.toggle(c, toggle, value)
        tap(box) { play("click"); action(); invalidate() }
    }

    private fun backButton(c: Canvas, destination: Screen) {
        iconButton(c, .03f, .022f, .135f, .078f, Icon.CHEVRON_LEFT) { controller.navigate(destination) }
    }

    private fun drawClouds(c: Canvas) {
        if (!effectsEnabled) return
        bmp("cloud_0")?.let {
            val x = ((ui.time * 11f) % (ui.w + ui.w * .8f)) - ui.w * .6f
            ui.sprite(c, it, RectF(x, ui.h * .255f, x + ui.w * .5f, ui.h * .345f), 55)
        }
        bmp("cloud_2")?.let {
            val x = ui.w * .9f - ((ui.time * 7f) % (ui.w + ui.w * .9f))
            ui.sprite(c, it, RectF(x, ui.h * .58f, x + ui.w * .58f, ui.h * .68f), 42)
        }
    }

    private fun drawGem(c: Canvas, index: Int, box: RectF, enabled: Boolean) {
        val sprite = bmp("gem_$index")
        if (sprite != null) ui.sprite(c, sprite, box, if (enabled) 255 else 70)
        else ui.icon(c, box, Icon.GEM, if (enabled) gems[index].color else Color.GRAY)
    }

    private fun button(
        c: Canvas, l: Float, t: Float, r: Float, b: Float, label: String, kind: BtnKind,
        enabled: Boolean = true, icon: Icon? = null, size: Float = ui.ts(15f), action: () -> Unit
    ) {
        val box = ui.r(l, t, r, b)
        ui.button(c, box, label, kind, enabled, down(box), icon, size)
        if (enabled) tap(box, action)
    }

    private fun iconButton(c: Canvas, l: Float, t: Float, r: Float, b: Float, glyph: Icon, action: () -> Unit) {
        val box = ui.r(l, t, r, b)
        ui.iconButton(c, box, glyph, down(box))
        tap(box, action)
    }

    private fun tap(box: RectF, action: () -> Unit) {
        targets += Target(box, action)
    }

    private fun down(box: RectF) = pressing && box.contains(pressX, pressY)

    private fun bmp(name: String) = bitmaps[name]

    private fun short(value: Int) = if (value >= 10000) "${value / 1000}K" else value.toString()

    private fun difficultyColor(value: Int) = when (value) {
        1 -> Palette.GREEN
        2 -> Palette.CYAN
        3 -> Palette.BLUE
        4 -> Palette.VIOLET
        else -> Palette.RED
    }

    private fun qualityColor(value: String) = when (value.uppercase()) {
        "DIVINE" -> Palette.GOLD
        "AWAKENED" -> Palette.VIOLET
        "CHARGED" -> Palette.CYAN
        else -> Palette.TEXT_DIM
    }

    private fun verdictColor(value: String) = when {
        value.contains("PERFECT") || value.contains("DIVINE") -> Palette.GOLD
        value.contains("HIT") && !value.contains("WEAK") -> Palette.GREEN
        value.contains("RESTORED") -> Palette.CYAN
        else -> Palette.RED
    }

    private fun openLegal(title: String, url: String, asset: String) {
        play("click")
        context.startActivity(Intent(context, LegalActivity::class.java).apply {
            putExtra(LegalActivity.EXTRA_TITLE, title)
            putExtra(LegalActivity.EXTRA_URL, url)
            putExtra(LegalActivity.EXTRA_ASSET, asset)
        })
    }

    // ------------------------------------------------------------------- input

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                pressX = event.x
                pressY = event.y - insetTop
                pressing = true
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                pressing = false
                val y = event.y - insetTop
                targets.asReversed().firstOrNull { it.rect.contains(event.x, y) }?.action?.invoke()
                performClick()
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                pressing = false
                invalidate()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private companion object {
        val TIPS = listOf(
            "A Perfect hit doubles the gem's charge",
            "Three Perfects in a row add bonus charge",
            "Divine Strike instantly fills 30% of the charge",
            "Misses never erase your lasting progress"
        )
        val HAVEN_PARTS = listOf(
            "Central Sanctuary", "Mountain Platform", "Temple of Zeus", "Sky Bridges",
            "Thunder Altars", "Crystal Network", "Palace of the Gods", "Stormreach Core"
        )
        val UPGRADES = listOf(
            "Basic Anvil" to "Process common gems",
            "Precision Tuning" to "Wider accurate hit window",
            "Energy Conduit" to "Bonus charge on Perfect",
            "Protective Circuit" to "No overload penalty",
            "Seal of Zeus" to "Extra Divine Strike"
        )
        val QUALITIES = listOf(
            "RAW" to "raw", "CHARGED" to "charged", "AWAKENED" to "awakened", "DIVINE" to "divine"
        )
        val TUTORIAL_TITLES = listOf(
            "RESTORE THE HAVEN", "FEEL THE RHYTHM", "STRIKE ON TIME", "FORGE THE DIVINE"
        )
        val TUTORIAL_TEXTS = listOf(
            "Process gems with divine lightning and spend rewards to restore the celestial haven of Olympus.",
            "The white marker runs along the energy meter. Green is a clean hit, gold is Perfect.",
            "Tap Lightning Strike when the marker enters the gold zone. Misses never erase your lasting progress.",
            "Build Perfect streaks, upgrade the forge, unlock new gems, and open all eight Olympus realms."
        )
        val TUTORIAL_ART = listOf(
            "Zeus_Main_Character_asset.webp", "Thunder_Forge_asset.webp",
            "bolt_1", "Divine_Lightning_Core_asset.webp"
        )
        val TUTORIAL_BG = listOf(
            "Stormreach_Haven_Background_asset.webp", "Temple_of_Zeus_Background_asset.webp",
            "Valley_of_Thunder_Background_asset.webp", "Celestial_Storm_Background_asset.webp"
        )
    }
}
