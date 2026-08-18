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
            // The directory is named here; who fills it is declared below.
            //
            // Handing srcDir() the TaskProvider instead looked tidier and did
            // build - but AGP resolved the asset directory without ever running
            // the task, so the APK shipped with no assets/panel at all and the
            // verification step caught an empty panel. Naming the path and
            // wiring the dependency explicitly is what actually works.
            assets.srcDir(panelAssetsDir)
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

// Everything that reads the generated assets directory must run after the task
// that fills it. Two groups need this, and missing either one is fatal in a
// different way:
//
//   *Assets   - mergeReleaseAssets and friends. Without this the pages are
//               simply absent from the APK.
//   lint*     - lintVitalAnalyzeRelease, generateReleaseLintVitalReportModel.
//               These only read the directory, but Gradle 8 fails the build
//               outright when a task consumes another's output undeclared.
tasks.matching {
    it.name.startsWith("merge") && it.name.endsWith("Assets") ||
        it.name.startsWith("lint") ||
        it.name.startsWith("generate") && it.name.contains("Lint") ||
        it.name.startsWith("package") && it.name.endsWith("Assets")
}.configureEach { dependsOn(copyPanel) }

// Last line of defence, inside the build itself.
//
// The APK has already been produced at this point, so this catches the exact
// failure seen on CI: a build that succeeds while assets/panel is missing,
// leaving an app that shows "страница не входит в сборку" on the phone. Better
// to fail the build than to publish that.
tasks.matching { it.name matches Regex("package(Debug|Release)") }.configureEach {
    doLast {
        val apks = outputs.files.asFileTree.matching { include("**/*.apk") }.files
        for (apk in apks) {
            val inside = java.util.zip.ZipFile(apk).use { zip ->
                zip.entries().asSequence().map { it.name }
                    .filter { it.startsWith("assets/panel/") }.toSet()
            }
            val missing = panelPages.filter { "assets/panel/$it" !in inside }
            if (missing.isNotEmpty()) {
                throw GradleException(
                    "${apk.name} is missing ${missing.size} panel page(s): $missing\n" +
                        "found in the APK: ${inside.sorted()}"
                )
            }
            logger.lifecycle("${apk.name}: all ${panelPages.size} panel pages packaged")
        }
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.9.2")
}
