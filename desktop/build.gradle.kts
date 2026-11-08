import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

kotlin {
    jvmToolchain(17)
    // codice condiviso con l'app Android
    sourceSets["main"].kotlin.srcDirs("src/main/kotlin", "../shared/src")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")
    implementation("org.json:json:20240303")
    implementation("org.apache.commons:commons-compress:1.27.1")
    testImplementation(kotlin("test"))
    @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
    testImplementation(compose.uiTest)
    testImplementation(compose.desktop.uiTestJUnit4)
}

tasks.withType<Test> { testLogging { showStandardStreams = true } }

val build = (System.getenv("VERSION_CODE") ?: "1").toInt()

compose.desktop {
    application {
        mainClass = "com.marco.mlmeteo.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "MLMeteo"
            packageVersion = "1.0.$build"
            description = "Meteo con molte fonti, radar e mappe ad alta risoluzione"
            vendor = "MLapps"
            modules("java.net.http", "jdk.crypto.ec", "java.prefs")
            windows {
                iconFile.set(project.file("icons/mlmeteo.ico"))
                menu = true
                menuGroup = "MLapps"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "6f1c2c1e-7f4b-4f53-9b0a-3d1d6c0a8e21"
            }
        }
    }
}
