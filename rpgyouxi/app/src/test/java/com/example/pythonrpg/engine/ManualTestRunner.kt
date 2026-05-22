package com.example.pythonrpg.engine

import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import org.junit.platform.engine.discovery.DiscoverySelectors
import org.junit.platform.launcher.listeners.SummaryGeneratingListener
import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.TestIdentifier
import org.junit.platform.engine.TestExecutionResult
import java.io.PrintWriter

fun main() {
    println("=== STARTING MANUAL TEST RUNNER ===")
    val request = LauncherDiscoveryRequestBuilder.request()
        .selectors(
            DiscoverySelectors.selectPackage("com.example.pythonrpg.engine")
        )
        .build()

    val launcher = LauncherFactory.create()
    val listener = SummaryGeneratingListener()
    val customListener = object : TestExecutionListener {
        override fun executionStarted(testIdentifier: TestIdentifier) {
            if (testIdentifier.isTest) {
                println(" [START] Running: ${testIdentifier.displayName}")
                System.out.flush()
            }
        }
        override fun executionFinished(testIdentifier: TestIdentifier, testExecutionResult: TestExecutionResult) {
            if (testIdentifier.isTest) {
                val status = if (testExecutionResult.status == TestExecutionResult.Status.SUCCESSFUL) "SUCCESS" else "FAILED"
                println(" [FINISH] ${testIdentifier.displayName} -> $status")
                System.out.flush()
            }
        }
    }

    launcher.registerTestExecutionListeners(listener, customListener)
    launcher.execute(request)

    val summary = listener.summary
    println("=== TEST RUN RESULTS ===")
    println("Total Tests Discovered: ${summary.testsFoundCount}")
    println("Total Tests Started:    ${summary.testsStartedCount}")
    println("Total Tests Succeeded:  ${summary.testsSucceededCount}")
    println("Total Tests Failed:     ${summary.testsFailedCount}")
    println("Total Tests Skipped:    ${summary.testsSkippedCount}")

    if (summary.failures.isNotEmpty()) {
        println("\n=== FAILURES DETAIL ===")
        for (failure in summary.failures) {
            println("Test: ${failure.testIdentifier.displayName}")
            println("Exception: ${failure.exception}")
            failure.exception.printStackTrace(PrintWriter(System.out))
            println("-----------------------")
        }
        System.exit(1)
    } else {
        println("\nALL TESTS PASSED SUCCESSFULLY!")
        System.exit(0)
    }
}
