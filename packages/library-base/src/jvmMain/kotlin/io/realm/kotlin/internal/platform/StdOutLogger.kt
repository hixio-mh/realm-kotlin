/*
 * Copyright 2021 Realm Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.realm.kotlin.internal.platform

import io.realm.kotlin.log.LogLevel
import io.realm.kotlin.log.RealmLogger
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Logger implementation outputting to stdout.
 */
internal class StdOutLogger(
    override val tag: String = "REALM",
    override val level: LogLevel
) : RealmLogger {

    override fun log(level: LogLevel, throwable: Throwable?, message: String?, vararg args: Any?) {
        val logMessage: String = prepareLogMessage(throwable, message, *args)
        println("${level.name}: [$tag] $logMessage")
    }

    private fun prepareLogMessage(
        throwable: Throwable?,
        message: String?,
        vararg args: Any?
    ): String {
        var messageToLog = message
        if (messageToLog.isNullOrEmpty()) {
            if (throwable == null) {
                return ""
            }
            messageToLog = getStackTraceString(throwable)
        } else {
            if (args.isNotEmpty()) {
                messageToLog = formatMessage(messageToLog, *args)
            }
            if (throwable != null) {
                messageToLog += "\n" + getStackTraceString(throwable)
            }
        }
        return messageToLog
    }

    private fun formatMessage(message: String, vararg args: Any?): String {
        return message.format(*args)
    }

    private fun getStackTraceString(t: Throwable): String {
        val sw = StringWriter(INITIAL_BUFFER_SIZE)
        val pw = PrintWriter(sw, false)
        t.printStackTrace(pw)
        pw.flush()
        return sw.toString()
    }

    companion object {
        const val INITIAL_BUFFER_SIZE = 256
    }
}
