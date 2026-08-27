pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
            isAllowInsecureProtocol = false
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
// 依赖仓库由全局 init 脚本（~/.gradle/init.d/mirrors.gradle，阿里云镜像）统一注入。
// 如需在本项目自定义仓库，取消下面注释：
// dependencyResolutionManagement {
//     repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
//     repositories { google(); mavenCentral() }
// }

rootProject.name = "NUI"
include(":app")
