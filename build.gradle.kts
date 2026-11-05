plugins {
    `java-library`
    `maven-publish`
}

group = "io.github.59alex"
version = "0.1.0"
val bootVersion = "4.1.2"
val testJavaVersion = providers.gradleProperty("testJavaVersion").map(String::toInt)
    .getOrElse(JavaVersion.current().majorVersion.toInt())

repositories {
    mavenLocal()
    mavenCentral()
}
java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(testJavaVersion))
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
    withJavadocJar()
}
tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    options.encoding = "UTF-8"
}
tasks.withType<Test>().configureEach { useJUnitPlatform() }
tasks.withType<Jar>().configureEach { from("LICENSE") { into("META-INF") } }

dependencies {
    api(platform("org.springframework.boot:spring-boot-dependencies:$bootVersion"))
    api("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework:spring-aop")
    api("io.github.59alex:reactive-pipeline-kit:0.3.0")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor:$bootVersion")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.projectreactor:reactor-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

publishing {
    publications {
        create<MavenPublication>("library") {
            from(components["java"])
            pom {
                name.set("Reactive Pipeline Spring Boot Starter")
                description.set("Spring Boot defaults and typed factory for reactive-pipeline-kit")
                url.set("https://github.com/dlorian59/reactive-pipeline-spring-boot-starter")
                licenses { license { name.set("MIT License"); url.set("https://opensource.org/license/mit") } }
                developers { developer { id.set("dlorian59"); name.set("Aleksandr Kashevarov") } }
                scm { url.set("https://github.com/dlorian59/reactive-pipeline-spring-boot-starter") }
            }
        }
    }
}
