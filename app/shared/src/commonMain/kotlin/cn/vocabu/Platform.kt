package cn.vocabu

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform