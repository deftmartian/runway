import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

data class RunwaySigningIdentity(
    val storeFile: String,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
)

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

if (providers.gradleProperty("runwayOrigin").isPresent) {
    throw GradleException(
        "runwayOrigin is no longer supported; every runway APK uses in-app server selection",
    )
}
val runwayApplicationId = providers.gradleProperty("runwayApplicationId")
    .orElse("com.deftmartian.runway")
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
        storePassword = requireNotNull(environmentSigningValues["storePassword"]),
        keyAlias = requireNotNull(environmentSigningValues["keyAlias"]),
        keyPassword = requireNotNull(environmentSigningValues["keyPassword"]),
    )
    releaseSigningProperties != null -> RunwaySigningIdentity(
        storeFile = releaseSigningProperties.getProperty("storeFile")?.trim().orEmpty(),
        storePassword = releaseSigningProperties.getProperty("storePassword")?.trim().orEmpty(),
        keyAlias = releaseSigningProperties.getProperty("keyAlias")?.trim().orEmpty(),
        keyPassword = releaseSigningProperties.getProperty("keyPassword")?.trim().orEmpty(),
    ).also { identity ->
        if (
            identity.storeFile.isEmpty() ||
            identity.storePassword.isEmpty() ||
            identity.keyAlias.isEmpty() ||
            identity.keyPassword.isEmpty()
        ) {
            throw GradleException("android/signing.properties is incomplete")
        }
    }
    else -> null
}

android {
    namespace = "com.deftmartian.runway"
    compileSdk = 36
    useLibrary("android.test.runner")
    useLibrary("android.test.base")

    defaultConfig {
        applicationId = runwayApplicationId
        minSdk = 23
        targetSdk = 36
        versionCode = 4
        versionName = "0.2.1"
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"

        manifestPlaceholders["usesCleartextTraffic"] = "false"
    }

    signingConfigs {
        if (releaseSigningIdentity != null) {
            create("runwayRelease") {
                storeFile = rootProject.file(releaseSigningIdentity.storeFile)
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
            manifestPlaceholders["usesCleartextTraffic"] = "true"
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("runwayRelease")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            manifestPlaceholders["usesCleartextTraffic"] = "false"
        }
    }

    buildFeatures {
        buildConfig = true
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
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.work:work-runtime:2.11.2")
    implementation("androidx.browser:browser:1.10.0")

    testImplementation("junit:junit:4.13.2")
}

val verifyServerSelectionRelease by tasks.registering {
    group = "verification"
    description = "Verifies that releases use the in-app server-selection model."
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
    dependsOn(verifyServerSelectionRelease)
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
