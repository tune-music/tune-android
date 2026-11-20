plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val updateFeedUrl = providers.gradleProperty("tuneUpdateFeedUrl")
    .orElse("https://github.com/tune-music/tune-android/releases/latest/download/update.json").get()
val devFeedUrl = providers.gradleProperty("tuneDevUpdateFeedUrl").getOrElse("")
val devVersion = providers.gradleProperty("tuneDevBuildNumber").getOrElse("1").toInt()
require(devVersion in 1..2_100_000_000)

fun encodedFeedUrl(value: String): String {
    if (value.isNotEmpty()) {
        val url = uri(value)
        require(url.scheme == "https" && url.host != null && url.userInfo == null && url.fragment == null) {
            "The update feed must be an HTTPS URL without credentials or a fragment"
        }
    }
    return "\"$value\""
}

android {
    namespace = "music.tune.android"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "music.tune.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 20
        versionName = "0.2.18"
        buildConfigField("String", "UPDATE_FEED_URL", encodedFeedUrl(updateFeedUrl))
    }

    buildTypes {
        create("dev") {
            initWith(getByName("release"))
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev.$devVersion"
            resValue("string", "app_name", "Tune Dev")
            resValue("string", "shortcut_target_package", "music.tune.android.dev")
            buildConfigField("String", "UPDATE_FEED_URL", encodedFeedUrl(devFeedUrl))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { buildConfig = true }
}

androidComponents {
    onVariants(selector().withBuildType("dev")) { variant ->
        variant.outputs.forEach { it.versionCode.set(devVersion) }
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("io.coil-kt:coil:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
