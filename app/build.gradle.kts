import java.util.Properties
import java.io.FileInputStream

// Загрузка properties из local.properties
val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties") // rootProject используется для доступа к файлу в корне проекта
if (localPropertiesFile.exists()) {
    localProperties.load(FileInputStream(localPropertiesFile))
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.10" // Обновлено до последней версии
}

android {
    namespace = "com.playeverywhere999.vpn_for_friends_v3"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.playeverywhere999.vpn_for_friends_v3"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Делаем API_SECRET_TOKEN доступным в BuildConfig
        // getStringProperty попытается прочитать значение. Если его нет, будет использовано значение по умолчанию "DEFAULT_TOKEN_PLACEHOLDER"
        // Важно: Если токен не будет найден в local.properties, И вы не предоставите значение по умолчанию здесь,
        // то будет ошибка сборки, если VpnViewModel попытается получить доступ к BuildConfig.API_SECRET_TOKEN.
        // Вы можете сделать значение по умолчанию более очевидной заглушкой или даже вызвать ошибку сборки, если токен не найден.
        val apiSecretToken: String = localProperties.getProperty("API_SECRET_TOKEN") ?: run {
            println("WARNING: API_SECRET_TOKEN not found in local.properties. Using placeholder.")
            "MISSING_TOKEN_CHECK_LOCAL_PROPERTIES" // Это значение попадет в BuildConfig, если токен не найден
        }
        buildConfigField("String", "API_SECRET_TOKEN", "\"$apiSecretToken\"")

    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val ktorVersion = "3.2.3" // Последняя стабильная версия
    implementation("io.ktor:ktor-client-android:$ktorVersion")
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0") // Последняя версия
    implementation("io.ktor:ktor-client-logging:$ktorVersion")
    implementation("androidx.compose.runtime:runtime-livedata:1.7.4") // Обновлено

    implementation("com.wireguard.android:tunnel:1.0.20230706")

    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    coreLibraryDesugaring(libs.android.desugarJdkLibs)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}