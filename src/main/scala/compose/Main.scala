package compose

import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.{Date, Properties}

import compose.Utils._

import ammonite.ops._

import scala.collection.JavaConverters


/**
  * Created by wangzx on 16/7/4.
  *
  */
object Main {

  /**
    * if set to true, all output would be redirect to console.
    */
  var debugMode = false

  /**
    * if set to true, docker images won't be pull from remote registry
    */
  var skipRemoteCheck = false

  val osType = {
    val _os = System.getenv("osName")
    if (_os != null) _os.toUpperCase
    else System.getProperty("os.name").toUpperCase
  }

  val isWinOs = osType.startsWith("WIN")

  val gitIdIni = Symbol(".local.gitid.ini")

  val versionIni = Symbol(".version.ini")

  val reportPath: Path = cwd / "report"

  // ignore services. you can skip building some services locally
  lazy val ignoreServices: List[String] = {
    loadPropertiesByIni(s".local-${getNodeName}.ini").getOrElse("ignoreServices","").split(" ").toList
  }


  def main(args: Array[String]) {

    val ymlFile = cwd / "dc-all.yml"


    val context = new Context().loadConfiguration(Array(ymlFile))
    val supportedOption = Array("-X", "-force", "-f", "-P", "-skipRemoteCheck")

    //所有服务的分支,应该是跟scompose分支一致或者是master分支
    assert(context.services.values.forall(service => (service.gitBranch == context.scomposeBranch) || (service.gitBranch == "master")),
      s"Branch should be in (${context.scomposeBranch}, master)")
    assert(context.services.values.flatMap(_.relatedSources).toSet[Service].forall(service => (service.gitBranch == context.scomposeBranch) || (service.gitBranch == "master")),
      s"Branch should be in (${context.scomposeBranch}, master)")

    if (args.exists(_ == "-X")) Main.debugMode = true
    if (args.exists(arg => (arg == "-skipRemoteCheck")||(arg == "-force")||(arg == "-f"))) Main.skipRemoteCheck = true
    val mvnProfile = args.filter(_.startsWith("-P")).headOption.getOrElse("")

    args.diff(supportedOption).filter(!_.startsWith("-P")) match {
      case Array("s-update-gid", services@_*) => supdateGid(context) // for develop only; get gid ; git push
      case Array("s-diff", services@_*) => sdiff(context, services) // git diff between current branch and master
      case Array("s-log", services@_*) => slog(context, services) // git log between gid of current branch and gid of master
      case Array("s-merge-by-gid", branches@_*) => // for production only. get gid; git diff/log by gid ; git merge branch to master;
        assert(branches.size == 1, "must specifid a branch name")
        smergeById(context, branches(0))
      case Array("s-push", services@_*) => spush(context, services) // git push
      case Array("s-pull", services@_*) => spull(context, services) // git pull
      case Array("s-pull-by-gid", services@_*) => spullById(context, services) // git pull commit
      case Array("s-clean", services@_*) => sclean(context, services) // mvn clean
      case Array("s-make", services@_*) => smake(context, services, mvnProfile) // mvn install -Dmaven.test.skip ; docker tag
      case Array("s-docker-push", services@_*) => sdockerPush(context, services) // docker push
      case Array("s-build", services@_*) => sbuild(context, services, mvnProfile) // s-make
      case Array("s-rebuild", services@_*) => srebuild(context, services, mvnProfile) //
      case Array("s-list", services@_*) => slist(context) //
      case Array("s-rollback", branches@_*) =>
        assert(branches.size == 1, "must specifid a branch name")
        srollback(context, branches(0))
      case Array("help", services@_*) => usage
      case emptyArry if (emptyArry.isEmpty)  => usage
      case _ => other(args)

    }

  }

  /**
    * Go around all services within dc-all.yml and perform git checkout & pull by branch
    *
    * @param context
    * @param services
    */
  def spull(context: Context, services: Seq[String]): Unit = {
    val todos: Seq[String] = if (!services.isEmpty) services else context.sortedServices.map(_.name)
    todos.foreach { service =>
      context.services(service).spull(context)
      println()
    }

    todos.flatMap(context.services(_).relatedSources).toSet[Service].foreach { service =>
      service.spull(context)
      println()
    }

    println("pull end")
  }


  def smake(context: Context, services: Seq[String], mvnProfile: String): Unit = {
    val gids = loadPropertiesByIni(gitIdIni.name)
    val todos: Seq[String] = if (!services.isEmpty) services else context.sortedServices.map(_.name)
    todos.foreach { service =>
      context.services(service).smake(context, gids, mvnProfile)
      println()
    }
  }

  def sclean(context: Context, services: Seq[String]): Unit = {
    val todos: Seq[String] = if (!services.isEmpty) services else context.sortedServices.map(_.name)
    todos.diff(ignoreServices).foreach { service =>
      context.services(service).sclean(context)
      println()
    }
  }

