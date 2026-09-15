import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.sqldelight)
}

dependencies {
    implementation(project(":app:shared"))
    implementation(project(":core"))

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)

    implementation(libs.compose.uiToolingPreview)

    implementation(libs.sqldelight.runtime)
    implementation(libs.sqldelight.sqliteDriver)
    implementation(libs.poi.ooxml)
    implementation(libs.jlayer) // MP3 解码（ISSUE-008 spike 选型：支持 MPEG-1/2/2.5 L3 全矩阵）
    testImplementation(libs.kotlin.test)
}

sqldelight {
    databases {
        create("VocabuDatabase") {
            packageName.set("cn.vocabu.db")
            verifyMigrations.set(true)
        }
    }
}

compose.desktop {
    application {
        mainClass = "cn.vocabu.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "cn.vocabu"
            packageVersion = "1.0.0"
        }
    }
}
