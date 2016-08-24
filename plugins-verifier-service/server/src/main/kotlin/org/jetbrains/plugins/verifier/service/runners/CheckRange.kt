package org.jetbrains.plugins.verifier.service.runners

import com.intellij.structure.domain.Plugin
import com.intellij.structure.resolvers.Resolver
import com.jetbrains.pluginverifier.api.*
import com.jetbrains.pluginverifier.configurations.CheckPluginConfiguration
import com.jetbrains.pluginverifier.configurations.CheckPluginParams
import com.jetbrains.pluginverifier.configurations.CheckRangeResults
import com.jetbrains.pluginverifier.configurations.CheckRangeResults.ResultType.*
import com.jetbrains.pluginverifier.repository.IFileLock
import org.jetbrains.plugins.verifier.service.core.BridgeVProgress
import org.jetbrains.plugins.verifier.service.core.Progress
import org.jetbrains.plugins.verifier.service.core.Task
import org.jetbrains.plugins.verifier.service.params.CheckRangeRunnerParams
import org.jetbrains.plugins.verifier.service.storage.IdeFilesManager
import org.jetbrains.plugins.verifier.service.storage.JdkManager
import org.slf4j.LoggerFactory

class CheckRangeRunner(val pluginToCheck: PluginDescriptor,
                       val params: CheckRangeRunnerParams) : Task<CheckRangeResults>() {
  override fun presentableName(): String = "CheckPluginWithSinceUntilBuilds"

  companion object {
    private val LOG = LoggerFactory.getLogger(CheckRangeRunner::class.java)
  }

  override fun computeResult(progress: Progress): CheckRangeResults {
    val createResult: VManager.CreatePluginResult
    try {
      createResult = VManager.createPlugin(pluginToCheck)
    } catch(e: Exception) {
      LOG.error("Unable to create plugin for $pluginToCheck", e)
      throw e
    }
    val (plugin: Plugin?, pluginLock: IFileLock?, badResult: VResult?) = createResult

    if (badResult != null) {
      return when (badResult) {
        is VResult.NotFound -> CheckRangeResults(pluginToCheck, NOT_FOUND, null, null, null)
        is VResult.BadPlugin -> CheckRangeResults(pluginToCheck, BAD_PLUGIN, badResult, null, null)
        else -> throw IllegalStateException()
      }
    }
    try {
      plugin!!

      val sinceBuild = plugin.sinceBuild
      val untilBuild = plugin.untilBuild

      if (sinceBuild == null) {
        LOG.info("The plugin $pluginToCheck has not specified since-build property")
        val reason = "The plugin ${plugin.toString()} has not specified the <idea-version> 'since-build' attribute. See  <a href=\"http://www.jetbrains.org/intellij/sdk/docs/basics/plugin_structure/plugin_configuration_file.html\">Plugin Configuration File - plugin.xml<\\a>"
        return CheckRangeResults(pluginToCheck, BAD_PLUGIN, VResult.BadPlugin(pluginToCheck, reason), null, null)
      }



      LOG.debug("Verifying plugin $plugin against its specified [$sinceBuild; $untilBuild] builds")


      val locks: List<IdeFilesManager.IdeLock> = IdeFilesManager.locked({
        IdeFilesManager.ideList()
            .filter { sinceBuild.compareTo(it) <= 0 && (untilBuild == null || it.compareTo(untilBuild) <= 0) }
            .map { IdeFilesManager.getIde(it) }
            .filterNotNull()
      })
      LOG.debug("IDE-s on the server: ${IdeFilesManager.ideList().joinToString()}; IDE-s compatible with [$sinceBuild; $untilBuild]: [${locks.joinToString { it.ide.version.asString() }}]")

      if (locks.isEmpty()) {
        //TODO: download from the IDE repository.
        LOG.error("There are no IDEs compatible with the Plugin ${plugin.toString()}; [since; until] = [$sinceBuild; $untilBuild]")
        return CheckRangeResults(pluginToCheck, NO_COMPATIBLE_IDES, null, null, null)
      }

      try {
        val ideDescriptors = locks.map { IdeDescriptor.ByInstance(it.ide) }
        val jdkDescriptor = JdkDescriptor.ByFile(JdkManager.getJdkHome(params.jdkVersion))
        val pluginDescriptor = PluginDescriptor.ByInstance(plugin)
        val params = CheckPluginParams(listOf(pluginDescriptor), ideDescriptors, jdkDescriptor, params.vOptions, true, Resolver.getEmptyResolver(), BridgeVProgress(progress))

        LOG.debug("CheckPlugin with [since; until] #$taskId arguments: $params")

        val results: VResults
        try {
          results = CheckPluginConfiguration(params).execute().vResults
        } catch(ie: InterruptedException) {
          throw ie
        } catch(e: Exception) {
          //this is likely the problem of the Verifier itself.
          LOG.error("Failed to verify the plugin $plugin", e)
          throw e
        }
        return CheckRangeResults(pluginToCheck, CHECKED, null, ideDescriptors, results)
      } finally {
        locks.forEach { it.release() }
      }
    } finally {
      pluginLock?.release()
    }
  }
}