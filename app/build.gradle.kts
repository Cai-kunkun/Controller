plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.arrbrants.controller"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.arrbrants.controller"
        minSdk = 24
        targetSdk = 34
        versionCode = 23
        versionName = "0.8.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("io.noties.markwon:core:4.6.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    // Conscrypt is a native library, so the classifier has to match the host.
    val conscryptClassifier = if (System.getProperty("os.arch") == "aarch64") {
        "linux-aarch_64"
    } else {
        "linux-x86_64"
    }
    testImplementation("org.conscrypt:conscrypt-openjdk:2.7.0:$conscryptClassifier")
}
