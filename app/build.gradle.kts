import java.util.Properties


val releaseSigningPropertiesFile = file(System.getProperty("user.home") + "/.config/null-reader/release.properties")
val releaseSigningProperties = Properties().apply {
    if (releaseSigningPropertiesFile.exists()) {
        releaseSigningPropertiesFile.inputStream().use(::load)
    }
}

val ciSigningFile = rootProject.file("ci/nullreader-ci.keystore")
val personalLibraryUrl = providers.gradleProperty("nullReaderPersonalUrl").orNull ?: ""
val personalLibraryToken = providers.gradleProperty("nullReaderPersonalToken").orNull ?: ""

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.nullyard.reader"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nullyard.reader"
        minSdk = 28
        targetSdk = 36
        versionCode = 8
        versionName = "0.3-beta5"
        buildConfigField("String", "PERSONAL_LIBRARY_URL", "\"$personalLibraryUrl\"")
        buildConfigField("String", "PERSONAL_LIBRARY_TOKEN", "\"$personalLibraryToken\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("ci") {
            if (ciSigningFile.exists()) {
                storeFile = ciSigningFile
                storePassword = "nullreader-ci"
                keyAlias = "nullreader-ci"
                keyPassword = "nullreader-ci"
            }
        }

        create("release") {
            if (releaseSigningPropertiesFile.exists()) {
                storeFile = file(releaseSigningProperties.getProperty("storeFile"))
                storePassword = releaseSigningProperties.getProperty("storePassword")
                keyAlias = releaseSigningProperties.getProperty("keyAlias")
                keyPassword = releaseSigningProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (ciSigningFile.exists()) {
                signingConfig = signingConfigs.getByName("ci")
            }
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("release")
        }
    }
}
