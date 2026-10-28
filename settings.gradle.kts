pluginManagement {
    repositories {
        // 阿里云镜像放前面：google() / mavenCentral() 指向 dl.google.com 和 repo1.maven.org，
        // 国内经常超时。镜像命中后就不会再走官方源，只是兜底时才回退。
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
    }
}

rootProject.name = "SplashSkip"

include(":app")
include(":core")
include(":rules")
include(":a11y")
