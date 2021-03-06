package com.jetbrains.plugin.structure.ide.classes

import com.intellij.openapi.util.io.FileUtil
import com.intellij.util.SystemProperties
import com.jetbrains.plugin.structure.classes.resolvers.ClassFilesResolver
import com.jetbrains.plugin.structure.classes.resolvers.EmptyResolver
import com.jetbrains.plugin.structure.classes.resolvers.Resolver
import com.jetbrains.plugin.structure.classes.resolvers.UnionResolver
import com.jetbrains.plugin.structure.classes.utils.JarsUtils
import com.jetbrains.plugin.structure.ide.Ide
import com.jetbrains.plugin.structure.ide.IdeManagerImpl
import com.jetbrains.plugin.structure.ide.IdeManagerImpl.Companion.isCompiledCommunity
import com.jetbrains.plugin.structure.ide.IdeManagerImpl.Companion.isCompiledUltimate
import com.jetbrains.plugin.structure.ide.IdeManagerImpl.Companion.isDistributionIde
import com.jetbrains.plugin.structure.ide.util.loadProject
import org.jetbrains.jps.model.java.JpsJavaExtensionService
import org.jetbrains.jps.model.library.JpsOrderRootType
import java.io.File

object IdeResolverCreator {

  @JvmStatic
  fun createIdeResolver(ide: Ide): Resolver = createIdeResolver(Resolver.ReadMode.FULL, ide)

  @JvmStatic
  fun createIdeResolver(readMode: Resolver.ReadMode, ide: Ide): Resolver {
    val idePath = ide.idePath
    return when {
      isDistributionIde(idePath) -> getJarsResolver(idePath.resolve("lib"), readMode)
      isCompiledCommunity(idePath) || isCompiledUltimate(idePath) -> getIdeResolverFromCompiledSources(idePath, readMode)
      else -> throw IllegalArgumentException("Invalid IDE $ide at $idePath")
    }
  }

  private fun getJarsResolver(jarsDirectory: File, readMode: Resolver.ReadMode): Resolver {
    if (!jarsDirectory.isDirectory) {
      return EmptyResolver
    }

    val jars = JarsUtils.collectJars(jarsDirectory, { true }, false)
    return JarsUtils.makeResolver(readMode, jars)
  }

  private fun getIdeResolverFromCompiledSources(idePath: File, readMode: Resolver.ReadMode): Resolver {
    val resolvers = arrayListOf<Resolver>()

    resolvers += getJarsResolver(idePath.resolve("lib"), readMode)
    resolvers += getRepositoryLibrariesResolver(idePath, readMode)

    val compiledClassesRoot = IdeManagerImpl.getCompiledClassesRoot(idePath)!!
    for (moduleRoot in compiledClassesRoot.listFiles().orEmpty()) {
      resolvers += ClassFilesResolver(moduleRoot, readMode)
    }

    if (IdeManagerImpl.isCompiledUltimate(idePath)) {
      resolvers += getJarsResolver(idePath.resolve("community").resolve("lib"), readMode)
    }

    return UnionResolver.create(resolvers)
  }

  private fun getRepositoryLibrariesResolver(idePath: File, readMode: Resolver.ReadMode): Resolver {
    val jars = getRepositoryLibraries(idePath.absoluteFile)
    return JarsUtils.makeResolver(readMode, jars)
  }

  private fun getRepositoryLibraries(projectPath: File): List<File> {
    val pathVariables = createPathVariables()
    val project = loadProject(projectPath, pathVariables)
    return JpsJavaExtensionService.dependencies(project)
        .productionOnly()
        .runtimeOnly()
        .libraries
        .flatMap { it.getFiles(JpsOrderRootType.COMPILED) }
        .distinctBy { it.path }
        .filter { it.isFile && it.name.endsWith(".jar") }
  }

  private fun createPathVariables(): Map<String, String> {
    val m2Repo = FileUtil.toSystemIndependentName(File(SystemProperties.getUserHome(), ".m2/repository").absolutePath)
    return mapOf("MAVEN_REPOSITORY" to m2Repo)
  }

}

