package cn.vocabu.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import cn.vocabu.db.VocabuDatabase
import java.nio.file.Path
import kotlin.io.path.createDirectories

/**
 * 数据库装配（ISSUE-002）：schema 创建、外键开关、文件路径。
 * 内存库供测试；文件库放用户数据目录（PRD §6.3）。
 */
object VocabuDatabaseFactory {

    fun createInMemory(): VocabuDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        enableForeignKeys(driver)
        VocabuDatabase.Schema.create(driver)
        return VocabuDatabase(driver)
    }

    /** 在 [dbFile] 创建/打开数据库；目录不存在则创建。 */
    fun createAt(dbFile: Path): VocabuDatabase {
        dbFile.parent?.createDirectories()
        val existed = java.nio.file.Files.exists(dbFile)
        val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.toAbsolutePath()}")
        enableForeignKeys(driver)
        if (!existed) VocabuDatabase.Schema.create(driver)
        return VocabuDatabase(driver)
    }

    /** 用户数据目录下的数据库文件（PRD §6.3）。 */
    fun userDataDatabase(): VocabuDatabase = createAt(userDataDir().resolve("vocabu.db"))

    private fun enableForeignKeys(driver: JdbcSqliteDriver) {
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
    }

    private fun userDataDir(): Path {
        val os = System.getProperty("os.name").lowercase()
        val home = Path.of(System.getProperty("user.home"))
        return when {
            os.contains("windows") -> Path.of(System.getenv("APPDATA") ?: home.resolve("AppData/Roaming").toString())
            os.contains("mac") || os.contains("darwin") -> home.resolve("Library/Application Support")
            else -> Path.of(System.getenv("XDG_DATA_HOME") ?: home.resolve(".local/share").toString())
        }.resolve("vocabu")
    }
}
