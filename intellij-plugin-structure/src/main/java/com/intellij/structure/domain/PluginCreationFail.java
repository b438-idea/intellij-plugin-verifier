package com.intellij.structure.domain;

import org.jetbrains.annotations.NotNull;

import java.util.List;

public interface PluginCreationFail extends PluginCreationResult {
  @NotNull
  public List<PluginProblem> getErrorsAndWarnings();
}