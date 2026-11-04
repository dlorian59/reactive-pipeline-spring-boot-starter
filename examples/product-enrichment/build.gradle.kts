plugins { application }

repositories {
    mavenLocal()
    mavenCentral()
}
dependencies {
    implementation("io.github.59alex:reactive-pipeline-spring-boot-starter:0.1.0")
}
java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(
        providers.gradleProperty("exampleJava").map(String::toInt)
            .getOrElse(JavaVersion.current().majorVersion.toInt())))
}
tasks.withType<JavaCompile>().configureEach { options.release.set(17); options.encoding = "UTF-8" }
application {
    mainClass.set(providers.gradleProperty("exampleMain").getOrElse("example.ExampleApplication"))
}
