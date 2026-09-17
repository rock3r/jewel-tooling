plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

rootProject.name = "jewel-tooling"

include(":e2e:driver-plugin", ":e2e:runner")

include(":test-fixtures:compiler-metadata")

include(":recording", ":recording-compose")
