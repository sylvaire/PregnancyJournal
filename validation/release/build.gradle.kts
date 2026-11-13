plugins { alias(libs.plugins.android.application) }

val copySmokeTest by tasks.registering(Copy::class) {
    from("../../app/src/androidTest/java") {include("cn/yunji/journal/ReleaseBackupUiSmokeTest.java")}
    into(layout.buildDirectory.dir("generated/smokeJava"))
}

android {
    namespace="cn.yunji.releasevalidation"
    compileSdk {version=release(36) {minorApiLevel=1}}
    defaultConfig {applicationId="cn.yunji.releasevalidation";minSdk=26;targetSdk=36;versionCode=1;versionName="local-only"}
    compileOptions {sourceCompatibility=JavaVersion.VERSION_17;targetCompatibility=JavaVersion.VERSION_17}
    sourceSets.getByName("main").java.srcDir(layout.buildDirectory.dir("generated/smokeJava"))
    packaging {resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"}
}
tasks.named("preBuild") {dependsOn(copySmokeTest)}

dependencies {
    // Standalone instrumentation: do not subtract libraries present (and renamed) in the target APK.
    implementation(libs.junit)
    implementation(libs.androidx.runner)
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.20")
    // Align with already-validated app/test runtime artifacts rather than resolving old transitives.
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("androidx.tracing:tracing:1.2.0")
}
