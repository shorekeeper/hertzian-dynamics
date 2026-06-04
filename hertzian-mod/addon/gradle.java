// Project-specific Gradle hook executed after the convention but
// before task graph realisation. Reserved for custom task wiring
// that would otherwise pollute build.gradle.kts.
//
// Empty at present. A cargo cross-compile JAR task can be routed
// here so a single CI build produces librf_jni for linux, macos
// and windows in one pass.