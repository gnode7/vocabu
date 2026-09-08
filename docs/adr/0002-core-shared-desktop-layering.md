# 三层分层：领域在 :core，UI 在 :app:shared，装配在 :app:desktopApp

MVP 只做桌面端（ADR-0001），但代码按「desktopApp → shared → core」分层组织：`:core` 是纯 Kotlin 领域层（SM-2、答案判定、导入解析、用例、仓库接口），无 Compose 依赖，保证核心逻辑可在 jvmTest 秒级单测；`:app:shared` 承载全部 Compose UI（commonMain），为后续多端留路；`:app:desktopApp` 只做平台装配——SQLite 驱动、有道 TTS 实现、文件选择器、窗口入口，并以手动构造注入组装依赖图（不引 DI 框架）。代价是桌面特有能力必须走「core 定义接口 + desktopApp 实现」的接缝；被否掉的备选是「全部塞 desktopApp」——原型期更快，但启多端时需要大搬家。
