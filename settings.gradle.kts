pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "MyPersonalAssistent"
include(":app")
include(":core:credentials:api", ":core:credentials:impl")
include(":core:dataBase:api", ":core:dataBase:impl")
include(":core:history:api", ":core:history:impl")
include(":core:llm:api", ":core:llm:impl")
include(":core:memory:api", ":core:memory:impl")
include(":core:agent:api", ":core:agent:impl")
include(":feature:credentials:api", ":feature:credentials:impl")
include(":feature:home:api", ":feature:home:impl")
include(":feature:chat:api", ":feature:chat:impl")
include(":feature:profile:api", ":feature:profile:impl")
