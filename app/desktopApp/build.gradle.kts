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
    implementation(libs.compose.material3) // PoC 直引 Expressive API（shared 为 implementation 不传递）

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

// M3 Expressive PoC 独立入口（零侵入生产 MainKt；--PpocShot=/path 时跑完自动截图退出）
tasks.register<JavaExec>("runExpressivePoc") {
    group = "compose desktop"
    description = "Run the M3 Expressive PoC showcase"
    mainClass.set("cn.vocabu.poc.ExpressivePocKt")
    classpath = sourceSets["main"].runtimeClasspath
    if (project.hasProperty("pocShot")) {
        args("--screenshot", project.property("pocShot").toString())
    }
    // 无头/容器环境 AWT X11 库兜底（-PpocX11Lib=路径），正常桌面环境无需
    if (project.hasProperty("pocX11Lib")) {
        environment("LD_LIBRARY_PATH", project.property("pocX11Lib").toString())
    }
    // 无头环境指定 X 显示（-PpocDisplay=:98）：JavaExec 继承 daemon 环境，CLI 前缀 DISPLAY 传不进来
    if (project.hasProperty("pocDisplay")) {
        environment("DISPLAY", project.property("pocDisplay").toString())
    }
}

// 渲染归因探针（诊断专用）：--PpocProbe=变体名 选择探针场景
tasks.register<JavaExec>("runExpressiveProbe") {
    group = "compose desktop"
    description = "Run the PoC rendering attribution probe"
    mainClass.set("cn.vocabu.poc.ExpressiveProbeKt")
    classpath = sourceSets["main"].runtimeClasspath
    if (project.hasProperty("pocShot")) {
        args("--screenshot", project.property("pocShot").toString())
    }
    if (project.hasProperty("pocProbe")) {
        args("--probe", project.property("pocProbe").toString())
    }
    if (project.hasProperty("pocX11Lib")) {
        environment("LD_LIBRARY_PATH", project.property("pocX11Lib").toString())
    }
    if (project.hasProperty("pocDisplay")) {
        environment("DISPLAY", project.property("pocDisplay").toString())
    }
}
