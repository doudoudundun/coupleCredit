import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}

fun localConfig(name: String, defaultValue: String = ""): String {
    return localProperties.getProperty(name, defaultValue)
}

android {
    namespace = "com.example.couplecredit"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.couplecredit"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        multiDexEnabled = true

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "PRIVATE_API_BASE_URL", "\"${localConfig("PRIVATE_API_BASE_URL")}\"")
        buildConfigField("String", "PRIVATE_API_INVITE_CODE", "\"${localConfig("PRIVATE_API_INVITE_CODE")}\"")
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
    
    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt"
            )
        }
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {

    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    
    // 添加Room数据库依赖
    implementation("androidx.room:room-runtime:2.6.1")
    implementation(libs.androidx.room.common.jvm)
    annotationProcessor("androidx.room:room-compiler:2.6.1")
    
    // 添加图表库依赖
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")
    
    // 添加 Gson 依赖
    implementation("com.google.code.gson:gson:2.10.1")
    
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    
    // MySQL JDBC驱动
    // 使用更老版本的MySQL驱动，避免Java 8+ API依赖
    implementation("mysql:mysql-connector-java:5.1.47")
    // 添加Android兼容的数据库连接池
    implementation("com.zaxxer:HikariCP-java7:2.4.13")
    
    // MultiDex支持
    implementation("androidx.multidex:multidex:2.0.1")
    // 在dependencies块中添加
    implementation("com.github.bumptech.glide:glide:4.15.1")
    
    // LocalBroadcastManager支持
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")
    
    // SwipeRefreshLayout支持
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    // Firebase Cloud Messaging
    implementation("com.google.firebase:firebase-messaging:24.1.1")

    // JPush (极光推送) — 本地 SDK
    implementation(files("libs/jpush-android-6.1.0.jar"))
    implementation(files("libs/jcore-android-5.4.0.aar"))
}

if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}