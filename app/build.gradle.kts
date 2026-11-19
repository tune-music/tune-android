plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val updateFeedUrl = providers.gradleProperty("tuneUpdateFeedUrl")
    .orElse("https://github.com/tune-music/tune-android/releases/latest/download/update.json").get()
if (updateFeedUrl.isNotEmpty()) {
    val url = uri(updateFeedUrl)
    require(url.scheme == "https" && url.host != null && url.userInfo == null && url.fragment == null) {
        "tuneUpdateFeedUrl must be an HTTPS URL without credentials or a fragment"
    }
}

android {
    namespace = "music.tune.android"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "music.tune.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 19
        versionName = "0.2.17"
        buildConfigField("String", "UPDATE_FEED_URL", "\"$updateFeedUrl\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { buildConfig = true }
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
