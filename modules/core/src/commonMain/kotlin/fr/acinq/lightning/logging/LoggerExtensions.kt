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

/**
 * Kermit uses short method name for logging instructions. We prefer using the longer names as seen
 * in SLF4J, so we add new methods as extensions to [Logger].
 */

inline fun Logger.verbose(throwable: Throwable? = null, tag: String = this.tag, message: () -> String) {
    this.v(throwable, tag, message)
}

inline fun Logger.debug(throwable: Throwable? = null, tag: String = this.tag, message: () -> String) {
    this.d(throwable, tag, message)
}

inline fun Logger.info(throwable: Throwable? = null, tag: String = this.tag, message: () -> String) {
    this.i(throwable, tag, message)
}

inline fun Logger.warning(throwable: Throwable? = null, tag: String = this.tag, message: () -> String) {
    this.w(throwable, tag, message)
}

inline fun Logger.error(throwable: Throwable? = null, tag: String = this.tag, message: () -> String) {
    this.e(throwable, tag, message)
}

inline fun Logger.always(throwable: Throwable? = null, tag: String = this.tag, message: () -> String) {
    this.a(throwable, tag, message)
}
