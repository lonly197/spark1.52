/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.deploy.yarn

import java.io.File
import java.util.regex.Matcher
import java.util.regex.Pattern

import scala.collection.mutable.HashMap
import scala.reflect.runtime._
import scala.util.Try

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.hadoop.hdfs.security.token.delegation.DelegationTokenIdentifier
import org.apache.hadoop.io.Text
import org.apache.hadoop.mapred.{Master, JobConf}
import org.apache.hadoop.security.Credentials
import org.apache.hadoop.security.UserGroupInformation
import org.apache.hadoop.security.token.Token
import org.apache.hadoop.yarn.conf.YarnConfiguration
import org.apache.hadoop.yarn.api.ApplicationConstants
import org.apache.hadoop.yarn.api.ApplicationConstants.Environment
import org.apache.hadoop.yarn.api.records.{ApplicationAccessType, ContainerId, Priority}
import org.apache.hadoop.yarn.util.ConverterUtils

import org.apache.spark.deploy.SparkHadoopUtil
import org.apache.spark.{SecurityManager, SparkConf, SparkException}
import org.apache.spark.util.Utils

/**
 * Contains util methods to interact with Hadoop from spark.
  * 包含与spark中的Hadoop交互的util方法
 */
class YarnSparkHadoopUtil extends SparkHadoopUtil {

  private var tokenRenewer: Option[ExecutorDelegationTokenUpdater] = None

  override def transferCredentials(source: UserGroupInformation, dest: UserGroupInformation) {
    dest.addCredentials(source.getCredentials())
  }

  // Note that all params which start with SPARK are propagated all the way through, so if in yarn
  // mode, this MUST be set to true.
  //请注意,所有以SPARK开头的参数都会一直传播,因此如果处于yarn模式,则必须将其设置为true。
  override def isYarnMode(): Boolean = { true }

  // Return an appropriate (subclass) of Configuration. Creating a config initializes some Hadoop
  // subsystems. Always create a new config, dont reuse yarnConf.
  //返回一个适当的(子类)Configuration,创建配置会初始化一些Hadoop子系统,
  //始终创建一个新配置,不要重复使用yarnConf。
  override def newConfiguration(conf: SparkConf): Configuration =
    new YarnConfiguration(super.newConfiguration(conf))

  // Add any user credentials to the job conf which are necessary for running on a secure Hadoop
  // cluster
  //将任何用户凭据添加到作业conf中,这是在安全的Hadoop集群上运行所必需的
  override def addCredentials(conf: JobConf) {
    val jobCreds = conf.getCredentials()
    jobCreds.mergeAll(UserGroupInformation.getCurrentUser().getCredentials())
  }

  override def getCurrentUserCredentials(): Credentials = {
    UserGroupInformation.getCurrentUser().getCredentials()
  }

  override def addCurrentUserCredentials(creds: Credentials) {
    UserGroupInformation.getCurrentUser().addCredentials(creds)
  }

  override def addSecretKeyToUserCredentials(key: String, secret: String) {
    val creds = new Credentials()
    creds.addSecretKey(new Text(key), secret.getBytes("utf-8"))
    addCurrentUserCredentials(creds)
  }

  override def getSecretKeyFromUserCredentials(key: String): Array[Byte] = {
    val credentials = getCurrentUserCredentials()
    if (credentials != null) credentials.getSecretKey(new Text(key)) else null
  }

  /**
   * Get the list of namenodes the user may access.
    * 获取用户可以访问的名称节点列表
   */
  def getNameNodesToAccess(sparkConf: SparkConf): Set[Path] = {
    sparkConf.get("spark.yarn.access.namenodes", "")
      .split(",")
      .map(_.trim())
      .filter(!_.isEmpty)
      .map(new Path(_))
      .toSet
  }

  def getTokenRenewer(conf: Configuration): String = {
    val delegTokenRenewer = Master.getMasterPrincipal(conf)
    logDebug("delegation token renewer is: " + delegTokenRenewer)
    if (delegTokenRenewer == null || delegTokenRenewer.length() == 0) {
      val errorMessage = "Can't get Master Kerberos principal for use as renewer"
      logError(errorMessage)
      throw new SparkException(errorMessage)
    }
    delegTokenRenewer
  }

