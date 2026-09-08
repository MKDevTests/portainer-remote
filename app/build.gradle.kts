import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/*
 * Identifiants de signature. Le fichier est produit par signing/new-keystore.ps1
 * et ignore par git. Son absence n'est pas une erreur : elle produit une build
 * release non signee, ce qui permet a n'importe qui de compiler le projet sans
 * detenir la cle.
 */
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "dev.mkdev.portainerremote"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.mkdev.portainerremote"
        minSdk = 26
        targetSdk = 36
        versionCode = 14
        versionName = "1.3.0"

        // Depot consulte pour les mises a jour. Un fork ne change que cette
        // ligne : rien d'autre dans le code ne nomme le depot.
        buildConfigField("String", "UPDATE_REPO", "\"MKDevTests/portainer-remote\"")

        // Racine de l'API des releases. Separee du depot pour qu'une instance
        // GitHub Enterprise reste atteignable sans toucher au code.
        buildConfigField("String", "UPDATE_API", "\"https://api.github.com\"")
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")

                // v2 suffit a partir d'Android 7, mais seul v3 porte la
                // rotation de cle : sans lui, remplacer la cle un jour
                // imposerait de desinstaller l'application.
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            // Pas d'obfuscation : kotlinx.serialization et Glance reposent sur
            // la reflexion, et une regle manquante ne casserait qu'a l'execution.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.androidx.work.runtime.ktx)
}
