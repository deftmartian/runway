import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

data class RunwaySigningIdentity(
    val storeFile: String,
    val storeType: String,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
)

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val runwayApplicationId = providers.gradleProperty("runwayApplicationId")
    .orElse("dev.deftmartian.runway")
    .get()
    .trim()
if (!runwayApplicationId.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))) {
    throw GradleException("runwayApplicationId must be a valid Android application id")
}
val releaseSigningPropertiesFile = rootProject.file(
    providers.gradleProperty("runwaySigningPropertiesFile")
        .orElse("signing.properties")
        .get(),
)
val fdroidSourceBuild = providers.gradleProperty("runwayFdroidSourceBuild")
    .map(String::toBoolean)
    .orElse(false)
    .get()
val runwayBuildCommit = providers.gradleProperty("runwayBuildCommit")
    .orElse(providers.environmentVariable("GITHUB_SHA"))
    .orElse("unknown")
    .get()
    .trim()
if (runwayBuildCommit != "unknown" && !runwayBuildCommit.matches(Regex("[0-9a-fA-F]{7,40}"))) {
    throw GradleException("runwayBuildCommit must be a 7-40 character hexadecimal commit")
}
val releaseSigningProperties = if (releaseSigningPropertiesFile.isFile) {
    Properties().apply {
        releaseSigningPropertiesFile.inputStream().use(::load)
    }
} else {
    null
}
val environmentSigningValues = mapOf(
    "storeFile" to providers.environmentVariable("RUNWAY_ANDROID_KEYSTORE_FILE").orNull,
    "storePassword" to providers.environmentVariable("RUNWAY_ANDROID_KEYSTORE_PASSWORD").orNull,
    "keyAlias" to providers.environmentVariable("RUNWAY_ANDROID_KEY_ALIAS").orNull,
    "keyPassword" to providers.environmentVariable("RUNWAY_ANDROID_KEY_PASSWORD").orNull,
).mapValues { (_, value) -> value?.trim()?.takeIf(String::isNotEmpty) }
if (environmentSigningValues.values.any { it != null } && environmentSigningValues.values.any { it == null }) {
    throw GradleException("Android release signing environment is incomplete")
}
val releaseSigningIdentity = when {
    environmentSigningValues.values.all { it != null } -> RunwaySigningIdentity(
        storeFile = requireNotNull(environmentSigningValues["storeFile"]),
        storeType = "PKCS12",
        storePassword = requireNotNull(environmentSigningValues["storePassword"]),
        keyAlias = requireNotNull(environmentSigningValues["keyAlias"]),
        keyPassword = requireNotNull(environmentSigningValues["keyPassword"]),
    )
    releaseSigningProperties != null -> RunwaySigningIdentity(
        storeFile = releaseSigningProperties.getProperty("storeFile")?.trim().orEmpty(),
        storeType = releaseSigningProperties.getProperty("storeType")?.trim().orEmpty(),
        storePassword = releaseSigningProperties.getProperty("storePassword")?.trim().orEmpty(),
        keyAlias = releaseSigningProperties.getProperty("keyAlias")?.trim().orEmpty(),
        keyPassword = releaseSigningProperties.getProperty("keyPassword")?.trim().orEmpty(),
    ).also { identity ->
        if (
            identity.storeFile.isEmpty() ||
            identity.storeType != "PKCS12" ||
            identity.storePassword.isEmpty() ||
            identity.keyAlias.isEmpty() ||
            identity.keyPassword.isEmpty()
        ) {
            throw GradleException(
                "android/signing.properties must be complete and use storeType=PKCS12",
            )
        }
    }
    else -> null
}

android {
    namespace = "dev.deftmartian.runway"
    compileSdk = 36
    useLibrary("android.test.runner")
    useLibrary("android.test.base")

    defaultConfig {
        applicationId = runwayApplicationId
        // Health Connect's Jetpack client supports Android 8.0.
        minSdk = 26
        targetSdk = 36
        versionCode = 13
        versionName = "0.7.1"
        buildConfigField("String", "SOURCE_COMMIT", "\"$runwayBuildCommit\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    }

    signingConfigs {
        if (releaseSigningIdentity != null) {
            create("runwayRelease") {
                storeFile = rootProject.file(releaseSigningIdentity.storeFile)
                storeType = releaseSigningIdentity.storeType
                storePassword = releaseSigningIdentity.storePassword
                keyAlias = releaseSigningIdentity.keyAlias
                keyPassword = releaseSigningIdentity.keyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
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
            signingConfig = signingConfigs.findByName("runwayRelease")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")

    implementation(project(":data"))
    implementation(project(":domain"))
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.navigation:navigation-compose:2.9.8")
    implementation("androidx.work:work-runtime:2.11.2")
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.google.guava:guava:33.6.0-android")
    implementation(composeBom)
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.test:runner:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

val verifyReleaseSigning by tasks.registering {
    group = "verification"
    description = "Fails unless an external, complete Android release signing identity is present."
    doLast {
        if (releaseSigningIdentity == null) {
            throw GradleException(
                "Release builds require untracked android/signing.properties; copy " +
                    "android/signing.properties.example and provide operator-owned credentials",
            )
        }
        val configuredStore = rootProject.file(releaseSigningIdentity.storeFile)
        if (!configuredStore.isFile) {
            throw GradleException("The release keystore configured by signing.properties was not found")
        }
    }
}

val verifyReleasePackaging by tasks.registering {
    group = "verification"
    description = "Requires direct signing or the explicit unsigned F-Droid source-build path."
    if (fdroidSourceBuild) {
        doLast {
            if (releaseSigningIdentity != null) {
                throw GradleException(
                    "F-Droid source builds must be unsigned; remove android/signing.properties",
                )
            }
        }
    } else {
        dependsOn(verifyReleaseSigning)
    }
}

tasks.matching {
    it.name == "assembleRelease" ||
        it.name == "bundleRelease" ||
        it.name == "packageRelease" ||
        it.name == "packageReleaseBundle" ||
        it.name == "signReleaseBundle" ||
        it.name == "installRelease"
}.configureEach {
    dependsOn(verifyReleasePackaging)
}
