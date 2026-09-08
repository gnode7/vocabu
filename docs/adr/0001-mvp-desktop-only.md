# MVP 只交付桌面端，其余平台模块保留但冻结

PRD 1.4 将 MVP 范围定为桌面端（Compose Desktop，JVM 目标）。仓库脚手架包含 iOS/Android/Web/Server 五端模块，但 MVP 阶段 `androidApp`、`webApp`、`iosApp`、`server` 四个模块保留在构建中、不投入任何业务代码。原因：PRD 的交互设计依赖桌面假设（鼠标悬停高亮、空格播报、Q 键偷看、数字键评级），移动端无键盘会推翻这些交互；先用桌面版验证学习闭环，多端化是验证后的独立决策。
