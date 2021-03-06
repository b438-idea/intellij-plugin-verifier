package com.jetbrains.intellij.feature.extractor

import com.google.gson.Gson
import com.jetbrains.plugin.structure.base.plugin.PluginCreationFail
import com.jetbrains.plugin.structure.base.plugin.PluginCreationSuccess
import com.jetbrains.plugin.structure.ide.IdeManager
import com.jetbrains.plugin.structure.ide.classes.IdeResolverCreator
import com.jetbrains.plugin.structure.intellij.plugin.IdePluginManager
import java.io.File

/**
 * Command-line entry point of feature extractor.
 */
fun main(args: Array<String>) {
  if (args.size != 2) {
    throw IllegalArgumentException("Usage: <plugin> <idea>")
  }
  val pluginFile = File(args[0])
  val ideaFile = File(args[1])
  val pluginCreationResult = IdePluginManager.createManager().createPlugin(pluginFile)
  val result = when (pluginCreationResult) {
    is PluginCreationSuccess -> {
      val ide = IdeManager.createManager().createIde(ideaFile)
      IdeResolverCreator.createIdeResolver(ide).use { ideResolver ->
        val extractorResult = FeaturesExtractor.extractFeatures(ide, ideResolver, pluginCreationResult.plugin)
        extractorResult.features.forEach { println(Gson().toJson(it)) }
        "All features extracted: ${extractorResult.extractedAll}"
      }
    }
    is PluginCreationFail -> "Plugin is invalid: " + pluginCreationResult.errorsAndWarnings.joinToString()
  }
  println(result)
}