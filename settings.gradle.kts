// Configuración global del proyecto: de dónde se descargan los plugins y las librerías.
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

// Cambia este nombre si renombras la app (también en strings.xml, namespace y applicationId).
rootProject.name = "BajaYa"
include(":app")
