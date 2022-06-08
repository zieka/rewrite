plugins {
    id("org.openrewrite.java-library")
    id("org.openrewrite.maven-publish")
}

dependencies {
    api(project(":rewrite-java"))
    implementation(platform(kotlin("bom", "latest.release")))
    implementation(kotlin("compiler-embeddable"))
    implementation(kotlin("stdlib"))
    testImplementation(project(":rewrite-test"))
}
