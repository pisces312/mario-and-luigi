plugins {
    id("com.android.application")
}

android {
    namespace = "org.libsdl.mario"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.libsdl.mario"
        minSdk = 21
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 正式版只打 arm64-v8a；签名复用 debug keystore（可安装），后续可换正式证书
            ndk { abiFilters += listOf("arm64-v8a") }
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets["main"].jniLibs.srcDir("src/main/jniLibs")

    lint {
        abortOnError = false
    }
}

dependencies {
}
