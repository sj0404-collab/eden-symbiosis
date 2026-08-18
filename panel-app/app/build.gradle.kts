plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The panel pages live in docs/ at the repository root, because GitHub Pages
// serves that directory and the files are edited there. They are copied into
// assets/ at build time rather than duplicated in the app: two copies of a
// 70 KB page drift apart, and the one inside the APK would be the stale one.
val panelPages = listOf(
    "index.html",
    "library.html",
    "desks.html",
    "panel.html",
    "icon.svg",
    "manifest.webmanifest"
)

val docsDir = rootProject.file("../docs")
val panelAssetsDir = layout.buildDirectory.dir("generated/panelAssets")
val panelAssetsSource = rootProject.file("app/src/main/java/dev/symbiosis/panel/PanelAssets.kt")

val copyPanel by tasks.registering(Copy::class) {
    description = "Bundles the panel pages from docs/ into the APK."
    // The destination is the assets root and the subdirectory is set on the
    // source, so this task's output directory is exactly what gets registered
    // as an asset source below. Copying straight into .../panel would make the
    // output directory the panel subfolder, which cannot be handed to
    // assets.srcDir without losing the "panel/" prefix inside the APK.
    from(docsDir) {
        include(panelPages)
        into("panel")
    }
    into(panelAssetsDir)

    // Captured here rather than reached for inside the task actions: touching
    // rootProject at execution time is what breaks the configuration cache.
    val pages = panelPages
    val from = docsDir
    val allowListSource = panelAssetsSource

    // A missing page would otherwise ship as a silent 404 on the phone, and
    // the allow-list in PanelAssets.kt has to agree with what is copied, so
    // both ends are checked here where it costs nothing.
    doFirst {
        val missing = pages.filter { !File(from, it).exists() }
        if (missing.isNotEmpty()) {
            throw GradleException("panel pages missing from ${from.absolutePath}: $missing")
        }
        val declared = Regex("\"([\\w.-]+\\.(?:html|svg|webmanifest))\"")
            .findAll(allowListSource.readText()).map { it.groupValues[1] }.toSet()
        val refused = pages.filter { it !in declared }
        if (refused.isNotEmpty()) {
            throw GradleException(
                "these pages are bundled but PanelAssets.ALLOWED will refuse to serve them: $refused"
            )
        }
    }
}

android {
    namespace = "dev.symbiosis.panel"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.symbiosis.panel"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"
    }

    sourceSets {
        getByName("main") {
            // Registering the task itself, not the bare directory. A plain path
            // tells Gradle where the assets are but nothing about who produces
            // them, so every consumer - mergeReleaseAssets, but also
            // lintVitalAnalyzeRelease and generateReleaseLintVitalReportModel -
            // reads a directory with no declared dependency on the task filling
            // it. Gradle 8 treats that as an error, and the build failed on
            // exactly those two lint tasks. Passing the TaskProvider carries the
            // dependency to all of them automatically.
            assets.srcDir(copyPanel)
        }
    }

    buildTypes {
        release {
            // No shrinking: the whole app is two classes, so R8 would save
            // kilobytes while making a crash report harder to read.
            isMinifyEnabled = false
            // Signed with the debug key on purpose. This is sideloaded from a
            // link, never published to Play, and a release build with no
            // signing config cannot be installed at all.
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
    implementation("androidx.activity:activity-ktx:1.9.2")
}
