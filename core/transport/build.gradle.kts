plugins {
    id("oikos.jvm.library")
    `java-test-fixtures`
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    // api: :app builds AthenaGatewayApi with its shared OkHttpClient (OkHttp comes with the SDK).
    api(libs.athena.gateway.client)
    implementation(libs.hivemq.mqtt.client)
    testImplementation(project(":core:crypto")) // seals replies as the gateway would
    testImplementation(libs.mockwebserver)
    testFixturesApi(libs.kotlinx.coroutines.core)
    testFixturesApi(libs.hivemq.mqtt.client)
    testFixturesImplementation(libs.moquette.broker)
}
