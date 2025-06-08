plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose) // Плагин для поддержки Jetpack Compose
}

android {
    namespace = "com.playeverywhere999.vpn_for_friends_v3"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.playeverywhere999.vpn_for_friends_v3"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false // Отключено для отладочных сборок
            isShrinkResources = false // Отключено для отладочных сборок
        }

        release {
            isMinifyEnabled = true // Рекомендуется установить в true для релизных сборок
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Включение Core Library Desugaring для поддержки новых API Java на старых версиях Android
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {

    implementation("androidx.compose.runtime:runtime-livedata:1.6.8")
    implementation("com.wireguard.android:tunnel:1.0.20230706")
    implementation(libs.androidx.lifecycle.viewmodel.ktx) // ViewModel Kotlin Extensions
    implementation(libs.androidx.lifecycle.viewmodel.compose) // Для интеграции ViewModel с Compose


    // Core Kotlin Extensions - расширения Kotlin для Android Framework
    implementation(libs.androidx.core.ktx)

    // Lifecycle - компоненты жизненного цикла (ViewModel, LiveData, LifecycleScope)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)   // Для использования LiveData

    // Coroutines - для асинхронного программирования
    implementation(libs.kotlinx.coroutines.android)

    // Jetpack Compose - основной UI toolkit
    implementation(platform(libs.androidx.compose.bom)) // Bill of Materials для управления версиями Compose
    implementation(libs.androidx.ui) // Базовые компоненты UI
    implementation(libs.androidx.ui.graphics) // Графические примитивы
    implementation(libs.androidx.ui.tooling.preview) // Для предварительного просмотра в Android Studio
    implementation(libs.androidx.material3) // Компоненты Material Design 3
    implementation(libs.androidx.activity.compose) // Для интеграции Compose с Activity

    // WireGuard - библиотека для работы с протоколом WireGuard
    //implementation(libs.wireguard.android.zaneschepke)
    implementation(libs.androidx.appcompat)

    // Зависимость для Core Library Desugaring
    coreLibraryDesugaring(libs.android.desugarJdkLibs)

    // Тестовые зависимости
    testImplementation(libs.junit) // JUnit для юнит-тестов
    androidTestImplementation(libs.androidx.junit) // JUnit для инструментальных тестов
    androidTestImplementation(libs.androidx.espresso.core) // Espresso для UI-тестов
    androidTestImplementation(platform(libs.androidx.compose.bom)) // BOM для тестовых Compose зависимостей
    androidTestImplementation(libs.androidx.ui.test.junit4) // Тестирование Compose UI

    // Отладочные зависимости
    debugImplementation(libs.androidx.ui.tooling) // Инструменты для отладки Compose
    debugImplementation(libs.androidx.ui.test.manifest) // Манифест для тестовых Compose UI
}