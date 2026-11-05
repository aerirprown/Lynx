plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.sqldelight)
}

kotlin {
    jvm()
    android {
        namespace = "app.lynx.prm.shared"
        compileSdk = 37
        minSdk = 29
    }

    sourceSets {
        val jvmAndroidMain = create("jvmAndroidMain") {
            dependsOn(getByName("commonMain"))
            dependencies {
                implementation(libs.libphonenumber)
            }
        }
        getByName("jvmMain").dependsOn(jvmAndroidMain)
        getByName("androidMain").dependsOn(jvmAndroidMain)

        commonMain.dependencies {
            implementation(libs.sqldelight.runtime)
            implementation(libs.coroutines)
            implementation(libs.datetime)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmMain.dependencies {
            implementation(libs.sqldelight.jdbc)
        }
        androidMain.dependencies {
            implementation(libs.sqldelight.android)
            implementation(libs.documentfile)
        }
    }
}

sqldelight {
    databases {
        create("LynxDatabase") {
            packageName.set("app.lynx.prm.db")
            dialect("app.cash.sqldelight:sqlite-3-38-dialect:2.4.1")
        }
    }
}
