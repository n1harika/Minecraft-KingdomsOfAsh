import java.security.MessageDigest

plugins {
    java
}

group = "com.kingdomsofash"
version = "1.0.0"

val paperVersion: String by project

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:$paperVersion")
    testImplementation("io.papermc.paper:paper-api:$paperVersion")
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
    options.compilerArgs.add("-Xlint:all,-serial,-processing,-deprecation,-rawtypes,-deprecation,-rawtypes")
}

tasks.processResources {
    val props = mapOf("version" to project.version, "apiVersion" to "26.3")
    inputs.properties(props)
    filesMatching("plugin.yml") { expand(props) }
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped"); showStandardStreams = false }
}

tasks.jar {
    archiveFileName = "KingdomsOfAsh-${project.version}.jar"
}

// ---- Resource pack: zip + sha1 so server owners can host it -------------------------------
val packZip = tasks.register<Zip>("resourcePack") {
    group = "kingdoms of ash"
    description = "Builds KingdomsOfAsh-ResourcePack.zip from /resourcepack"
    from("resourcepack")
    archiveFileName = "KingdomsOfAsh-ResourcePack.zip"
    destinationDirectory = layout.buildDirectory.dir("resourcepack")
    isReproducibleFileOrder = true
    isPreserveFileTimestamps = false
    doLast {
        val f = archiveFile.get().asFile
        val sha = MessageDigest.getInstance("SHA-1").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
        File(f.parentFile, "KingdomsOfAsh-ResourcePack.sha1").writeText(sha + "\n")
        println("resource pack sha1 = $sha")
    }
}

tasks.build { dependsOn(packZip) }
