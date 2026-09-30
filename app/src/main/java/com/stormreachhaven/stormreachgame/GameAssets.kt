package com.stormreachhaven.stormreachgame

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

/**
 * Decoded art for the native game. Loaded only after the router has already
 * chosen the native part, while the first splash is still on screen.
 * A WebView launch never calls [prepare].
 */
object GameAssets {

    val bitmaps = ConcurrentHashMap<String, Bitmap>()

    @Volatile
    var ready: Boolean = false
        private set

    private val gate = Any()

    fun prepare(context: Context) {
        if (ready) return
        synchronized(gate) {
            if (ready) return
            val app = context.applicationContext
            NAMES.forEach { name ->
                try {
                    app.assets.open(name).use { stream ->
                        BitmapFactory.decodeStream(stream)?.let { prepareBitmap(name, it) }
                    }
                } catch (_: Exception) {
                }
            }
            ready = true
        }
    }

    private fun prepareBitmap(name: String, bitmap: Bitmap) {
        when (name) {
            "Gemstones_Ruby_Sapphire_Emerald_Amethyst_asset.webp" -> slice(bitmap, 2, 2, "gem_")
            "Celestial_Cloud_Clusters_Set_asset.webp" -> slice(bitmap, 2, 2, "cloud_")
            "Lightning_Bolt_Effects_Set_asset.webp" -> slice(bitmap, 4, 1, "bolt_")
            else -> {
                val keepFrame = name.contains("_Background_")
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

    private val NAMES = listOf(
        "Game_Name.webp",
        "Stormreach_Haven_Background_asset.webp",
        "Mount_Olympus_Background_asset.webp",
        "Temple_of_Zeus_Background_asset.webp",
        "Aether_Isles_Background_asset.webp",
        "Valley_of_Thunder_Background_asset.webp",
        "Crystal_Caverns_Background_asset.webp",
        "Palace_of_the_Gods_Background_asset.webp",
        "Celestial_Storm_Background_asset.webp",
        "Gemstones_Ruby_Sapphire_Emerald_Amethyst_asset.webp",
        "Celestial_Cloud_Clusters_Set_asset.webp",
        "Lightning_Bolt_Effects_Set_asset.webp",
        "Thunder_Forge_asset.webp",
        "Gemstone_Altar_asset.webp",
        "Zeus_Main_Character_asset.webp",
        "Stormreach_Haven_Core_asset.webp",
        "Divine_Lightning_Core_asset.webp",
        "Storm_Crystal_asset.webp",
        "Olympus_Energy_Orb_asset.webp",
        "Divine_Gem_Fragment_asset.webp",
        "Haven_Restoration_Stone_asset.webp",
        "Golden_Divine_Relic_asset.webp",
        "Zeus_Thunder_Symbol_asset.webp",
        "Ancient_Lightning_Seal_asset.webp"
    )
}
