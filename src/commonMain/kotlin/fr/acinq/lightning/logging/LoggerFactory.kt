/*
 * Copyright 2024 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.lightning.logging

import co.touchlab.kermit.Logger
import co.touchlab.kermit.LoggerConfig
import kotlin.reflect.KClass

/**
 * A logger factory is a simple class that allows us to create [Logger] instances sharing the same configuration.
 *
 * For example, we typically want loggers using the same severity, and writing logs to the same place. A common
 * factory, instantiated in one place, and shared throughout the project, lets us do that.
 */
class LoggerFactory(private val config: LoggerConfig) {

    /**
     * Returns a new [Logger] object that will use the qualified class name as a tag when available. If the qualified name
     * is not available, for example, the class is an anonymous class, then we fall back a default static tag.
     */
    fun newLogger(cls: KClass<*>): Logger = newLogger(tag = cls.qualifiedName ?: cls.simpleName ?: "Anonymous")

    /** Returns a new [Logger] object using the config provided to the factory. */
    fun newLogger(tag: String): Logger = Logger(
        config = config,
        tag = tag
    )
}