import org.gradle.api.tasks.SourceSet
import org.gradle.language.jvm.tasks.ProcessResources

plugins {
    java
    id("fabric-loom") version "1.17.21"
}

version = "0.1.0"
group = "com.letseries.letmelook"

base {
    archivesName.set("letmelook-client")
}

loom {
    mods {
        register("letmelook-client") {
            sourceSet(sourceSets.getByName("main") as SourceSet)
        }
    }
}

dependencies {
    minecraft("com.mojang:minecraft:1.21.11")
    mappings("net.fabricmc:yarn:1.21.11+build.6:v2")
    modImplementation("net.fabricmc:fabric-loader:0.19.5")
    modImplementation("net.fabricmc.fabric-api:fabric-api:0.141.6+1.21.11")

    // share protocol classes; loom `include` bundles them into the mod jar
    include(implementation(project(":letmelook-common"))!!)
    include(implementation("com.google.code.gson:gson:2.11.0")!!)
}

tasks.named<ProcessResources>("processResources") {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand(mapOf("version" to project.version))
    }
}

tasks.withType<JavaCompile> {
    options.release.set(21)
}

java {
    withSourcesJar()
    targetCompatibility = JavaVersion.VERSION_21
    sourceCompatibility = JavaVersion.VERSION_21
}
