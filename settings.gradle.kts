pluginManagement {
  repositories {
    // KSP's plugin marker (com.google.devtools.ksp) is published to Maven Central only;
    // google()'s content filter would claim the com.google.* group and 404. List it first.
    exclusiveContent {
      forRepository {
        mavenCentral()
      }
      filter {
        includeGroup("com.google.devtools.ksp")
      }
    }
    google {
      content {
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("androidx.*")
      }
    }
    mavenCentral()
    gradlePluginPortal()
  }
}

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
  }
}

rootProject.name = "OmniPDF"

include(":app")
