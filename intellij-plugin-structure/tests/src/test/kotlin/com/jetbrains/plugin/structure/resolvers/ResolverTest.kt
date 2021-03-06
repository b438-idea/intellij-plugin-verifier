package com.jetbrains.plugin.structure.resolvers

import com.jetbrains.plugin.structure.classes.resolvers.CacheResolver
import com.jetbrains.plugin.structure.classes.resolvers.EmptyResolver
import com.jetbrains.plugin.structure.classes.resolvers.FixedClassesResolver
import com.jetbrains.plugin.structure.classes.resolvers.UnionResolver
import org.junit.Assert.*
import org.junit.Test
import org.objectweb.asm.tree.ClassNode

class ResolverTest {
  @Test
  fun `empty cache doesnt contain classes`() {
    val cacheResolver = CacheResolver(EmptyResolver)
    assertNull(cacheResolver.findClass("a"))
    assertTrue(cacheResolver.allClasses.isEmpty())
    assertEquals(emptySet<String>(), cacheResolver.allPackages)
  }

  @Test
  fun `cache with one class`() {
    val className = "a"
    val classNode = ClassNode()
    classNode.name = className
    val cacheResolver = CacheResolver(
        FixedClassesResolver.create(listOf(classNode))
    )
    assertEquals(1, cacheResolver.allClasses.size)
    assertEquals(classNode, cacheResolver.findClass(className))
    assertEquals(setOf(""), cacheResolver.allPackages)
    assertTrue(cacheResolver.containsPackage(""))
  }

  @Test
  fun `union resolver search order is equal to class-path`() {
    val commonPackage = "some/package"

    val sameClass = "$commonPackage/Same"
    val sameClassNode1 = ClassNode().apply { name = sameClass }
    val sameClassNode2 = ClassNode().apply { name = sameClass }

    val class1 = "$commonPackage/Some1"
    val class1Node = ClassNode().apply { name = class1 }

    val class2 = "$commonPackage/Some2"
    val class2Node = ClassNode().apply { name = class2 }

    val resolver1 = FixedClassesResolver.create(class1Node, sameClassNode1)
    val resolver2 = FixedClassesResolver.create(class2Node, sameClassNode2)

    val resolver = UnionResolver.create(resolver1, resolver2)

    assertEquals(setOf("some", "some/package"), resolver.allPackages)
    assertEquals(setOf(sameClass, class1, class2), resolver.allClasses)

    assertSame(class1Node, resolver.findClass(class1))
    assertSame(class2Node, resolver.findClass(class2))
    assertSame(sameClassNode1, resolver.findClass(sameClass))
    assertSame(resolver1, resolver.getClassLocation(sameClass))
  }
}