// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.internal.statistic.eventLog

import com.intellij.internal.statistic.DeviceIdManager
import com.intellij.internal.statistic.eventLog.EventLogConfiguration.getHeadlessDeviceIdProperty
import com.intellij.internal.statistic.eventLog.EventLogConfiguration.getHeadlessSaltProperty
import com.intellij.internal.statistic.eventLog.EventLogConfiguration.getSaltPropertyKey
import com.intellij.internal.statistic.utils.StatisticsUtil
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.impl.ApplicationInfoImpl
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.BuildNumber
import com.intellij.openapi.util.text.StringUtil
import com.intellij.util.MathUtil
import com.intellij.util.io.DigestUtil
import org.jetbrains.annotations.ApiStatus
import java.nio.file.Path
import java.nio.file.Paths
import java.security.SecureRandom
import java.util.*
import java.util.prefs.Preferences

@ApiStatus.Internal
object EventLogConfiguration {
  internal val LOG = Logger.getInstance(EventLogConfiguration::class.java)
  private const val FUS_RECORDER = "FUS"
  private const val SALT_PREFERENCE_KEY = "feature_usage_event_log_salt"
  private const val IDEA_HEADLESS_STATISTICS_DEVICE_ID = "idea.headless.statistics.device.id"
  private const val IDEA_HEADLESS_STATISTICS_SALT = "idea.headless.statistics.salt"

  private val defaultConfiguration: EventLogRecorderConfiguration = EventLogRecorderConfiguration(FUS_RECORDER)
  private val configurations: MutableMap<String, EventLogRecorderConfiguration> = HashMap()

  val build: String by lazy { ApplicationInfo.getInstance().build.asBuildNumber() }

  @Deprecated("Use bucket from configuration created with getOrCreate method")
  val bucket: Int = defaultConfiguration.bucket

  @Deprecated("Call method on configuration created with getOrCreate method")
  fun anonymize(data: String): String {
    return defaultConfiguration.anonymize(data)
  }

  private fun BuildNumber.asBuildNumber(): String {
    val str = this.asStringWithoutProductCodeAndSnapshot()
    return if (str.endsWith(".")) str + "0" else str
  }

  fun getOrCreate(recorderId: String): EventLogRecorderConfiguration {
    if (isDefaultRecorderId(recorderId)) return defaultConfiguration

    synchronized(this) {
      return configurations.getOrPut(recorderId) { EventLogRecorderConfiguration(recorderId) }
    }
  }

  /**
   * Don't use this method directly, prefer [EventLogConfiguration.anonymize]
   */
  fun hashSha256(salt: ByteArray, data: String): String {
    val md = DigestUtil.sha256()
    md.update(salt)
    md.update(data.toByteArray())
    return StringUtil.toHexString(md.digest())
  }

  fun getEventLogDataPath(): Path = Paths.get(PathManager.getSystemPath()).resolve("event-log-data")

  @JvmStatic
  fun getEventLogSettingsPath(): Path = getEventLogDataPath().resolve("settings")

  internal fun getSaltPropertyKey(recorderId: String): String {
    return if (isDefaultRecorderId(recorderId)) SALT_PREFERENCE_KEY else StringUtil.toLowerCase(recorderId) + "_" + SALT_PREFERENCE_KEY
  }

  internal fun getHeadlessDeviceIdProperty(recorderId: String): String {
    return getRecorderBasedProperty(recorderId, IDEA_HEADLESS_STATISTICS_DEVICE_ID)
  }

  internal fun getHeadlessSaltProperty(recorderId: String): String {
    return getRecorderBasedProperty(recorderId, IDEA_HEADLESS_STATISTICS_SALT)
  }

  private fun getRecorderBasedProperty(recorderId: String, property: String): String {
    return if (isDefaultRecorderId(recorderId)) property else property + "." + StringUtil.toLowerCase(recorderId)
  }

  private fun isDefaultRecorderId(recorderId: String): Boolean {
    return FUS_RECORDER == recorderId
  }
}

class EventLogRecorderConfiguration internal constructor(recorderId: String) {
  val sessionId: String = generateSessionId()

  val deviceId: String = getOrGenerateDeviceId(recorderId)
  val bucket: Int = deviceId.asBucket()

  private val salt: ByteArray = getOrGenerateSalt(recorderId)
  private val anonymizedCache = HashMap<String, String>()

  fun anonymize(data: String): String {
    if (data.isBlank()) {
      return data
    }

    if (anonymizedCache.containsKey(data)) {
      return anonymizedCache[data] ?: ""
    }

    val result = EventLogConfiguration.hashSha256(salt, data)
    anonymizedCache[data] = result
    return result
  }

  private fun String.shortedUUID(): String {
    val start = this.lastIndexOf('-')
    if (start > 0 && start + 1 < this.length) {
      return this.substring(start + 1)
    }
    return this
  }

  private fun String.asBucket(): Int {
    return MathUtil.nonNegativeAbs(this.hashCode()) % 256
  }

  private fun generateSessionId(): String {
    val presentableHour = StatisticsUtil.getCurrentHourInUTC()
    return "$presentableHour-${UUID.randomUUID().toString().shortedUUID()}"
  }

  private fun getOrGenerateDeviceId(recorderId: String): String {
    val app = ApplicationManager.getApplication()
    if (app != null && app.isHeadlessEnvironment) {
      val property = getHeadlessDeviceIdProperty(recorderId)
      System.getProperty(property)?.let {
        return it
      }
    }
    return DeviceIdManager.getOrGenerateId(recorderId)
  }

  private fun getOrGenerateSalt(recorderId: String): ByteArray {
    val app = ApplicationManager.getApplication()
    if (app != null && app.isHeadlessEnvironment) {
      val property = getHeadlessSaltProperty(recorderId)
      System.getProperty(property)?.let {
        return it.toByteArray(Charsets.UTF_8)
      }
    }

    val companyName = ApplicationInfoImpl.getShadowInstance().shortCompanyName
    val name = if (StringUtil.isEmptyOrSpaces(companyName)) "jetbrains" else companyName.toLowerCase(Locale.US)
    val prefs = Preferences.userRoot().node(name)

    val saltKey = getSaltPropertyKey(recorderId)
    var salt = prefs.getByteArray(saltKey, null)
    if (salt == null) {
      salt = ByteArray(32)
      SecureRandom().nextBytes(salt)
      prefs.putByteArray(saltKey, salt)
      EventLogConfiguration.LOG.info("Generating salt for the device")
    }
    return salt
  }
}
