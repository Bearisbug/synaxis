// 临时镜像：仅本次构建生效，不写入仓库或全局配置
settingsEvaluated {
    pluginManagement {
        repositories {
            maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
            maven { url = uri("https://maven.aliyun.com/repository/central") }
            gradlePluginPortal()
            mavenCentral()
        }
    }
    dependencyResolutionManagement {
        repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
        repositories {
            maven { url = uri("https://maven.aliyun.com/repository/central") }
            maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
            mavenCentral()
            maven { url = uri("https://central.sonatype.com/repository/maven-snapshots/") }
        }
    }
}
