import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI
import java.util.Locale
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val requestedOrigin = providers.gradleProperty("runwayOrigin")
    .orElse("https://runway.invalid")
    .get()
    .trim()
    .removeSuffix("/")
val runwayApplicationId = providers.gradleProperty("runwayApplicationId")
    .orElse("com.deftmartian.runway")
    .get()
    .trim()
if (!runwayApplicationId.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))) {
    throw GradleException("runwayApplicationId must be a valid Android application id")
}
val requestedUri = runCatching { URI(requestedOrigin) }.getOrElse {
    throw GradleException("runwayOrigin must be an absolute HTTPS origin")
}
val runwayScheme = requestedUri.scheme?.lowercase(Locale.ROOT)
    ?: throw GradleException("runwayOrigin must include a scheme")
val runwayHost = requestedUri.host?.trim('[', ']')?.lowercase(Locale.ROOT)
    ?: throw GradleException("runwayOrigin must include a host")
val runwayPort = requestedUri.port
val hasInvalidOriginParts = requestedUri.userInfo != null ||
    requestedUri.query != null ||
    requestedUri.fragment != null ||
    (!requestedUri.path.isNullOrEmpty() && requestedUri.path != "/") ||
    (runwayPort != -1 && runwayPort !in 1..65535)
if (hasInvalidOriginParts) {
    throw GradleException("runwayOrigin must not include credentials, a path, query, or fragment")
}

fun isLocalDebugHost(host: String): Boolean {
    if (
        host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") ||
        host == "::1" || host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe80:")
    ) return true
    val octets = host.split('.').map { it.toIntOrNull() }
    if (octets.size != 4 || octets.any { it == null || it !in 0..255 }) return false
    val first = octets[0] ?: return false
    val second = octets[1] ?: return false
    return first == 10 || first == 127 ||
        (first == 172 && second in 16..31) ||
        (first == 192 && second == 168) ||
        (first == 169 && second == 254)
}

if (runwayScheme != "https" && (runwayScheme != "http" || !isLocalDebugHost(runwayHost))) {
    throw GradleException("runwayOrigin must use HTTPS; cleartext is limited to local debug origins")
}

val runwayOrigin = URI(runwayScheme, null, runwayHost, runwayPort, null, null, null).toString()
val runwayStartUrl = "$runwayOrigin/app"
val assetStatements = """[{"relation":["delegate_permission/common.handle_all_urls"],"target":{"namespace":"web","site":"$runwayOrigin"}}]"""
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

fun requiredSigningProperty(name: String): String = releaseSigningProperties
    ?.getProperty(name)
    ?.trim()
    ?.takeIf(String::isNotEmpty)
    ?: throw GradleException("android/signing.properties is missing $name")

android {
    namespace = "com.deftmartian.runway"
    compileSdk = 36

    defaultConfig {
        applicationId = runwayApplicationId
        minSdk = 23
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.1"

        manifestPlaceholders["runwayScheme"] = runwayScheme
        manifestPlaceholders["runwayHost"] = runwayHost
        manifestPlaceholders["usesCleartextTraffic"] = "false"
        buildConfigField("String", "RUNWAY_ORIGIN", "\"$runwayOrigin\"")
        resValue("string", "runway_start_url", runwayStartUrl)
        resValue("string", "asset_statements", assetStatements)
    }

    signingConfigs {
        if (releaseSigningProperties != null) {
            create("runwayRelease") {
                storeFile = rootProject.file(requiredSigningProperty("storeFile"))
                storePassword = requiredSigningProperty("storePassword")
                keyAlias = requiredSigningProperty("keyAlias")
                keyPassword = requiredSigningProperty("keyPassword")
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
    implementation("com.google.androidbrowserhelper:androidbrowserhelper:2.7.2")

    testImplementation("junit:junit:4.13.2")
}

val verifyReleaseInstance by tasks.registering {
    group = "verification"
    description = "Rejects placeholder or cleartext instance origins for release builds."
    doLast {
        if (
            runwayScheme != "https" ||
            runwayHost.endsWith(".invalid") ||
            runwayHost.endsWith(".test") ||
            runwayHost.endsWith(".localhost") ||
            runwayHost == "localhost" ||
            runwayHost.endsWith(".example") ||
            runwayHost == "example.com" ||
            runwayHost.endsWith(".example.com") ||
            runwayHost == "example.net" ||
            runwayHost.endsWith(".example.net") ||
            runwayHost == "example.org" ||
            runwayHost.endsWith(".example.org") ||
            (runwayPort != -1 && runwayPort != 443)
        ) {
            throw GradleException(
                "Release builds require the final runway HTTPS origin on the default port",
            )
        }
    }
}

val verifyReleaseSigning by tasks.registering {
    group = "verification"
    description = "Fails unless an external, complete Android release signing identity is present."
    dependsOn(verifyReleaseInstance)
    doLast {
        if (releaseSigningProperties == null) {
            throw GradleException(
                "Release builds require untracked android/signing.properties; copy " +
                    "android/signing.properties.example and provide operator-owned credentials",
            )
        }
        val configuredStore = rootProject.file(requiredSigningProperty("storeFile"))
        if (!configuredStore.isFile) {
            throw GradleException("The release keystore configured by signing.properties was not found")
        }
    }
}

val verifyReleasePackaging by tasks.registering {
    group = "verification"
    description = "Requires direct signing or the explicit unsigned F-Droid source-build path."
    if (fdroidSourceBuild) {
        dependsOn(verifyReleaseInstance)
        doLast {
            if (releaseSigningProperties != null) {
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
