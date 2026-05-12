group = "com.github.IbanEtchep"
version = "1.1.1"

java.sourceCompatibility = JavaVersion.VERSION_21

plugins {
    `java-library`
    `maven-publish`
    id("io.github.goooler.shadow") version "8.1.7"
}

repositories {
    mavenLocal()
    maven { url = uri("https://repo.papermc.io/repository/maven-public/") }
    maven { url = uri("https://oss.sonatype.org/content/groups/public/") }
    maven { url = uri("https://repo.codemc.io/repository/maven-public/") }
    maven { url = uri("https://mvn.intellectualsites.com/content/repositories/thirdparty/") }
    maven { url = uri("https://repo.alessiodp.com/releases/") }
    maven { url = uri("https://jitpack.io/") }
    maven { url = uri("https://repo.maven.apache.org/maven2/") }
    maven { url = uri("https://repo.codemc.io/repository/maven-releases/") }
    maven { url = uri("https://repo.tcoded.com/releases") }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("com.github.IbanEtchep.MSCore:core-paper:1.1.1")
    compileOnly("com.github.IbanEtchep:MSGuilds:1.1.0")
    compileOnly("com.ghostchu:quickshop-api:6.1.0.1")
    compileOnly("com.ghostchu:quickshop-bukkit:6.1.0.1")
    compileOnly("com.arcaniax:HeadDatabase-API:1.3.2")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1")
    compileOnly("com.github.retrooper:packetevents-spigot:2.6.0")

    implementation("com.tcoded:FoliaLib:0.5.1")
    implementation("net.objecthunter:exp4j:0.4.8")
    implementation("io.github.revxrsal:lamp.common:4.0.0-rc.16")
    implementation("io.github.revxrsal:lamp.bukkit:4.0.0-rc.16")
    implementation("io.github.revxrsal:lamp.brigadier:4.0.0-rc.16")
}

publishing {
    publications.create<MavenPublication>("maven") {
        from(components["java"])
    }
}

tasks.shadowJar {
    archiveClassifier.set("")
    relocate("revxrsal", "fr.iban.lands.libs.lamp")
    relocate("com.tcoded.folialib", "fr.iban.libs.folialib")
    relocate("net.objecthunter.exp4j", "fr.iban.libs.exp4j")
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand(
            "project_version" to project.version
        )
    }
}

tasks.withType<JavaCompile>() {
    options.encoding = "UTF-8"
}

tasks.withType<Javadoc>() {
    options.encoding = "UTF-8"
}
