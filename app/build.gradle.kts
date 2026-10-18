// Ported from Files (YAZ/Files at HEAD, app/build.gradle.kts), itself from Calculator (https://github.com/213YaZ786/Calculator, app/build.gradle.kts).
// Left out of Files' build: Room and KSP (its name index), Tink and Biometric (its Safe folder), Lottie (its easter eggs),
// the lifecycle-process library (the update kit's, which Store does not take).
plugins {
    // AGP 9 compiles Kotlin itself (built-in Kotlin), so no kotlin-android here.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.yaz.store"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.yaz.store"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        create("release") {
            val storeFilePath = providers.gradleProperty("store.storeFile").orNull
            if (storeFilePath != null) {
                storeFile = file(storeFilePath)
                storePassword = providers.gradleProperty("store.storePassword").orNull
                keyAlias = providers.gradleProperty("store.keyAlias").orNull
                keyPassword = providers.gradleProperty("store.keyPassword").orNull
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // Only attached when the signing properties are supplied, so a
            // local build without a keystore still produces an unsigned APK.
            if (providers.gradleProperty("store.storeFile").isPresent) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Screens drawn on the JVM by Robolectric, as SMS and Contacts do (Now in Android's core/screenshot-testing).
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all {
                it.maxHeapSize = "2048m"
                System.getenv("STORE_SHOTS")?.let { dir -> it.systemProperties["shots.dir"] = dir }
                // Robolectric on JDK 17 and later (https://robolectric.org/getting-started/).
                it.jvmArgs(
                    "--add-opens=java.base/java.lang=ALL-UNNAMED",
                    "--add-opens=java.base/java.util=ALL-UNNAMED",
                    "--add-opens=java.base/java.io=ALL-UNNAMED",
                    "--add-opens=java.base/java.net=ALL-UNNAMED",
                    "--add-opens=java.base/java.security=ALL-UNNAMED",
                    "--add-opens=java.base/java.text=ALL-UNNAMED",
                    "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                    "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
                    "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED"
                )
            }
        }
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/INDEX.LIST",
            "/META-INF/DEPENDENCIES"
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.graphics.shapes)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.koin.android)
    implementation(libs.koin.compose)

    // GIF frames, decoded only inside the isolated DecoderService (the safemedia kit), as SMS's and Contacts' build files declare it.
    implementation(libs.glide.gifdecoder)

    // Google's apksig (Apache 2.0, the library AGP and apksigner use): reads an APK's binary manifest
    // (AndroidBinXmlParser) and verifies its signature to give the signer certificates (ApkVerifier).
    // It runs only in the isolated ApkInspectorService (the install kit), never on a file in the app's own process.
    implementation("com.android.tools.build:apksig:9.4.1")
    // ARSCLib (REAndroid, Apache 2.0, Maven Central): reads resources.arsc to get an app's label.
    // Also only inside the isolated ApkInspectorService.
    implementation("io.github.reandroid:ARSCLib:1.3.5")

    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
