plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.souchastnik"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.souchastnik"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-spike"

        ndk { abiFilters += "arm64-v8a" }

        externalNativeBuild {
            cmake {
                // GGML_LLAMAFILE выключен: на Android даёт проблемы сборки,
                // выигрыш для наших коротких промптов несущественный.
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DLLAMA_CURL=OFF",
                    "-DLLAMA_BUILD_TOOLS=OFF",
                    "-DLLAMA_BUILD_EXAMPLES=OFF",
                    "-DLLAMA_BUILD_TESTS=OFF",
                    "-DGGML_LLAMAFILE=OFF",
                    "-DGGML_OPENMP=OFF",
                )
                cppFlags += "-O3"
            }
        }
    }

    // КРИТИЧНО: legacy packaging = нативные библиотеки распаковываются в
    // nativeLibraryDir реальным файлом. Модель лежит там как libmodel-*.so,
    // и мы её mmap-им напрямую по пути. Без этого файла на диске нет и
    // mmap невозможен.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    buildFeatures {
        aidl = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Debug-ключ, чтобы `./gradlew installRelease` работал для спайка.
            // Перед первым публичным релизом заменить на свой keystore:
            // APK с моделью раздаётся через GitHub Releases и F-Droid,
            // и подпись должна быть постоянной, иначе обновления не встанут.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
