plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.marco.mlmeteo"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.marco.mlmeteo"
        minSdk = 26
        targetSdk = 35
        // In CI il numero cresce a ogni build (variabile VERSION_CODE)
        val build = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "1.0.$build"
    }

    // Chiave fissa inclusa nel progetto: tutte le build (anche da GitHub Actions)
    // hanno la stessa firma, quindi l'APK si aggiorna sopra la versione già installata.
    // La chiave di firma non è nel repository: arriva dai Secrets di GitHub (variabili d'ambiente).
    // Senza chiave (build locale) si usa la firma di debug di Android.
    val ksFile = System.getenv("KEYSTORE_FILE")
    val hasKey = !ksFile.isNullOrBlank() && file(ksFile).exists()
    signingConfigs {
        if (hasKey) {
            create("release") {
                storeFile = file(ksFile!!)
                storePassword = System.getenv("KEYSTORE_PASSWORD")?.trim()
                keyAlias = (System.getenv("KEY_ALIAS") ?: "mlmeteo").trim().lowercase()
                keyPassword = System.getenv("KEY_PASSWORD")?.trim()
            }
        }
    }
    val signing = if (hasKey) signingConfigs.getByName("release") else signingConfigs.getByName("debug")

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signing
        }
        debug {
            signingConfig = signing
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    // codice condiviso con la versione per Windows
    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/java", "../shared/src")
        }
    }
    buildFeatures {
        compose = true
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/LICENSE*", "/META-INF/NOTICE*", "/META-INF/DEPENDENCIES")
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("com.github.marco90-cyber:MLTheme:1.0.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.apache.commons:commons-compress:1.27.1")
}
