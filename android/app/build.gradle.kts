plugins { id("com.android.application") version "9.3.1" }

android {
    namespace = "cn.jiuwusan.jellyfin.playbackprobe"
    compileSdk = 36
    defaultConfig {
        applicationId = "cn.jiuwusan.jellyfin.playbackprobe"
        minSdk = 23
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.8.0")
    implementation("androidx.media3:media3-datasource-okhttp:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")
}
