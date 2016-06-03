package com.jetbrains.pluginverifier.location;

import com.jetbrains.pluginverifier.utils.FailUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.TestOnly;
import org.objectweb.asm.tree.MethodNode;

/**
 * @author Sergey Patrikeev
 */
public abstract class ProblemLocation {

  //TODO: add more detailed location, e.g. superclass, field of a class, interface, throws list and so on

  @NotNull
  public static ProblemLocation fromPlugin(@NotNull String pluginId) {
    return new PluginLocation(pluginId);
  }

  @NotNull
  public static ProblemLocation fromClass(@NotNull String className) {
    return new CodeLocation(className, null, null);
  }

  @NotNull
  public static ProblemLocation fromField(@NotNull String className, @NotNull String fieldName) {
    return new CodeLocation(className, null, fieldName);
  }

  @NotNull
  @TestOnly
  public static ProblemLocation fromMethod(@NotNull String className, @NotNull String methodDescr) {
    return new CodeLocation(className, methodDescr, null);
  }

  @NotNull
  public static ProblemLocation fromMethod(@NotNull String className, @NotNull MethodNode methodNode) {
    return new CodeLocation(className, getMethodDescr(methodNode), null);
  }

  @NotNull
  private static String getMethodDescr(@NotNull MethodNode methodNode) {
    FailUtil.assertTrue(methodNode.name != null);
    FailUtil.assertTrue(methodNode.desc != null);
    return methodNode.name + methodNode.desc;
  }

  @Override
  public String toString() {
    throw new UnsupportedOperationException("Children of ProblemLocation must override toString()");
  }

  @Override
  public boolean equals(Object o) {
    throw new UnsupportedOperationException("Children of ProblemLocation must override equals() and hashcode()");
  }

  @Override
  public int hashCode() {
    throw new UnsupportedOperationException("Children of ProblemLocation must override equals() and hashcode()");
  }
}