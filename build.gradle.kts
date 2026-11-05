plugins {
    id("net.fabricmc.fabric-loom-remap") version "1.17.21"
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
}

val p = { key: String -> project.property(key) as String }

version = p("mod_version")
group = p("maven_group")
base.archivesName = p("archives_base_name")

repositories {
    maven("https://repo.polyfrost.org/releases") { name = "Polyfrost" }
    mavenCentral()
}

dependencies {
    minecraft("com.mojang:minecraft:${p("minecraft_version")}")
    mappings("net.fabricmc:yarn:${p("yarn_mappings")}:v2")
    modImplementation("net.fabricmc:fabric-loader:${p("loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${p("fabric_api_version")}")
    modImplementation("net.fabricmc:fabric-language-kotlin:${p("fabric_kotlin_version")}")

    implementation("org.polyfrost:polyui:${p("polyui_version")}")
    include("org.polyfrost:polyui:${p("polyui_version")}")

    val lwjgl = p("lwjgl_version")
    implementation("org.lwjgl:lwjgl-nanovg:$lwjgl")
    include("org.lwjgl:lwjgl-nanovg:$lwjgl")
    runtimeOnly("org.lwjgl:lwjgl-nanovg:$lwjgl:natives-windows")
    include("org.lwjgl:lwjgl-nanovg:$lwjgl:natives-windows")
}

loom {
    runs.named("client") {
        (findProperty("quickPlay") as String?)?.let { programArgs("--quickPlaySingleplayer", it.replace('_', ' ')) }
        (findProperty("demo") as String?)?.let { vmArg("-Dlyricvibes.demo=$it") }
    }
}

val bridgeDir = layout.projectDirectory.dir("native/bridge")

val buildBridge = tasks.register<Exec>("buildBridge") {
    group = "build"
    description = "Builds the Windows media bridge"
    workingDir = bridgeDir.asFile
    commandLine("cargo", "build", "--release")
    inputs.dir(bridgeDir.dir("src"))
    inputs.file(bridgeDir.file("Cargo.toml"))
    outputs.file(bridgeDir.file("target/release/lyricvibes-bridge.exe"))
    onlyIf { System.getProperty("os.name").startsWith("Windows") }
}

tasks.processResources {
    dependsOn(buildBridge)
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") { expand("version" to project.version) }
    from(bridgeDir.file("target/release/lyricvibes-bridge.exe")) { into("natives") }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach { options.release = 21 }

kotlin {
    compilerOptions.jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
}

tasks.jar {
    from("LICENSE") { rename { "${it}_lyricvibes" } }
}
