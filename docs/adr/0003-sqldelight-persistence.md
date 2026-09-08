# 持久层选型 SQLDelight，.sq 文件置于 :app:desktopApp

本地存储定为 SQLite（PRD 1.4）。在 SQLDelight、Room KMP、Exposed、裸 JDBC 之间选择 SQLDelight：SQL 优先的模型与本应用查询密集的特征（今日词表生成、到期复习捞取、去重/搜索）匹配，且 KMP 原生支持为多端化留门——MVP 阶段 `.sq` 文件与 JDBC 驱动放 `:app:desktopApp`，生成的查询经仓库实现类实现 `:core` 的仓库接口；多端化时只需迁移持久层模块，领域层与 UI 层无感。被否掉的备选：Exposed（JVM-only，焊死多端）、Room KMP（Android 仪式负担，对本域无优势）、裸 JDBC（手写映射与迁移，错误率换不来收益）。
