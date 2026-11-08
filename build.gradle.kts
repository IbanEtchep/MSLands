// ArtisanLands, the lands plugin on Artisan (successor of MSLands). The package stays
// fr.iban.lands: Warps and MSBoosts use LandsPlugin, Land, Action, SChunk and GuildLand as they are.
group = "com.github.IbanEtchep"
version = "2.0.0-SNAPSHOT"

java.sourceCompatibility = JavaVersion.VERSION_21

plugins {
    `java-library`
    `maven-publish`
    id("io.github.goooler.shadow") version "8.1.7"
}

val artisanApiVersion: String by project

repositories {
    mavenCentral()
    mavenLocal()
    maven { url = uri("https://repo.papermc.io/repository/maven-public/") }
    maven { url = uri("https://repo.artisanmc.net/releases") }
    maven { url = uri("https://repo.codemc.io/repository/maven-public/") }
    maven { url = uri("https://jitpack.io/") }
    maven { url = uri("https://repo.codemc.io/repository/maven-releases/") }
    maven { url = uri("https://repo.tcoded.com/releases") }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    // Artisan is required and provides its API at runtime: never shaded.
    compileOnly("net.artisanmc:artisan-core-api:$artisanApiVersion")
    // ArtisanGuilds is not published remotely yet:
    //   cd <MSGuilds> && ./gradlew :artisan-guilds-plugin:publishToMavenLocal
    compileOnly("com.github.ibanetchep:artisan-guilds-plugin:2.0.0-SNAPSHOT")
    compileOnly("com.ghostchu:quickshop-api:6.1.0.1")
    compileOnly("com.ghostchu:quickshop-bukkit:6.1.0.1")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1") { isTransitive = false }
    compileOnly("com.github.retrooper:packetevents-spigot:2.6.0")

    implementation("com.tcoded:FoliaLib:0.5.1")
    implementation("net.objecthunter:exp4j:0.4.8")
    implementation("io.github.revxrsal:lamp.common:4.0.0-rc.16")
    implementation("io.github.revxrsal:lamp.bukkit:4.0.0-rc.16")
    implementation("io.github.revxrsal:lamp.brigadier:4.0.0-rc.16")

    testImplementation("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    testImplementation("net.artisanmc:artisan-core-api:$artisanApiVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.1")
    testImplementation("com.h2database:h2:2.3.232")
    testImplementation("org.yaml:snakeyaml:2.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

publishing {
    publications.create<MavenPublication>("maven") {
        from(components["java"])
    }
}

tasks.shadowJar {
    archiveFileName.set("ArtisanLands-${project.version}.jar")
    relocate("revxrsal", "fr.iban.lands.libs.lamp")
    relocate("com.tcoded.folialib", "fr.iban.lands.libs.folialib")
    relocate("net.objecthunter.exp4j", "fr.iban.lands.libs.exp4j")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.processResources {
    val props = mapOf("project_version" to project.version)
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.withType<JavaCompile>() {
    options.encoding = "UTF-8"
}

tasks.withType<Javadoc>() {
    options.encoding = "UTF-8"
}
