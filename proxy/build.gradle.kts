group = rootProject.group
version = rootProject.version
description = "Plex-Velocity"

repositories {
    maven { url = uri("https://repo.papermc.io/repository/maven-public/") }
}

tasks.getByName<Jar>("jar") {
    archiveBaseName.set("Plex-Velocity")
}

tasks {
    jar {
        finalizedBy(rootProject.tasks["copyJars"])
    }

    shadowJar {
        enabled = false
    }
}

sourceSets {
    main {
        blossom {
            javaSources {
                property("version", project.version.toString())
            }
        }
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}

dependencies {
    compileOnly("org.projectlombok:lombok:1.18.30")
    annotationProcessor("org.projectlombok:lombok:1.18.30")
    compileOnly("org.json:json:20231013")
    compileOnly("com.velocitypowered:velocity-api:3.2.0-SNAPSHOT")
    annotationProcessor("com.velocitypowered:velocity-api:3.2.0-SNAPSHOT")
}