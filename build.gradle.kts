plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}

tasks.register<Test>("test") {
    group = "verification"
    description = "Runs unit tests from subprojects."

    evaluationDependsOn(":app")
    evaluationDependsOn(":sleepwalker-core")

    val appProject = project(":app")
    val coreProject = project(":sleepwalker-core")

    val appTestTask = appProject.tasks.named<Test>("testDebugUnitTest").get()
    val coreTestTask = coreProject.tasks.named<Test>("testDebugUnitTest").get()

    testClassesDirs = files(appTestTask.testClassesDirs, coreTestTask.testClassesDirs)
    classpath = files(appTestTask.classpath, coreTestTask.classpath)

    binaryResultsDirectory.set(layout.buildDirectory.dir("test-results/test/binary"))
    reports.html.outputLocation.set(layout.buildDirectory.dir("reports/tests/test"))
    reports.junitXml.outputLocation.set(layout.buildDirectory.dir("test-results/test"))

    dependsOn(appTestTask)
    dependsOn(coreTestTask)

    // Replace the aggregate 'test' tasks from subprojects to prevent 'Unknown command-line option --tests'
    appProject.tasks.replace("test", org.gradle.api.tasks.testing.Test::class.java).apply {
        testClassesDirs = files()
        classpath = files()
    }
    coreProject.tasks.replace("test", org.gradle.api.tasks.testing.Test::class.java).apply {
        testClassesDirs = files()
        classpath = files()
    }

    // Propagate command-line filter to all subproject test tasks of type Test when task graph is ready
    gradle.taskGraph.whenReady {
        val getCmdPatterns = filter::class.java.getMethod("getCommandLineIncludePatterns")
        val filterSpecs = getCmdPatterns.invoke(filter) as? Set<*>
        if (filterSpecs != null && filterSpecs.isNotEmpty()) {
            subprojects {
                tasks.withType<Test>().configureEach {
                    filter.setFailOnNoMatchingTests(false)
                    filterSpecs.forEach { spec ->
                        filter.includeTestsMatching(spec.toString())
                    }
                }
            }
        }
    }
}
