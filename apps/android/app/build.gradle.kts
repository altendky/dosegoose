plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

val repositoryRoot = rootProject.layout.projectDirectory.dir("../..").asFile
val generatedUniFFIKotlin = layout.buildDirectory.dir("generated/uniffi/kotlin")
val generatedUniFFIJni = layout.buildDirectory.dir("generated/uniffi/jniLibs")
val rustHostTarget = layout.buildDirectory.dir("rust-host")
val rustBindgenTarget = layout.buildDirectory.dir("rust-bindgen")
val rustAndroidTarget = layout.buildDirectory.dir("rust-android-target")
val rustInputs = files(
    repositoryRoot.resolve("Cargo.toml"),
    repositoryRoot.resolve("Cargo.lock"),
    repositoryRoot.resolve("rust-toolchain.toml"),
    fileTree(repositoryRoot.resolve("src")) { include("**/*.rs") },
    fileTree(repositoryRoot.resolve("crates/dosegoose-uniffi")) {
        include("Cargo.toml", "uniffi.toml", "src/**/*.rs")
    },
    fileTree(repositoryRoot.resolve("tools/uniffi-bindgen")) {
        include("Cargo.toml", "src/**/*.rs")
    },
)

val buildRustHost = tasks.register<Exec>("buildRustHost") {
    workingDir(repositoryRoot)
    commandLine(
        "cargo",
        "build",
        "--locked",
        "-p",
        "dosegoose-uniffi",
        "--target-dir",
        rustHostTarget.get().asFile.absolutePath,
    )
    inputs.files(rustInputs)
    outputs.file(rustHostTarget.map { it.file("debug/libdosegoose_uniffi.so") })
}

val generateUniFFIKotlin = tasks.register<Exec>("generateUniFFIKotlin") {
    dependsOn(buildRustHost)
    workingDir(repositoryRoot)
    commandLine(
        "cargo",
        "run",
        "--locked",
        "-p",
        "dosegoose-uniffi-bindgen",
        "--target-dir",
        rustBindgenTarget.get().asFile.absolutePath,
        "--",
        "generate",
        rustHostTarget.get().file("debug/libdosegoose_uniffi.so").asFile.absolutePath,
        "--language",
        "kotlin",
        "--out-dir",
        generatedUniFFIKotlin.get().asFile.absolutePath,
        "--no-format",
    )
    inputs.files(rustInputs)
    outputs.dir(generatedUniFFIKotlin)
}

val buildRustAndroid = tasks.register<Exec>("buildRustAndroid") {
    workingDir(repositoryRoot)
    commandLine(
        "cargo",
        "ndk",
        "--platform",
        "26",
        "--target",
        "arm64-v8a",
        "--target",
        "armeabi-v7a",
        "--target",
        "x86",
        "--target",
        "x86_64",
        "--output-dir",
        generatedUniFFIJni.get().asFile.absolutePath,
        "build",
        "--locked",
        "-p",
        "dosegoose-uniffi",
        "--target-dir",
        rustAndroidTarget.get().asFile.absolutePath,
    )
    inputs.files(rustInputs)
    outputs.dir(generatedUniFFIJni)
}

android {
    namespace = "net.fstab.dosegoose"
    compileSdk = 37
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "net.fstab.dosegoose"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    buildFeatures {
        compose = true
    }

    sourceSets {
        getByName("main") {
            kotlin.directories.add(generatedUniFFIKotlin.get().asFile.absolutePath)
            jniLibs.directories.add(generatedUniFFIJni.get().asFile.absolutePath)
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

tasks.named("preBuild") {
    dependsOn(generateUniFFIKotlin, buildRustAndroid)
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.room.runtime)
    implementation(libs.datastore.preferences)
    implementation(libs.play.services.location)
    // Play services still requests Fragment 1.1.0 transitively; Activity Result needs 1.3+.
    implementation(libs.fragment)
    // Room 2.8.5's migration bundle serializers are compiled against 1.8.1.
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.jna) {
        artifact {
            type = "aar"
        }
    }
    ksp(libs.room.compiler)

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.room.testing)
}
