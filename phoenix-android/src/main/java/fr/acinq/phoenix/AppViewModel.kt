/*
 * Copyright 2019 ACINQ SAS
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

package fr.acinq.phoenix

import androidx.annotation.UiThread
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory

class AppViewModel : ViewModel() {
  private val log = LoggerFactory.getLogger(AppViewModel::class.java)

  init {
    log.info("app model init...")
  }

  override fun onCleared() {
    shutdown()
    super.onCleared()
    log.debug("appkit has been cleared")
  }

  suspend fun fireAndWait(): String {
    return coroutineScope {
      async(Dispatchers.Default) {
        log.info("do something suspended in default")
        delay(1000)
        "the result!"
      }
    }.await()
  }

  @UiThread
  fun fireAndForget() {
    viewModelScope.launch {
      withContext(Dispatchers.IO) {
        log.info("not awaiting for result")
      }
    }
  }

  private fun shutdown() {
    log.info("shutting down node!")
  }
}
