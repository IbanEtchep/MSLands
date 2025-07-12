group = "com.github.IbanEtchep"
version = "1.1.0"

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
    compileOnly(libs.io.papermc.paper.paper.api)
    compileOnly(libs.corePaper)
    compileOnly(libs.msguilds)
    compileOnly(libs.com.ghostchu.quickshop.api)
    compileOnly(libs.com.ghostchu.quickshop.bukkit)
    compileOnly(libs.com.arcaniax.headdatabase.api)
    compileOnly(libs.com.github.milkbowl.vaultapi)
    compileOnly(libs.packetevents)

    implementation(libs.folialib)
}

publishing {
    publications.create<MavenPublication>("maven") {
        from(components["java"])
    }
}

tasks.shadowJar {
    archiveClassifier.set("")
    relocate("com.tcoded.folialib", "fr.iban.libs.folialib")
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
