plugins {
    id("com.android.application")
}

android {
    namespace = "com.naate.olleetasker"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.naate.olleetasker"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

base {
    archivesName = "OlleeWatchForTasker"
}

dependencies {
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
}
