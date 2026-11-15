plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
}

android {
    namespace = "cn.yunji.journal"
    compileSdk { version = release(36) { minorApiLevel = 1 } }
    defaultConfig {
        applicationId = "cn.yunji.journal"
        minSdk = 26
        targetSdk = 36
        versionCode = 30
        versionName = "0.28.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Only the explicit verification build disables FLAG_SECURE to capture real-device screenshots;
        // release/performance and normal debug builds keep the secure flag.
        buildConfigField("boolean", "VISUAL_VERIFICATION", providers.gradleProperty("visualVerification").orElse("false").get())
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
    testBuildType = providers.gradleProperty("testBuildType").orElse("debug").get()
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    // Chinese picker resources must also be available on English devices after bundle installation.
    bundle { language { enableSplit = false } }

    // 发布签名由 CI 通过环境变量注入（密钥本身从不入库）。
    // 四个变量齐备时才配置；缺任一则 release 仍产出未签名包，便于本地构建与审计。
    val releaseStoreFile = providers.environmentVariable("RELEASE_KEYSTORE_PATH").orNull
    val releaseStorePassword = providers.environmentVariable("RELEASE_KEYSTORE_PASSWORD").orNull
    val releaseKeyAlias = providers.environmentVariable("RELEASE_KEY_ALIAS").orNull
    val releaseKeyPassword = providers.environmentVariable("RELEASE_KEY_PASSWORD").orNull
    val hasReleaseSigning = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
        .all { !it.isNullOrBlank() } && file(requireNotNull(releaseStoreFile)).exists()
    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(requireNotNull(releaseStoreFile))
                storePassword = releaseStorePassword
                this.keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
        create("performance") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            matchingFallbacks += "release"
            proguardFiles("performance-rules.pro")
            testProguardFiles("performance-test-rules.pro")
        }
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
room3 { schemaDirectory("$projectDir/schemas") }

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.tooling.preview)
    debugImplementation(libs.compose.tooling)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.compose)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.navigation.runtime)
    implementation(libs.navigation.ui)
    implementation(libs.room.runtime)
    implementation(libs.sqlite)
    implementation(libs.sqlcipher)
    ksp(libs.room.compiler)
    implementation(libs.hilt)
    ksp(libs.hilt.compiler)
    implementation(libs.work)
    implementation(libs.coroutines)
    implementation(libs.serialization)
    implementation(libs.okhttp)
    implementation(libs.tink)
    implementation(libs.bcprov)
    implementation(libs.biometric)
    implementation(libs.coil.compose)
    implementation(libs.coil.svg)
    implementation(libs.datastore)
    // androidx 的 ExifInterface 在 API 26 上既能读也能写相机方向；平台实现拒绝写入真实 JPEG。
    implementation(libs.androidx.exifinterface)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.okhttp.tls)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.androidx.runner)
    // A real TLS WebDAV subset runs on the device so cloud restore can be exercised end to end.
    androidTestImplementation(libs.mockwebserver)
    androidTestImplementation(libs.okhttp.tls)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.test.junit4)
    // 设备测试夹具要为相机照片写入 EXIF；API 26 的平台 ExifInterface 拒绝写入。
    androidTestImplementation(libs.androidx.exifinterface)
    debugImplementation(libs.compose.test.manifest)
    add("performanceImplementation", libs.compose.test.manifest)
}

