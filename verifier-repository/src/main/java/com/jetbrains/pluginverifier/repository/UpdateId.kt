package com.jetbrains.pluginverifier.repository

data class UpdateId(val id: Int) {
  override fun toString(): String = "#$id"
}