  /**
   * Obtains tokens for the namenodes passed in and adds them to the credentials.
    * 获取传入的名称节点的标记并将其添加到凭据
   */
  def obtainTokensForNamenodes(
    paths: Set[Path],
    conf: Configuration,
    creds: Credentials,
    renewer: Option[String] = None
  ): Unit = {
    if (UserGroupInformation.isSecurityEnabled()) {
      val delegTokenRenewer = renewer.getOrElse(getTokenRenewer(conf))
      paths.foreach { dst =>
        val dstFs = dst.getFileSystem(conf)
        logInfo("getting token for namenode: " + dst)
        dstFs.addDelegationTokens(delegTokenRenewer, creds)
      }
    }
  }

  private[spark] override def startExecutorDelegationTokenRenewer(sparkConf: SparkConf): Unit = {
    tokenRenewer = Some(new ExecutorDelegationTokenUpdater(sparkConf, conf))
    tokenRenewer.get.updateCredentialsIfRequired()
  }

  private[spark] override def stopExecutorDelegationTokenRenewer(): Unit = {
    tokenRenewer.foreach(_.stop())
  }

  private[spark] def getContainerId: ContainerId = {
    val containerIdString = System.getenv(ApplicationConstants.Environment.CONTAINER_ID.name())
    ConverterUtils.toContainerId(containerIdString)
  }

  /**
   * Obtains token for the Hive metastore, using the current user as the principal.
   * Some exceptions are caught and downgraded to a log message.
    * 获取Hive Metastore的令牌,使用当前用户作为principal,捕获一些异常并将其降级为日志消息。
   * @param conf hadoop configuration; the Hive configuration will be based on this
    *             hadoop配置, Hive配置将基于此
   * @return a token, or `None` if there's no need for a token (no metastore URI or principal
   *         in the config), or if a binding exception was caught and downgraded.
   */
  def obtainTokenForHiveMetastore(conf: Configuration): Option[Token[DelegationTokenIdentifier]] = {
    try {
      obtainTokenForHiveMetastoreInner(conf, UserGroupInformation.getCurrentUser().getUserName)
    } catch {
      case e: ClassNotFoundException =>
        logInfo(s"Hive class not found $e")
        logDebug("Hive class not found", e)
        None
    }
  }

  /**
   * Inner routine to obtains token for the Hive metastore; exceptions are raised on any problem.
    * 获取Hive Metastore的令牌的内部例程; 任何问题都会引发异常
   * @param conf hadoop configuration; the Hive configuration will be based on this.
    *             hadoop配置; Hive配置将基于此
   * @param username the username of the principal requesting the delegating token.
   * @return a delegation token
   */
  private[yarn] def obtainTokenForHiveMetastoreInner(conf: Configuration,
      username: String): Option[Token[DelegationTokenIdentifier]] = {
    val mirror = universe.runtimeMirror(Utils.getContextOrSparkClassLoader)

    // the hive configuration class is a subclass of Hadoop Configuration, so can be cast down
    // to a Configuration and used without reflection
    //hive配置类是Hadoop配置的子类,因此可以转换为配置并在没有反射的情况下使用
    val hiveConfClass = mirror.classLoader.loadClass("org.apache.hadoop.hive.conf.HiveConf")
    // using the (Configuration, Class) constructor allows the current configuratin to be included
    // in the hive config. 在配置单元配置中
    val ctor = hiveConfClass.getDeclaredConstructor(classOf[Configuration],
      classOf[Object].getClass)
    val hiveConf = ctor.newInstance(conf, hiveConfClass).asInstanceOf[Configuration]
    val metastoreUri = hiveConf.getTrimmed("hive.metastore.uris", "")

    // Check for local metastore 检查当地的Metastore
    if (metastoreUri.nonEmpty) {
      require(username.nonEmpty, "Username undefined")
      val principalKey = "hive.metastore.kerberos.principal"
      val principal = hiveConf.getTrimmed(principalKey, "")
      require(principal.nonEmpty, "Hive principal $principalKey undefined")
      logDebug(s"Getting Hive delegation token for $username against $principal at $metastoreUri")
      val hiveClass = mirror.classLoader.loadClass("org.apache.hadoop.hive.ql.metadata.Hive")
      val closeCurrent = hiveClass.getMethod("closeCurrent")
      try {
        // get all the instance methods before invoking any
        //在调用any之前获取所有实例方法
        val getDelegationToken = hiveClass.getMethod("getDelegationToken",
          classOf[String], classOf[String])
        val getHive = hiveClass.getMethod("get", hiveConfClass)

        // invoke
        val hive = getHive.invoke(null, hiveConf)
        val tokenStr = getDelegationToken.invoke(hive, username, principal).asInstanceOf[String]
        val hive2Token = new Token[DelegationTokenIdentifier]()
        hive2Token.decodeFromUrlString(tokenStr)
        Some(hive2Token)
      } finally {
        Utils.tryLogNonFatalError {
          closeCurrent.invoke(null)
        }
      }
    } else {
      logDebug("HiveMetaStore configured in localmode")
      None
    }
  }
}

