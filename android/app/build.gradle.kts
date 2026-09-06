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

    signingConfigs {
        create("release") {
            // 从环境变量读取，密码不落入源码（KEY_STORE / KEY_STORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD）
            val ks = System.getenv("KEY_STORE")
            val ksp = System.getenv("KEY_STORE_PASSWORD")
            val ka = System.getenv("KEY_ALIAS")
            val kp = System.getenv("KEY_PASSWORD")
            if (ks == null || ksp == null || ka == null || kp == null) {
                throw GradleException("缺少签名环境变量: KEY_STORE / KEY_STORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD")
            }
            storeFile = file(ks)
            storePassword = ksp
            keyAlias = ka
            keyPassword = kp
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 正式版只打 arm64-v8a；正式证书签名（环境变量）
            ndk { abiFilters += listOf("arm64-v8a") }
            signingConfig = signingConfigs.getByName("release")
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
