plugins {
    id("com.android.application")
}

val releaseStoreFile = providers.environmentVariable("DEEPSEEK_RELEASE_STORE_FILE").orNull
val releaseStorePassword = providers.environmentVariable("DEEPSEEK_RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("DEEPSEEK_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("DEEPSEEK_RELEASE_KEY_PASSWORD").orNull

android {
    namespace = "app.deepseek.harness.mobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.deepseek.harness.mobile"
        minSdk = 26
        targetSdk = 36
        versionCode = 8
        versionName = "1.1.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    packaging {
        // gomobile AAR 的 libgojni.so 需在安装时解出（instrumentation 上下文不能从
        // 未解压 APK 直载 .so，api31 实测 dlopen "not found"）。AAR 已是主依赖，
        // 主 APK 与 androidTest APK 的全部 .so 都由此在安装时解出。
        jniLibs { useLegacyPackaging = true }
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all { !it.isNullOrBlank() }) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            // -PqaInstall=true builds the same-cert package that the Pixel's .qa install can be
            // replaced with (pairing data survives). Default stays .debug.
            val qaInstall = providers.gradleProperty("qaInstall").orNull == "true"
            applicationIdSuffix = if (qaInstall) ".qa" else ".debug"
            versionNameSuffix = if (qaInstall) "-qa" else "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    kotlin {
        jvmToolchain(21)
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        disable += setOf(
            "AndroidGradlePluginVersion",
            "GradleDependency",
            "OldTargetApi",
        )
    }
}

dependencies {
    implementation("androidx.activity:activity:1.11.0")
    implementation("androidx.browser:browser:1.9.0")
    implementation("androidx.credentials:credentials:1.6.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.6.0")
    implementation("androidx.webkit:webkit:1.17.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("junit:junit:4.13.2")
    // tstunnel AAR 由 tstunnel/build-aar.sh 产出（不入库）：隧道是主代码依赖（GoLayer），
    // 构建前需先跑该脚本（与 Android SDK 同类的本机前置）。spike 仪器测试由参数驱动，
    // 缺参数自动跳过。
    implementation(files("libs/tstunnel.aar"))
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:monitor:1.7.2")
}
