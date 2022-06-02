plugins {
    id("org.openrewrite.java-library")
    id("org.openrewrite.maven-publish")
}

dependencies {
    api(project(":rewrite-java"))
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:latest.release")

    testImplementation(project(":rewrite-test"))
}
