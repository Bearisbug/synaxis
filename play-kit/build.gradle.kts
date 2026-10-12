plugins {
    id("synaxis.java-library")
}

// The play kit of docs/DESIGN-PLAY.md API-PLAY-008: helpers a game plugin builds its rules from. It
// sits on the stable surface the way a plugin does, takes no ABI gate and makes no compatibility
// promise; its types appear in the signatures of its classes, so the surface is an api dependency.
dependencies {
    api(project(":plugin-api"))
}

// No module-info here: plugins run on the classpath (ADR-M2-005), and so does what they bring along.
