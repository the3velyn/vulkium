plugins {
    id("java-library")
    id("idea")
    id("net.fabricmc.fabric-loom") version ("1.16.1")
}

// --- Version constants ----------------------------------------------------
// Centralized here (not in a buildSrc/BuildConfig) because the project is a
// single-module Fabric mod; no multi-platform split.

val modVersion = "0.1.0"
val minecraftVersion = "26.2-snapshot-3"
val fabricLoaderVersion = "0.19.1"
val fabricApiVersion = "0.146.0+26.2"
val lwjglVersion = "3.4.1"
val jdkVersion = 25

// --- Project identity -----------------------------------------------------

group = "me.cortex.vulkium"

version = buildString {
    append(modVersion)
    if (!project.hasProperty("build.release")) {
        append("-SNAPSHOT")
    }
    append("+mc").append(minecraftVersion)
    if (!project.hasProperty("build.release")) {
        val ghRun = System.getenv("GITHUB_RUN_NUMBER")
        if (ghRun != null) append("-build.").append(ghRun) else append("-local")
    }
}

base {
    archivesName = "vulkium"
}

java.toolchain.languageVersion = JavaLanguageVersion.of(jdkVersion)

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(jdkVersion)
}

tasks.withType<GenerateModuleMetadata>().configureEach { enabled = false }

// --- Repositories & dependencies -----------------------------------------

repositories {
    mavenLocal()
    exclusiveContent {
        forRepository {
            maven {
                name = "Modrinth"
                url = uri("https://api.modrinth.com/maven")
            }
        }
        filter { includeGroup("maven.modrinth") }
    }
}

dependencies {
    // Minecraft 26.1+ is deobfuscated by default; no mappings block required.
    minecraft("com.mojang:minecraft:$minecraftVersion")

    // MixinExtras is used for @WrapOperation / @ModifyExpressionValue where @Redirect is too brittle.
    compileOnly("io.github.llamalad7:mixinextras-common:0.5.0")
    annotationProcessor("io.github.llamalad7:mixinextras-common:0.5.0")

    compileOnly("net.fabricmc:sponge-mixin:0.13.2+mixin.0.8.5")
    implementation("net.fabricmc:fabric-loader:$fabricLoaderVersion")

    // Vulkan + VMA. These are actually part of MC 26.2's runtime library set, so compileOnly is
    // enough — natives load from Mojang's own classpath. We pin 3.4.1 to match Mojang's bundle.
    compileOnly("org.lwjgl:lwjgl-vulkan:$lwjglVersion")
    compileOnly("org.lwjgl:lwjgl-vma:$lwjglVersion")

    // Fabric API modules — request only what we actually use.
    fun embed(name: String) {
        val module = fabricApi.module(name, fabricApiVersion)
        implementation(module)
        include(module)
    }
    embed("fabric-api-base")
    embed("fabric-lifecycle-events-v1")
    embed("fabric-resource-loader-v0")
    embed("fabric-rendering-v1")
}

// --- Loom configuration ---------------------------------------------------

loom {
    accessWidenerPath.set(file("src/main/resources/vulkium.accesswidener"))
    mixin { useLegacyMixinAp = false }

    runs {
        named("client") {
            client()
            configName = "Fabric/Client"
            appendProjectPathToConfigName = false
            ideConfigGenerated(true)
            runDir("run")
        }
    }
}

// --- Resource processing: expand ${version} in fabric.mod.json -----------

tasks.processResources {
    inputs.property("version", version)
    filesMatching("fabric.mod.json") {
        expand(mapOf("version" to inputs.properties["version"]))
    }
}

tasks.jar {
    destinationDirectory.set(layout.buildDirectory.dir("mods"))
}
