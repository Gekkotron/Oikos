package com.gekkotron.oikos.transport

/** MQTT 3.1.1 topic-filter matching, including the `$`-topic rule (§4.7.2). */
fun topicMatches(filter: String, topic: String): Boolean {
    if (topic.startsWith("$") && (filter.startsWith("#") || filter.startsWith("+"))) return false
    val f = filter.split('/')
    val t = topic.split('/')
    for (i in f.indices) {
        when (f[i]) {
            "#" -> return true
            "+" -> if (i >= t.size) return false
            else -> if (i >= t.size || f[i] != t[i]) return false
        }
    }
    return f.size == t.size
}

fun isValidPublishTopic(topic: String): Boolean =
    topic.isNotEmpty() && '+' !in topic && '#' !in topic && '\u0000' !in topic