object YarnSparkHadoopUtil {
  // Additional memory overhead 额外的内存开销
  // 10% was arrived at experimentally. In the interest of minimizing memory waste while covering
  // the common cases. Memory overhead tends to grow with container size.
  //10％是通过实验得出的,为了最大限度地减少内存浪费,同时涵盖常见案例,内存开销随容器大小而增加。

  val MEMORY_OVERHEAD_FACTOR = 0.10
  val MEMORY_OVERHEAD_MIN = 384

  val ANY_HOST = "*"

  val DEFAULT_NUMBER_EXECUTORS = 2

  // All RM requests are issued with same priority : we do not (yet) have any distinction between
  // request types (like map/reduce in hadoop for example)
  val RM_REQUEST_PRIORITY = Priority.newInstance(1)

  def get: YarnSparkHadoopUtil = {
    val yarnMode = java.lang.Boolean.valueOf(
      System.getProperty("SPARK_YARN_MODE", System.getenv("SPARK_YARN_MODE")))
    if (!yarnMode) {
      throw new SparkException("YarnSparkHadoopUtil is not available in non-YARN mode!")
    }
    SparkHadoopUtil.get.asInstanceOf[YarnSparkHadoopUtil]
  }
  /**
   * Add a path variable to the given environment map.
    * 将路径变量添加到给定的环境映射
   * If the map already contains this key, append the value to the existing value instead.
    * 如果Map已包含此键,请将值附加到现有值。
   */
  def addPathToEnvironment(env: HashMap[String, String], key: String, value: String): Unit = {
    val newValue = if (env.contains(key)) { env(key) + getClassPathSeparator  + value } else value
    env.put(key, newValue)
  }

  /**
   * Set zero or more environment variables specified by the given input string.
    * 设置由给定输入字符串指定的零个或多个环境变量
   * The input string is expected to take the form "KEY1=VAL1,KEY2=VAL2,KEY3=VAL3".
   */
  def setEnvFromInputString(env: HashMap[String, String], inputString: String): Unit = {
    if (inputString != null && inputString.length() > 0) {
      val childEnvs = inputString.split(",")
      val p = Pattern.compile(environmentVariableRegex)
      for (cEnv <- childEnvs) {
        val parts = cEnv.split("=") // split on '='
        val m = p.matcher(parts(1))
        val sb = new StringBuffer
        while (m.find()) {
          val variable = m.group(1)
          var replace = ""
          if (env.get(variable) != None) {
            replace = env.get(variable).get
          } else {
            // if this key is not configured for the child .. get it from the env
            //如果没有为孩子配置此密钥..从env获取它
            replace = System.getenv(variable)
            if (replace == null) {
            // the env key is note present anywhere .. simply set it
              //env键是注释出现在任何地方..只需设置它
              replace = ""
            }
          }
          m.appendReplacement(sb, Matcher.quoteReplacement(replace))
        }
        m.appendTail(sb)
        // This treats the environment variable as path variable delimited by `File.pathSeparator`
        // This is kept for backward compatibility and consistency with Hadoop's behavior
        addPathToEnvironment(env, parts(0), sb.toString)
      }
    }
  }

  private val environmentVariableRegex: String = {
    if (Utils.isWindows) {
      "%([A-Za-z_][A-Za-z0-9_]*?)%"
    } else {
      "\\$([A-Za-z_][A-Za-z0-9_]*)"
    }
  }

