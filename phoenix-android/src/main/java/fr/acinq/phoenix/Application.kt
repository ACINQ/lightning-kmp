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

import android.app.Application
import fr.acinq.phoenix.utils.Logging
import org.slf4j.LoggerFactory

class Application : Application() {

    val log = LoggerFactory.getLogger(Application::class.java)

    override fun onCreate() {
        super.onCreate()
        init()
        log.info("app started")
    }

    private fun init() {
        Logging.setupLogger(applicationContext)
    }
}
