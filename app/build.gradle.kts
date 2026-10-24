import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val localProperties = Properties().apply {
    val propertiesFile = rootProject.file("local.properties")
    if (propertiesFile.isFile) {
        propertiesFile.inputStream().use(::load)
    }
}

fun localProperty(name: String): String? = localProperties.getProperty(name)?.takeIf { it.isNotBlank() }

fun buildConfigString(value: String): String = "\"" + value
    .replace("\\", "\\\\")
    .replace("\"", "\\\"") + "\""

val feedbackEndpoint = localProperty("emucorex.feedback.endpoint").orEmpty()
val feedbackApiKey = localProperty("emucorex.feedback.apiKey").orEmpty()
val multiplayerSignalingUrl = "https://emucorex-multiplayer.kyivstar19971502.workers.dev"
val multiplayerClientCode = "ecx-mp-v1-7H4K9M2Q"
val discordApplicationId = "1536775623287115786"
val discordSdkDirectory = (
    providers.gradleProperty("emucorex.discord.sdkDir").orNull
        ?: localProperty("emucorex.discord.sdkDir")
        ?: providers.environmentVariable("DISCORD_SDK_DIR").orNull
    )
    ?.let(::file)
    ?.takeIf { sdkDir ->
        sdkDir.resolve("include/discordpp.h").isFile &&
            sdkDir.resolve("arm64-v8a/libdiscord_partner_sdk.so").isFile &&
            sdkDir.resolve("discord_partner_sdk.aar").isFile
    }

// Supplied only by the local release script; no signing secrets in the repository.
val dukeStoreFile = providers.environmentVariable("DUKE_STORE_FILE").orNull
val dukeStorePassword = providers.environmentVariable("DUKE_STORE_PASSWORD").orNull
val dukeKeyAlias = providers.environmentVariable("DUKE_KEY_ALIAS").orNull
val dukeKeyPassword = providers.environmentVariable("DUKE_KEY_PASSWORD").orNull
val dukeSigningReady = listOf(dukeStoreFile, dukeStorePassword, dukeKeyAlias, dukeKeyPassword)
    .all { !it.isNullOrBlank() }

android {
    namespace = "com.sbro.emucorer"
    compileSdk {
        version = release(37)
    }
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.crixux.dukestation"
        minSdk = 26
        targetSdk = 37
        versionCode = 11
        versionName = "1.1.0-preview7"

        buildConfigField("String", "FEEDBACK_ENDPOINT", buildConfigString(feedbackEndpoint))
        buildConfigField("String", "FEEDBACK_API_KEY", buildConfigString(feedbackApiKey))
        buildConfigField("String", "MULTIPLAYER_SIGNALING_URL", buildConfigString(multiplayerSignalingUrl))
        buildConfigField("String", "MULTIPLAYER_CLIENT_CODE", buildConfigString(multiplayerClientCode))
        buildConfigField("long", "DISCORD_APPLICATION_ID", "${discordApplicationId}L")
        buildConfigField("boolean", "DISCORD_SDK_AVAILABLE", (discordSdkDirectory != null).toString())
        manifestPlaceholders["discordSdkAvailable"] = (discordSdkDirectory != null).toString()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            //noinspection ChromeOsAbiSupport
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++23"
                arguments += "-DANDROID_STL=c++_shared"
                discordSdkDirectory?.let { arguments += "-DDISCORD_SDK_DIR=${it.absolutePath}" }
            }
        }
    }

    signingConfigs {
        create("dukeRelease") {
            if (dukeSigningReady) {
                storeFile = file(dukeStoreFile!!)
                storePassword = dukeStorePassword
                keyAlias = dukeKeyAlias
                keyPassword = dukeKeyPassword
            }
        }
    }
    buildTypes {
        debug {
            // The emulator core is performance-sensitive even when the
            // Android frontend is debuggable. Keep symbols and assertions,
            // but do not run the complete native machine at Clang's implicit
            // -O0 when launching it from Android Studio.
            externalNativeBuild {
                cmake {
                    cFlags += "-O3"
                    cppFlags += "-O3"
                }
            }
        }
        release {
            signingConfig = signingConfigs.getByName("dukeRelease")
            // AGP configures CMake with CMAKE_BUILD_TYPE=RelWithDebInfo, whose
            // default flags are "-O2 -g -DNDEBUG" and are appended after any -O3
            // given through cFlags/cppFlags, so that -O2 would win. Replace the
            // RelWithDebInfo flags outright to keep symbols but compile the
            // emulator core at the same -O3 as the debug build.
            //
            // Overriding -DCMAKE_BUILD_TYPE=Release would also enable the
            // core's LTO block, but full LTO across the whole vendored machine
            // is a large build-time cost and stays a separate step.
            externalNativeBuild {
                cmake {
                    arguments += listOf(
                        "-DCMAKE_C_FLAGS_RELWITHDEBINFO=-O3 -g -DNDEBUG",
                        "-DCMAKE_CXX_FLAGS_RELWITHDEBINFO=-O3 -g -DNDEBUG"
                    )
                }
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            discordSdkDirectory?.let { pickFirsts += "**/libdiscord_partner_sdk.so" }
        }
    }
    discordSdkDirectory?.let { sdkDir -> sourceSets["main"].jniLibs.srcDir(sdkDir) }
    bundle {
        language {
            enableSplit = false
        }
    }
    lint {
        lintConfig = file("lint.xml")
    }
}

dependencies {
    // Local game OCR and translation. OCR models are bundled; language translation
    // models are downloaded only after the user presses Download models.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
    implementation("com.google.mlkit:translate:17.0.3")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.fragment)
    implementation(libs.google.play.review)
    implementation(libs.google.play.review.ktx)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.androidx.browser)
    implementation(libs.webrtc.android)
    implementation(libs.android.youtube.player.core)
    ksp(libs.androidx.room.compiler)
    discordSdkDirectory?.let { sdkDir ->
        implementation(files(sdkDir.resolve("discord_partner_sdk.aar")))
    }
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

val verifyDukeReleaseSigning = tasks.register("verifyDukeReleaseSigning") {
    doLast {
        check(dukeSigningReady) { "Use Build-DukeRelease.ps1 to provide your local signing key." }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(verifyDukeReleaseSigning)
}
