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

    /** 在 [dbFile] 创建/打开数据库；目录不存在则创建。已存在的旧库走幂等迁移（0012 C1）。 */
    fun createAt(dbFile: Path): VocabuDatabase {
        dbFile.parent?.createDirectories()
        val existed = java.nio.file.Files.exists(dbFile)
        val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.toAbsolutePath()}")
        enableForeignKeys(driver)
        if (!existed) VocabuDatabase.Schema.create(driver) else migrateLegacy(driver)
        return VocabuDatabase(driver)
    }

    /**
     * 旧库幂等迁移（0012 C1）：现状无 schema 版本机制，已存在库跳过 Schema.create，
     * 加列须手动 ALTER。查 pragma_table_info 缺列则补（旧行按 DEFAULT 0 = false 读，符合派单口径）。
     * TODO 正式 schema 版本迁移机制挂账（0012），列清单增长后此处需换成版本化脚本。
     */
    private fun migrateLegacy(driver: JdbcSqliteDriver) {
        val columns = driver.executeQuery(
            null,
            "PRAGMA table_info(appSettings)",
            { cursor ->
                app.cash.sqldelight.db.QueryResult.Value(buildList {
                    while (cursor.next().value) add(cursor.getString(1))
                })
            },
            0,
        ).value ?: emptyList()
        if ("wordPlayTranslation" !in columns) {
            driver.execute(
                null,
                "ALTER TABLE appSettings ADD COLUMN wordPlayTranslation INTEGER NOT NULL DEFAULT 0",
                0,
            )
        }
    }

    /** 用户数据目录下的数据库文件（PRD §6.3）。 */
    fun userDataDatabase(): VocabuDatabase = createAt(userDataDir().resolve("vocabu.db"))

    private fun enableForeignKeys(driver: JdbcSqliteDriver) {
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
    }

    /** 用户数据目录（PRD §6.3）：数据库与 TTS 缓存共用（ISSUE-008 提为 internal 复用，勿复制实现）。 */
    internal fun userDataDir(): Path {
        val os = System.getProperty("os.name").lowercase()
        val home = Path.of(System.getProperty("user.home"))
        return when {
            os.contains("windows") -> Path.of(System.getenv("APPDATA") ?: home.resolve("AppData/Roaming").toString())
            os.contains("mac") || os.contains("darwin") -> home.resolve("Library/Application Support")
            else -> Path.of(System.getenv("XDG_DATA_HOME") ?: home.resolve(".local/share").toString())
        }.resolve("vocabu")
    }
}
