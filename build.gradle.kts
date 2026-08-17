plugins {
    kotlin("jvm") version "2.1.21"
    id("fabric-loom") version "1.10.1"
    `maven-publish`
}

version = project.property("mod_version") as String
group = project.property("maven_group") as String

base {
    archivesName.set(project.property("archives_base_name") as String)
}

repositories {
    mavenCentral()
    maven("https://artefacts.cobblemon.com/releases/") {
        name = "Cobblemon"
    }
    maven {
        name = "Terraformers"
        url = uri("https://maven.terraformersmc.com/")
    }
}

loom {
    splitEnvironmentSourceSets()

    mods {
        register("battleintroduction") {
            sourceSet("main")
            sourceSet("client")
        }
    }
}

sourceSets {
    val client = getByName("client")
    client.java.srcDirs("src/client/java")
}

dependencies {
    minecraft("com.mojang:minecraft:${project.property("minecraft_version")}")
    mappings("net.fabricmc:yarn:${project.property("yarn_mappings")}:v2")

    modImplementation("net.fabricmc:fabric-loader:${project.property("loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${project.property("fabric_version")}")
    modImplementation("net.fabricmc:fabric-language-kotlin:${project.property("fabric_kotlin_version")}")
    modImplementation("com.cobblemon:fabric:${project.property("cobblemon_version")}")

    modImplementation("com.terraformersmc:modmenu:11.0.4")
}

java {
    withSourcesJar()
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    jvmToolchain(21)
}

tasks.processResources {
    inputs.property("version", project.version)

    filesMatching("fabric.mod.json") {
        expand(mutableMapOf("version" to project.version))
    }
}

tasks.withType<Jar>().configureEach {
    from(rootProject.file("LICENSE")) {
        rename { "LICENSE_${project.property("archives_base_name")}" }
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
