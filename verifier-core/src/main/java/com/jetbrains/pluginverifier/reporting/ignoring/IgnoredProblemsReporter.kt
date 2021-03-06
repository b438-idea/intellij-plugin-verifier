package com.jetbrains.pluginverifier.reporting.ignoring

import com.jetbrains.pluginverifier.VerificationTarget
import com.jetbrains.pluginverifier.misc.closeLogged
import com.jetbrains.pluginverifier.misc.writeText
import com.jetbrains.pluginverifier.reporting.Reporter
import com.jetbrains.pluginverifier.reporting.common.CollectingReporter
import java.nio.file.Path

/**
 * Collects all [ProblemIgnoredEvent]s reported
 * for one plugin against one target
 * and saves them to `<plugin-verification>/ignored-problems.txt` file.
 */
class IgnoredProblemsReporter(
    private val pluginVerificationDirectory: Path,
    private val verificationTarget: VerificationTarget
) : Reporter<ProblemIgnoredEvent> {

  private val collectingReporter = CollectingReporter<ProblemIgnoredEvent>()

  override fun report(t: ProblemIgnoredEvent) {
    collectingReporter.report(t)
  }

  override fun close() {
    try {
      saveIgnoredProblems()
    } finally {
      collectingReporter.closeLogged()
    }
  }

  private fun saveIgnoredProblems() {
    val allIgnoredProblems = collectingReporter.getReported()
    if (allIgnoredProblems.isNotEmpty()) {
      val ignoredProblemsFile = pluginVerificationDirectory.resolve("ignored-problems.txt")
      ignoredProblemsFile.writeText(
          AllIgnoredProblemsReporter.formatManyIgnoredProblems(verificationTarget, allIgnoredProblems)
      )
    }
  }


}