  /**
   * Escapes a string for inclusion in a command line executed by Yarn. Yarn executes commands
   * using `bash -c "command arg1 arg2"` and that means plain quoting doesn't really work. The
   * argument is enclosed in single quotes and some key characters are escaped.
    *
   *转义字符串以包含在Yarn执行的命令行中,Yarn使用`bash -c“命令arg1 arg2”`执行命令,
    * 这意味着简单引用不起作用,参数用单引号括起来,一些关键字符被转义。
    *
   * @param arg A single argument.
   * @return Argument quoted for execution via Yarn's generated shell script.
   */
  def escapeForShell(arg: String): String = {
    if (arg != null) {
      val escaped = new StringBuilder("'")
      for (i <- 0 to arg.length() - 1) {
        arg.charAt(i) match {
          case '$' => escaped.append("\\$")
          case '"' => escaped.append("\\\"")
          case '\'' => escaped.append("'\\''")
          case c => escaped.append(c)
        }
      }
      escaped.append("'").toString()
    } else {
      arg
    }
  }

  def getApplicationAclsForYarn(securityMgr: SecurityManager)
      : Map[ApplicationAccessType, String] = {
    Map[ApplicationAccessType, String] (
      ApplicationAccessType.VIEW_APP -> securityMgr.getViewAcls,
      ApplicationAccessType.MODIFY_APP -> securityMgr.getModifyAcls
    )
  }

  /**
   * Expand environment variable using Yarn API.
    * 使用Yarn API扩展环境变量
   * If environment.$$() is implemented, return the result of it.
   * Otherwise, return the result of environment.$()
   * Note: $$() is added in Hadoop 2.4.
   */
  private lazy val expandMethod =
    Try(classOf[Environment].getMethod("$$"))
      .getOrElse(classOf[Environment].getMethod("$"))

  def expandEnvironment(environment: Environment): String =
    expandMethod.invoke(environment).asInstanceOf[String]

  /**
   * Get class path separator using Yarn API.
    * 使用Yarn API获取类路径分隔符
   * If ApplicationConstants.CLASS_PATH_SEPARATOR is implemented, return it.
   * Otherwise, return File.pathSeparator
   * Note: CLASS_PATH_SEPARATOR is added in Hadoop 2.4.
   */
  private lazy val classPathSeparatorField =
    Try(classOf[ApplicationConstants].getField("CLASS_PATH_SEPARATOR"))
      .getOrElse(classOf[File].getField("pathSeparator"))

  def getClassPathSeparator(): String = {
    classPathSeparatorField.get(null).asInstanceOf[String]
  }

  /**
   * Getting the initial target number of executors depends on whether dynamic allocation is
   * enabled.获取执行程序的初始目标数取决于是否启用了动态分配。
   * If not using dynamic allocation it gets the number of executors reqeusted by the user.
    * 如果不使用动态分配,则获取用户需要的执行程序数,
   */
  def getInitialTargetExecutorNumber(
      conf: SparkConf,
      numExecutors: Int = DEFAULT_NUMBER_EXECUTORS): Int = {
    if (Utils.isDynamicAllocationEnabled(conf)) {
      val minNumExecutors = conf.getInt("spark.dynamicAllocation.minExecutors", 0)
      val initialNumExecutors =
        conf.getInt("spark.dynamicAllocation.initialExecutors", minNumExecutors)
      val maxNumExecutors = conf.getInt("spark.dynamicAllocation.maxExecutors", Int.MaxValue)
      require(initialNumExecutors >= minNumExecutors && initialNumExecutors <= maxNumExecutors,
        s"initial executor number $initialNumExecutors must between min executor number" +
          s"$minNumExecutors and max executor number $maxNumExecutors")

      initialNumExecutors
    } else {
      //System.getenv()和System.getProperties()的区别
      //System.getenv() 返回系统环境变量值 设置系统环境变量：当前登录用户主目录下的".bashrc"文件中可以设置系统环境变量
      //System.getProperties() 返回Java进程变量值 通过命令行参数的"-D"选项
      val targetNumExecutors =
        sys.env.get("SPARK_EXECUTOR_INSTANCES").map(_.toInt).getOrElse(numExecutors)
      // System property can override environment variable.
      conf.getInt("spark.executor.instances", targetNumExecutors)
    }
  }
}

