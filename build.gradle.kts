plugins {
    id("java-library")
    id("idea")
    id("net.fabricmc.fabric-loom") version ("1.16.1")
}

// --- Version constants ----------------------------------------------------
// Centralized here (not in a buildSrc/BuildConfig) because the project is a
// single-module Fabric mod; no multi-platform split.

val modVersion = "0.1.0"
val minecraftVersion = "26.2"
val fabricLoaderVersion = "0.19.1"
val fabricApiVersion = "0.153.0+26.2"
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
    // shaderc for runtime GLSL→SPIR-V compile of mesh/task stages Mojang's GlslCompiler
    // doesn't cover. lwjgl-shaderc is already on MC 26.2's runtime classpath.
    compileOnly("org.lwjgl:lwjgl-shaderc:$lwjglVersion")

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

    // Sodium (compile + dev runtime, NOT bundled — user provides their own at install).
    // This branch (mc-26.2-sodium) targets Sodium as a hard dependency; standalone edition
    // on mc-26.2 instead declares `breaks.sodium` in fabric.mod.json. Sodium ships with
    // official mappings (no Yarn remap needed), so plain compileOnly+runtimeOnly works —
    // we don't need Loom's mod* configurations (which aren't wired up in this build setup
    // anyway). compileOnly puts Sodium's types on the classpath for our mixins; runtimeOnly
    // puts the actual mod into the dev runClient's mods directory.
    compileOnly("maven.modrinth:sodium:mc26.2-0.9.1-beta.2-fabric")
    runtimeOnly("maven.modrinth:sodium:mc26.2-0.9.1-beta.2-fabric")
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
            // DEV_ONLY_QUICKPLAY — remove before release.
            // Skip title → singleplayer → worldlist clicks when testing over remote SSH
            // (KDE Wayland capture portal flakes and ydotool mouse-move is unreliable on
            // this host). World name matches run/saves/test/. Override with
            // `-PquickPlay=<other>` or unset via `-PquickPlay=off`.
            // Window size is set by scripts/vulkium-test.sh via --args, not here.
            val quickPlay = (project.findProperty("quickPlay") as? String) ?: "test"
            if (quickPlay != "off") {
                programArgs("--quickPlaySingleplayer", quickPlay)
            }
            // END DEV_ONLY_QUICKPLAY
        }
    }
}

// --- Resource processing: expand ${version} in fabric.mod.json -----------

tasks.processResources {
    inputs.property("version", version)
    filesMatching("fabric.mod.json") {
        expand(mapOf("version" to inputs.properties["version"]))
    }
    // Non-asset tracker files that live alongside shaders for human reference — keep them
    // out of the jar so MC's resource-pack scanner doesn't warn about them each launch.
    exclude("assets/vulkium/shaders/SHADERS_TODO.md")
}

tasks.jar {
    destinationDirectory.set(layout.buildDirectory.dir("mods"))
}
