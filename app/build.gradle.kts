import java.util.Properties

val releaseSigningProperties: Properties by lazy {
    Properties().apply {
        val localFile = rootProject.file("release-signing.local.properties")
        if (localFile.exists()) {
            localFile.inputStream().use(::load)
        }
    }
}

fun resolvePrivateBuildValue(
    environmentName: String,
    gradlePropertyName: String,
    localPropertyName: String,
): String {
    val environmentValue = providers.environmentVariable(environmentName).orNull
        ?.trim()
        .orEmpty()
    if (environmentValue.isNotBlank()) {
        return environmentValue
    }

    val gradleValue = providers.gradleProperty(gradlePropertyName).orNull
        ?.trim()
        .orEmpty()
    if (gradleValue.isNotBlank()) {
        return gradleValue
    }

    return releaseSigningProperties.getProperty(localPropertyName)
        ?.trim()
        .orEmpty()
}

val releaseStoreFilePath: String by lazy {
    resolvePrivateBuildValue(
        environmentName = "ANDROID_RELEASE_STORE_FILE",
        gradlePropertyName = "androidReleaseStoreFile",
        localPropertyName = "storeFile",
    )
}

val releaseStorePassword: String by lazy {
    resolvePrivateBuildValue(
        environmentName = "ANDROID_RELEASE_STORE_PASSWORD",
        gradlePropertyName = "androidReleaseStorePassword",
        localPropertyName = "storePassword",
    )
}

val releaseKeyAlias: String by lazy {
    resolvePrivateBuildValue(
        environmentName = "ANDROID_RELEASE_KEY_ALIAS",
        gradlePropertyName = "androidReleaseKeyAlias",
        localPropertyName = "keyAlias",
    )
}

val releaseKeyPassword: String by lazy {
    resolvePrivateBuildValue(
        environmentName = "ANDROID_RELEASE_KEY_PASSWORD",
        gradlePropertyName = "androidReleaseKeyPassword",
        localPropertyName = "keyPassword",
    )
}

val hasReleaseSigning: Boolean by lazy {
    listOf(
        releaseStoreFilePath,
        releaseStorePassword,
        releaseKeyAlias,
        releaseKeyPassword,
    ).all(String::isNotBlank) && rootProject.file(releaseStoreFilePath).exists()
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "nz.co.mixport.customsvision"
    compileSdk = 34
    flavorDimensions += "distribution"

    defaultConfig {
        applicationId = "nz.co.mixport.customsvision"
        minSdk = 26
        targetSdk = 34
        versionCode = 13
        versionName = "0.6.8"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    productFlavors {
        create("public") {
            dimension = "distribution"
            manifestPlaceholders["syncProvisioningAliasEnabled"] = "false"
            manifestPlaceholders["syncProvisioningAliasExported"] = "false"
            resValue("string", "sync_distribution_channel", "public")
        }
        create("field") {
            dimension = "distribution"
            manifestPlaceholders["syncProvisioningAliasEnabled"] = "true"
            manifestPlaceholders["syncProvisioningAliasExported"] = "true"
            resValue("string", "sync_distribution_channel", "field")
            versionNameSuffix = "-field"
        }
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = rootProject.file(releaseStoreFilePath)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.google.android.material:material:1.12.0")

    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-video:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.google.mlkit:image-labeling:17.0.9")
    implementation("com.google.mlkit:object-detection:17.0.2")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
