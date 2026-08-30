plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.protobuf")
}

import java.util.Properties

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.meshkachoutility"
    compileSdk = 35

    signingConfigs {
        if (localProps.getProperty("upload.storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(localProps.getProperty("upload.storeFile"))
                storePassword = localProps.getProperty("upload.storePassword")
                keyAlias = localProps.getProperty("upload.keyAlias")
                keyPassword = localProps.getProperty("upload.keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "com.meshkachoutility"
        minSdk = 26
        targetSdk = 35
        versionCode = 16
        versionName = "1.2.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
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

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.1"
    }
    generateProtoTasks {
        all().configureEach {
            builtins {
                create("java") {
                    option("lite")
                }
                create("kotlin") {
                    option("lite")
                }
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    
    // Coroutines for thread-safe asynchronous serial reader loops
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Protobuf Lite dependencies
    implementation("com.google.protobuf:protobuf-javalite:3.25.1")
    implementation("com.google.protobuf:protobuf-kotlin-lite:3.25.1")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
