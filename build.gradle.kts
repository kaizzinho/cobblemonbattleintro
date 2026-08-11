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
    maven("https://artefacts.cobblemon.com/releases/") { name = "Cobblemon" }
    maven("https://api.modrinth.com/maven") { name = "Modrinth" }
    maven {
        name = "Terraformers"
        url = uri("https://maven.terraformersmc.com/")
    }
}

loom {
    splitEnvironmentSourceSets()

    mods {
        register("cobbletunes") {
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

    // Cobblemon — real dependency, needed running for CobblemonEvents/live battle data.
    modImplementation("com.cobblemon:fabric:${project.property("cobblemon_version")}")

    // Radical Cobblemon Trainers — soft dependency for Pillar 4 (gym leader / E4 /
    // champion tier detection). modCompileOnly so the compiler can see the API surface
    // (TrainerMob, BattleState, TrainerMobData, etc.) without bundling RCT into the
    // final jar. modLocalRuntime keeps them on the classpath when running in dev.
    // CobblemonBattleListener guards every RCT call behind FabricLoader.isModLoaded
    // and a dedicated RctBridge object, so the mod loads cleanly without RCT present.
    modCompileOnly(files("libs/rctapi-fabric-1.21.1-0.15.2-beta.jar"))
    modCompileOnly(files("libs/rctmod-fabric-1.21.1-0.18.1-beta.jar"))
    modLocalRuntime(files("libs/rctapi-fabric-1.21.1-0.15.2-beta.jar"))
    modLocalRuntime(files("libs/rctmod-fabric-1.21.1-0.18.1-beta.jar"))
    modLocalRuntime(files("libs/architectury-13.0.11-fabric.jar"))
    modLocalRuntime(files("libs/ForgeConfigAPIPort-v21.1.6-1.21.1-Fabric.jar"))
    implementation("com.electronwill.night-config:core:3.8.0")
    implementation("com.electronwill.night-config:toml:3.8.0")
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
