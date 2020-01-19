package io.gitlab.arturbosch.detekt.invoke

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import java.io.PrintStream
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader

internal interface DetektInvoker {
    fun invokeCli(
        arguments: List<CliArgument>,
        classpath: FileCollection,
        taskName: String,
        ignoreFailures: Boolean = false
    )

    companion object {
        fun create(project: Project): DetektInvoker =
            if (project.isDryRunEnabled()) {
                DryRunInvoker(project)
            } else {
                DefaultCliInvoker(project)
            }

        private fun Project.isDryRunEnabled(): Boolean {
            return hasProperty(DRY_RUN_PROPERTY) && property(DRY_RUN_PROPERTY) == "true"
        }

        private const val DRY_RUN_PROPERTY = "detekt-dry-run"
    }
}

private class DefaultCliInvoker(private val project: Project) : DetektInvoker {

    override fun invokeCli(
        arguments: List<CliArgument>,
        classpath: FileCollection,
        taskName: String,
        ignoreFailures: Boolean
    ) {
        val cliArguments = arguments.flatMap(CliArgument::toArgument)
        try {
            val loader = URLClassLoader(
                classpath.map { it.toURI().toURL() }.toTypedArray(),
                null /* isolate detekt environment */
            )
            loader.use {
                val clazz = it.loadClass("io.gitlab.arturbosch.detekt.cli.Main")
                val runner = clazz.getMethod("buildRunner",
                    Array<String>::class.java,
                    PrintStream::class.java,
                    PrintStream::class.java
                ).invoke(null, cliArguments.toTypedArray(), System.out, System.err)
                runner::class.java.getMethod("execute").invoke(runner)
            }
        } catch (reflectionWrapper: InvocationTargetException) {
            val cause = reflectionWrapper.targetException
            val message = cause.message
            if (message != null && isBuildFailure(message) && ignoreFailures) {
                return
            }
            throw GradleException(message ?: "There was a problem running detekt.", cause)
        }
    }

    private fun isBuildFailure(msg: String?) =
        msg != null && "Build failed with" in msg && "issues" in msg
}

private class DryRunInvoker(private val project: Project) : DetektInvoker {

    override fun invokeCli(
        arguments: List<CliArgument>,
        classpath: FileCollection,
        taskName: String,
        ignoreFailures: Boolean
    ) {
        val cliArguments = arguments.flatMap(CliArgument::toArgument)
        project.logger.info("Invoking detekt with dry-run.")
        project.logger.info("Task: $taskName")
        project.logger.info("Arguments: ${cliArguments.joinToString(" ")}")
        project.logger.info("Classpath: ${classpath.files}")
        project.logger.info("Ignore failures: $ignoreFailures")
    }
}
