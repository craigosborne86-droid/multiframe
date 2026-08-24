pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Multiframe"
include(":app")

// The project lives on an exFAT volume, which creates AppleDouble "._" sidecar
// files whenever extended attributes are written. AGP walks the build tree and
// chokes on them ("._drawable is not a directory"), so build output is kept on
// the internal APFS volume instead. Sources stay put.
gradle.beforeProject {
    val external = System.getProperty("user.home") +
        "/Library/Caches/MultiframeBuild/" + project.path.replace(':', '_').trim('_')
    project.layout.buildDirectory.set(java.io.File(external))
}
