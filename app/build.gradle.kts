plugins { id("com.android.application") }
android {
    namespace = "dev.codex.bedrockpocket"
    compileSdk = 35
    defaultConfig { applicationId = "dev.codex.bedrockpocket"; minSdk = 26; targetSdk = 28; versionCode = 62; versionName = "0.8.43" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation(files("libs/commons-compress-1.28.0.jar"))
    implementation(files("libs/commons-io-2.20.0.jar"))
    implementation(files("libs/commons-lang3-3.17.0.jar"))
}
