plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.sqldelight)
}

kotlin {
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.sqldelight.runtime)
            implementation(libs.coroutines)
            implementation(libs.datetime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmMain.dependencies {
            implementation(libs.sqldelight.jdbc)
            implementation(libs.libphonenumber)
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
