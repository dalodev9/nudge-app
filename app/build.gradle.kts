import java.util.Properties
import java.io.FileInputStream
import com.android.build.api.artifact.SingleArtifact

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        load(FileInputStream(localPropertiesFile))
    }
}

android {
    namespace = "com.nudge.app"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.nudge.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("RELEASE_KEYSTORE_PATH")
                ?: localProperties.getProperty("release.keystore.path")
            val keystorePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                ?: localProperties.getProperty("release.keystore.password")
            val keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                ?: localProperties.getProperty("release.key.alias")
            val keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
                ?: localProperties.getProperty("release.key.password")

            if (keystorePath != null && file(keystorePath).exists()) {
                storeFile = file(keystorePath)
                storePassword = keystorePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile != null) {
                signingConfig = releaseSigning
            } else {
                // Fallback to debug signing config if release keystore is absent
                signingConfig = signingConfigs.getByName("debug")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    jvmToolchain(17)
}

// Rename APK/AAB outputs to nudge-<buildType>-<versionName>.[apk|aab].
// AGP 9's public Variant API no longer exposes a direct outputFileName setter,
// so both outputs are renamed post-build via their SingleArtifact providers.
androidComponents {
    onVariants { variant ->
        val versionName = android.defaultConfig.versionName ?: "unknown"
        val variantName = variant.name // "debug" or "release"
        val baseName = "nudge-$variantName-$versionName"
        val capitalizedVariant = variantName.replaceFirstChar { it.uppercase() }

        // APK filename
        val apkDir = variant.artifacts.get(SingleArtifact.APK)
        val builtArtifactsLoader = variant.artifacts.getBuiltArtifactsLoader()
        val renameApkTask = tasks.register("rename${capitalizedVariant}Apk") {
            inputs.dir(apkDir)
            doLast {
                val builtArtifacts = builtArtifactsLoader.load(apkDir.get())
                    ?: return@doLast
                builtArtifacts.elements.forEach { artifact ->
                    val source = File(artifact.outputFile)
                    val dest = source.resolveSibling("$baseName.apk")
                    // Copy only — don't delete the original. AGP's output-metadata.json
                    // (read by Android Studio's Run/Deploy) is written before this task
                    // runs and still points at the original filename; deleting it causes
                    // "We were unable to deploy your changes: FileNotFoundException".
                    if (source != dest) {
                        source.copyTo(dest, overwrite = true)
                    }
                }
            }
        }
        tasks.matching { it.name == "assemble$capitalizedVariant" }.configureEach {
            finalizedBy(renameApkTask)
        }

        // AAB filename
        val bundleFile = variant.artifacts.get(SingleArtifact.BUNDLE)
        val renameBundleTask = tasks.register("rename${capitalizedVariant}Bundle") {
            inputs.file(bundleFile)
            doLast {
                val source = bundleFile.get().asFile
                val dest = source.resolveSibling("$baseName.aab")
                // Copy only — same reasoning as the APK task above: leave the original
                // in place for AGP/IDE tooling that expects it via its own listing file.
                if (source != dest) {
                    source.copyTo(dest, overwrite = true)
                }
            }
        }
        tasks.matching { it.name == "bundle$capitalizedVariant" }.configureEach {
            finalizedBy(renameBundleTask)
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
