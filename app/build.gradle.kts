plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
}

configurations.all {
    resolutionStrategy {
        eachDependency {
            if (requested.group == "org.jetbrains.kotlin") {
                useVersion("1.9.20")
            }
        }
    }
}

android {
    namespace = "com.synapse.localai"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    ndkVersion = "27.2.12479018"
    ndkPath = "/root/android-sdk/ndk/27.2.12479018"

    defaultConfig {
        applicationId = "com.synapse.localai"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "2.1-16k-SDK36-ARM64"

        // 16k page alignment support
        ndk {
            abiFilters.addAll(listOf("arm64-v8a"))
        }

        externalNativeBuild {
            cmake {
                // تفعيل رايات تسريع المعالج الفائقة مع تضمين dotprod و fp16 لحل بطء التكميم والحرارة
                cppFlags("-O3", "-DNDEBUG", "-fvectorize", "-march=armv8.2-a+dotprod+fp16")
                arguments(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DGGML_OPENMP=OFF",
                    "-DGGML_LLAMAFILE=OFF"
                )
            }
        }

    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
            excludes += listOf("**/libc++.so")
            // تصحيح اسم مكتبة الجسر إلى libsynapse_bridge.so وتضمين مكتبات تسريع Vulkan و Llama
            pickFirsts += listOf(
                "**/libc++_shared.so",
                "**/libonnxruntime.so",
                "**/libonnxruntime4j_jni.so",
                "**/libsynapse_bridge.so",
                "**/libggml-vulkan.so",
                "**/libllama.so"
            )
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
            // فرض بناء ملفات C++ بنمط Release حتى عند تجربة نسخة Debug لتفادي خنق السرعة
            externalNativeBuild {
                cmake {
                    arguments("-DCMAKE_BUILD_TYPE=Release")
                }
            }
            ndk {
                debugSymbolLevel = "NONE"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
        freeCompilerArgs += "-Xskip-metadata-version-check"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.5"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.material3)
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.kotlinx.coroutines.android)
    implementation("com.google.ai.client.generativeai:generativeai:0.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.itextpdf:itextg:5.5.10")
    implementation("androidx.datastore:datastore-preferences:1.0.0")
    implementation("com.github.topjohnwu.libsu:core:6.0.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("ru.noties:jlatexmath-android:0.2.0")
    implementation("ru.noties:jlatexmath-android-font-greek:0.2.0")
    testImplementation("junit:junit:4.13.2")
}
