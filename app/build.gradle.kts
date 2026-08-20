import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Supabase credentials live in local.properties, the way the web app kept them
 * in .env: they are per-machine setup, not source. Absent values are fine —
 * the app then keeps everything on the device.
 */
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

/**
 * Release signing, when a key exists.
 *
 * The keystore and its passwords live in local.properties, which is not in
 * version control — a signing key in a repository is a signing key anyone can
 * use to publish something that claims to be this app. With no key configured
 * the release build still runs and produces an unsigned APK, so a checkout
 * that has never been signed is not a broken checkout.
 */
val keystorePath: String? = localProperties.getProperty("KEYSTORE_FILE")?.takeIf { it.isNotBlank() }

val supabaseUrl: String = localProperties.getProperty("SUPABASE_URL", "").trim()
val supabaseKey: String = localProperties.getProperty("SUPABASE_PUBLISHABLE_KEY", "").trim()

android {
    namespace = "az.spendly"
    compileSdk = 36

    defaultConfig {
        applicationId = "az.spendly"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "1.3.0"

        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"$supabaseKey\"")
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = localProperties.getProperty("KEYSTORE_PASSWORD")
                keyAlias = localProperties.getProperty("KEY_ALIAS")
                keyPassword = localProperties.getProperty("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            if (keystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

/**
 * A release built without a project is a different app.
 *
 * With no Supabase values the app falls back to keeping everything on the
 * device: no sign-in, no account, nothing shared between phones. That is a
 * perfectly good debug build and a broken release — and it is silent, so the
 * first person to find out is whoever installs it, opens it, and finds no way
 * to reach the data they already have.
 *
 * Pass -PallowLocalOnlyRelease to build one on purpose.
 */
tasks.configureEach {
    if (name != "assembleRelease" && name != "bundleRelease") return@configureEach
    doFirst {
        val deliberate = project.hasProperty("allowLocalOnlyRelease")
        if (!deliberate && (supabaseUrl.isEmpty() || supabaseKey.isEmpty())) {
            error(
                "local.properties faylında SUPABASE_URL və SUPABASE_PUBLISHABLE_KEY yoxdur. " +
                    "Bu dəyərlər olmadan buraxılış yalnız cihaz yaddaşında işləyir — " +
                    "giriş ekranı və hesab olmur. Bilərəkdən belə yığmaq üçün: " +
                    "./gradlew assembleRelease -PallowLocalOnlyRelease",
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