  def sbuild(context: Context, services: Seq[String], mvnProfile: String): Unit = {
    val beginTimeInMills = System.currentTimeMillis()
    val gids = loadPropertiesByIni(gitIdIni.name)
    val todos: Seq[String] = if (!services.isEmpty) services else context.sortedServices.map(_.name)
    todos.diff(ignoreServices).foreach { service =>
      context.services(service).sbuild(context, gids, mvnProfile)
      println()
    }

    println(s"sbuild done. cost:${(System.currentTimeMillis() - beginTimeInMills) / 1000}")
  }

  def srebuild(context: Context, services: Seq[String], mvnProfile: String): Unit = {
    val beginTimeInMills = System.currentTimeMillis()
    val gids = loadPropertiesByIni(gitIdIni.name)
    val todos: Seq[String] = if (!services.isEmpty) services else context.sortedServices.map(_.name)
    todos.diff(ignoreServices).foreach { service =>
      context.services(service).srebuild(context, gids, mvnProfile)
      println()
    }

    println(s"srebuild done. cost:${(System.currentTimeMillis() - beginTimeInMills) / 1000}")
  }

  def slist(context: Context): Unit = {
    context.services.values.toList.sortBy(_.name).sortBy(_.relatedSources.length).foreach{ service =>
      println(service.name)
      service.relatedSources.foreach(relatedService=>println(s" |__${relatedService.name}"))
    }
  }

  /**
    * 更新系统的gid
    *
    * @param context
    */
  def supdateGid(context: Context): Unit = {
    rm ! cwd / gitIdIni.name
    val todos: Seq[String] = context.sortedServices.map(_.name)
    todos.foreach { service =>
      context.services(service).updateGid(context)
    }

    println()

    todos.flatMap(context.services(_).relatedSources).toSet[Service].foreach { service =>
      service.updateGid(context)
    }

    exitWhileFailed("scompose")(%%.git("commit", "-am", s"update gid for ${context.scomposeBranch}")(cwd))
    println("update commit id end. If you want to submit it to gitlab, just do: git push")
  }

  def sdiff(context: Context, services: Seq[String]): Unit = {
    val todos: Seq[String] = if (!services.isEmpty) services else context.sortedServices.map(_.name)
    todos.foreach(context.services(_).sdiff(context))

    todos.flatMap(context.services(_).relatedSources).toSet[Service].foreach { service =>
      service.sdiff(context)
      println()
    }

    println("diff report end")
  }

  def slog(context: Context, services: Seq[String]): Unit = {
    val todos: Seq[String] = if (!services.isEmpty) services else context.sortedServices.map(_.name)
    val gids = loadPropertiesByIni(gitIdIni.name)
    todos.foreach(context.services(_).slog(context, gids))

    todos.flatMap(context.services(_).relatedSources).toSet[Service].foreach { service =>
      service.slog(context, gids)
      println()
    }

    println("log report end")
  }

  def spullById(context: Context, services: Seq[String]): Unit = {
    val todos: Seq[String] = if (!services.isEmpty) services else context.sortedServices.map(_.name)

    // git 游离态的问题
    spull(context, services)

    val gids = loadPropertiesByIni(gitIdIni.name)
    todos.foreach(context.services(_).spullByCommit(context, gids))

    todos.flatMap(context.services(_).relatedSources).toSet[Service].foreach { service =>
      service.spullByCommit(context, gids)
      println()
    }

    println("pull by commit end")
  }

  /**
    * 生产合并操作. 必须在@branch 分支下操作.因为master分支下加载的yml文件组成的services(其branch皆为master),
    * 无法知道到底应该合并哪些项目. 该操作完成后, 各项目处于游离态(detatch)
    * s-merge-by-gid branch
    *
    * @param context
    * @param branch
    */
  def smergeById(context: Context, branch: String): Unit = {
    val currentScomposeBranch = context.scomposeBranch
    //    assert(currentScomposeBranch.equals(branch), s"please swich to $branch, currently is $currentScomposeBranch")
    //    assert(!currentScomposeBranch.equals("master"), "can't do this operation at master branch")

    //1. merge dockercompose
    exitWhileFailed("scompose")(%%.git("checkout", ".")(cwd))
    exitWhileFailed("scompose")(%%.git("checkout", "master")(cwd))
    exitWhileFailed("scompose")(%%.git("pull")(cwd))

    val mergeResult = %.git("merge", "--ff-only", branch)(cwd)
    if (mergeResult != 0) {
      println("kscompose merge failed. please fixed the problems and try again.")
      %.git("commit", "-am", "resotre master")(cwd)
      %.git("checkout", branch)(cwd)
      %.git("branch", "-D", "master")(cwd)

      System.exit(mergeResult)
    }

    val gids = loadPropertiesByIni(gitIdIni.name)

    //记录升级内容
    val versionBuf = new StringBuffer(new SimpleDateFormat("YYYYMMdd HHmm").format(new Date()) + s" $branch\n")

    val todos: Seq[String] = context.sortedServices.map(_.name)
    todos.filter(context.services(_).gitBranch != "master").foreach { serviceName =>
      context.services(serviceName).smergeById(context, gids(serviceName.replace('-', '_')))
      versionBuf.append(s"--$serviceName\n")
    }

    todos.flatMap(context.services(_).relatedSources).toSet[Service].filter(_.gitBranch != "master")
      .foreach { service =>
        service.smergeById(context, gids(service.name.replace('-', '_')))
      }


    write.append(cwd / Main.versionIni.name, versionBuf.toString)

    val ymlFile = cwd / "dc-all.yml"

    //把yml文件中的分支名称恢复为master

    val newContent = read(ymlFile).trim.replaceAll("""@@[A-Za-z0-9\-\.\_]+""", "@@master")
    rm ! ymlFile
    write.write(ymlFile, newContent)

    exitWhileFailed("scompose")(%%.git("commit", "-am", s"restore branchName of biz projects from ${branch} to master")(cwd))

    spush(context, context.sortedServices.map(_.name))

    exitWhileFailed("scompose")(%%.git("tag", "-f", "tag_" + branch)(cwd))
    exitWhileFailed("scompose")(%%.git("push", "--tags")(cwd))

    println("merge by Id end")
  }

