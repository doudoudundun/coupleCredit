plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.couplecredit"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.couplecredit"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {

    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation("com.transsion.api:widgets:16.1.0.2")
//    implementation("com.transsion.api:widgetslistitemlayout:16.1.0.2")
//    implementation("com.transsion.api:widgetPerGuide:16.1.0.2")
//    implementation("com.transsion.api:widgetsrecanimation:16.1.0.2")
//    implementation("com.transsion.api:widgetsThemes:16.1.0.2")
//    implementation("com.transsion.api:widgetBottomSheet:16.1.0.2")
//    implementation("com.transsion.api:widgetsShareAnimation:16.1.0.2")

}