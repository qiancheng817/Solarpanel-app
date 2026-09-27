plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.solarpanel.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.solarpanel.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 38
        versionName = "2.1.7"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/solarpanel.jks")
            // 密码优先从环境变量读取（CI 可通过 GitHub Secret 注入）；
            // 未配置时回退到仓库内固定口令，保持与旧版相同签名、可覆盖安装。
            val password = System.getenv("SOLARPANEL_KEYSTORE_PASSWORD") ?: "solarpanel"
            storePassword = password
            keyAlias = "solarpanel"
            keyPassword = password
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 仓库内没有密钥库时退化为 debug 签名，保证始终能产出可安装包；
            // CI 会保证密钥库存在，正式产物始终使用 release 签名。
            signingConfig = if (rootProject.file("keystore/solarpanel.jks").exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("com.google.android.material:material:1.12.0")
}
