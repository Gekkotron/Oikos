plugins {
    id("oikos.jvm.library")
    id("oikos.serialization")
}

dependencies {
    // Entity, MqttBindings (the discovery wiring a ref is read from).
    api(project(":core:state"))
    // HttpGetResult: what LanOrGatewayGet answers.
    api(project(":core:transport"))
}
