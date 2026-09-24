plugins {
    id("oikos.jvm.library")
    `java-test-fixtures`
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    implementation(libs.hivemq.mqtt.client)
    testFixturesApi(libs.kotlinx.coroutines.core)
    testFixturesApi(libs.hivemq.mqtt.client)
    testFixturesImplementation(libs.moquette.broker)
}
