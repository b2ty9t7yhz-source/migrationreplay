plugins {
    application
    jacoco
    java
}

group = "dev.migrationreplay"
version = "1.0.0"

val releaseVersion = version.toString()

tasks.register("printVersion") {
    group = "help"
    description = "Prints the project release version."
    doLast {
        println(releaseVersion)
    }
}

repositories {
    mavenCentral()
}

dependencyLocking {
    lockAllConfigurations()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    implementation("org.xerial:sqlite-jdbc:3.53.2.1")
    implementation(platform("com.fasterxml.jackson:jackson-bom:2.21.5"))
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")

    testImplementation(platform("org.junit:junit-bom:6.1.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "dev.migrationreplay.Main"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

distributions {
    named("main") {
        contents {
            from("README.md")
            from("CHANGELOG.md")
            from("LICENSE")
            from("SECURITY.md")
            from("docs") {
                into("docs")
            }
            from("examples") {
                into("examples")
            }
        }
    }
}

tasks.processResources {
    filesMatching("dev/migrationreplay/version.properties") {
        expand("version" to releaseVersion)
    }
}

tasks.jar {
    manifest {
        attributes["Implementation-Title"] = "MigrationReplay"
        attributes["Implementation-Version"] = releaseVersion
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        html.required = true
        xml.required = true
        csv.required = false
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.84".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = "0.65".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification, tasks.jacocoTestReport)
}
