plugins {
    id("com.android.application")
}

val signingPath = System.getenv("ANDROID_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
val signingStorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD") ?: ""
val signingAlias = System.getenv("ANDROID_KEY_ALIAS") ?: ""
val signingKeyPassword = System.getenv("ANDROID_KEY_PASSWORD") ?: ""

android {
    namespace = "com.tvroom.downloader"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tvroom.downloader"
        minSdk = 26
        targetSdk = 36
        versionCode = 12
        versionName = "1.1.3"
    }

    signingConfigs {
        signingPath?.let { path ->
            create("githubRelease") {
                storeFile = file(path)
                storePassword = signingStorePassword
                keyAlias = signingAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingPath?.let { signingConfig = signingConfigs.getByName("githubRelease") }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity:1.10.1")
    implementation("androidx.core:core:1.16.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("androidx.media3:media3-exoplayer:1.10.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.10.1")
    implementation("androidx.media3:media3-transformer:1.10.1")
    implementation("androidx.media3:media3-ui:1.10.1")
    implementation("androidx.documentfile:documentfile:1.1.0")
}