  /**
    * push the code to gitlab
    *
    * @param context
    * @param services
    */
  def spush(context: Context, services: Seq[String]): Unit = {
    val todos: Seq[String] = if (!services.isEmpty) services else context.sortedServices.map(_.name)
    todos.foreach(context.services(_).spush(context))

    todos.flatMap(context.services(_).relatedSources).toSet[Service].foreach(_.spush(context))

    // push dockercompose
    exitWhileFailed("scompose")(%%.git("push")(cwd))

    println("code pushed")
  }


  def sdockerPush(context: Context, services: Seq[String]): Unit = {
    val gids = loadPropertiesByIni(gitIdIni.name)
    val todos: Seq[String] = if (!services.isEmpty) services else context.sortedServices.map(_.name)
    todos.foreach(context.services(_).sdockerPush(context, gids))

    println("image pushed")
  }


  def srollback(context: Context, branch: String): Unit = {
    exitWhileFailed("scompose")(%%.git("checkout", "tag_" + branch)(cwd))

    other(Array("up", "-d"))
  }

  def loadPropertiesByIni(ini: String): Map[String, String] = {
    val gids = new Properties()
    val gitIdIni = cwd / ini
    val is = new FileInputStream(gitIdIni.toIO)
    gids.load(is)
    is.close()
    JavaConverters.propertiesAsScalaMap(gids).toMap
  }

  // Just a concept for docer-compose
  def other(args: Array[String]): Unit = {

    val nodeName = getNodeName

    val gids: Map[String, String] = loadPropertiesByIni(gitIdIni.name).map { case (k, v) => (k + "_gid", v) }
    val localIni = cwd / s".local-${nodeName}.ini"

    val _envs: Map[String, String] = loadPropertiesByIni(".local.ini") ++ gids ++ (
      if (exists ! localIni) loadPropertiesByIni(s".local-${nodeName}.ini") else Map.empty) // load from .local-$nodeName.env and build a Map

    val envs: Map[String, String] = _envs.map { case (k, v) => {
      if (v.startsWith("$")) {
        (k, _envs(v.replaceAll("""[\$\{\}]""", "")))
      } else (k, v)
    }
    }
    val cmd: Vector[String] = Vector("docker-compose", "-f", s"dc-${nodeName}.yml") ++ args

    val command = Command(cmd, envs, Shellout.executeInteractive)

    command.execute(cwd, command)
  }

  def getNodeName = {
    val prop = System.getProperty("nodeName")

    if (prop != null) prop
    else {
      val env = System.getenv("nodeName")
      if (env != null) env
      else throw new RuntimeException("please specify nodeName via -D or environment.")
    }
  }

  def usage(): Unit = {
    println(
      """usage:
    ./scompose
      -X                              -- redirect operations output to console
      -force -f                       -- skip remote check even if image not found locally
      -skipRemoteCheck                -- Deprecated by -force or -f option
      -P[profile]                     -- mvn profile support
      s-list                          -- list all services
      s-pull [service..]              -- pull by branch: git pull
      s-pull-by-gid [service..]       -- pull by commit id: git pull
      s-clean [service..]             -- mvn clean, clean all services within the .ymls by default
      s-make [service..]              -- mvn install & docker build
      s-docker-push [service..]       -- push the docker images to docker registry
      s-build [service..]             -- equals to s-make
      s-rebuild [service..]           -- equals to s-clean s-make
      s-merge                         -- merge the source code:
      s-update-gid                    -- write .local.gitid.ini
      s-diff                          -- git diff/log between current branch commitId and master commit id
      s-report                        -- send email, not implemented yet
      s-rollback                      -- rollback services
      s-version
      others -- prepare local env  && docker-compose -f ...
      """)
    System.exit(1)
  }

}
