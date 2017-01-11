package compose

import ammonite.ops._
import Utils._

/**
  * Created by wangzx on 16/7/4.
  *
  * TODO add log.
  */
case class Service(name: String, projectName: String, gitURL: String, gitBranch: String,
                   relatedSources: List[Service], depends: List[String], buildDepends: List[Service],
                   image: String) {
  /**
    * 把当前分支的commitId写入.local.gitid.ini
    *
    * @param context
    */
  def updateGid(context: Context): Unit = {
    val projectPath: Path = Path(projectName, Path(context.workspace))

    val commitId = getGitCommitId(projectPath)

    write.append(cwd / Main.gitIdIni.name, s"${name.replace('-', '_')}=$commitId\n")

    println(s"$projectName=$commitId")
  }

  /**
    * 升级报告, git diff master
    * 一般用于灰度
    *
    * @param context
    */
  def sdiff(context: Context): Unit = {
    println(s"${{getSpaces(context)}}$projectName diff begin")

    if (gitBranch != "master") {
      rm ! Main.reportPath / s"$gitBranch-$projectName.diff"
      val projectPath: Path = Path(projectName, Path(context.workspace))
      val result = intercept(%%.git("diff", "master", gitBranch)(projectPath))
      if (result.exitCode != 0) System.exit(result.exitCode)
      val diffContent = result.out.trim
      write.append(Main.reportPath / s"$gitBranch-$projectName.diff", diffContent)
    }

    println(s"${{getSpaces(context)}}$projectName diff done")
  }

  def slog(context: Context, gids: Map[String, String]): Unit = {
    println(s"${{getSpaces(context)}}$projectName diff-log begin")

    val gid = gids(name.replace('-', '_'))

    if (gitBranch != "master") {
      val projectPath: Path = Path(projectName, Path(context.workspace))
      val masterCommitId = getMasterCommitId(projectPath)
      rm ! Main.reportPath / s"$gitBranch-$projectName.change.log"
      val result = intercept(%%.git("log", s"$masterCommitId..$gid")(projectPath))
      if (result.exitCode != 0) System.exit(result.exitCode)
      val diffLog = result.out.trim
      write.append(Main.reportPath / s"$gitBranch-$projectName.change.log", diffLog)

      exitWhileFailed(name)(%%.git("checkout", gitBranch)(projectPath))
    }

    println(s"${{getSpaces(context)}}$projectName diff-log done")
  }

  /**
    * 获得某项目master分支的提交id
    *
    * @param projectPath
    * @return
    */
  def getMasterCommitId(projectPath: Path) = {
    exitWhileFailed(name)(%%.git("checkout", ".")(projectPath))
    exitWhileFailed(name)(%%.git("checkout", "master")(projectPath))
    exitWhileFailed(name)(%%.git("pull")(projectPath))

    getGitCommitId(projectPath)
  }

  /**
    * usage:
    * s-merge-by-id branch
    * 需要带分支名,不针对特定service,而是针对所有的services
    *
    * 该操作包括把代码合并到master,并得出升级报告
    *
    * @param context
    */
  def smergeById(context: Context, gid: String): Unit = {
    println(s"${{getSpaces(context)}}$projectName merge by id begin")
    val projectPath = Path(projectName, Path(context.workspace))



    //checkout to master and get master gid
    val masterCommitId = getMasterCommitId(projectPath)

    val diffFile: Path = Main.reportPath / s"$gitBranch-$projectName.diff"
    val changeLog: Path = Main.reportPath / s"$gitBranch-$projectName.change.log"
    rm ! diffFile
    rm ! changeLog

    val result = intercept(%%.git("diff", s"$masterCommitId..$gid")(projectPath))
    if (result.exitCode != 0) System.exit(result.exitCode)
    val diffContent = result.out.trim
    val logResult = intercept(%%.git("log", s"$masterCommitId..$gid")(projectPath))
    if (logResult.exitCode != 0) System.exit(logResult.exitCode)
    val changeLogContent = logResult.out.trim

    write.append(diffFile, diffContent)
    write.append(changeLog, changeLogContent)


    exitWhileFailed(name)(%%.git("merge", "--ff-only", gid)(projectPath))

    println(s"${{getSpaces(context)}}$projectName merge by id End")
  }

  /**
    * must be done after smerge
    *
    * @param context
    */
  def spush(context: Context): Unit = {
    println(s"$projectName push begin")
    val projectPath: Path = Path(projectName, Path(context.workspace))


    val branch = getNonDetachGitBranch(projectPath)
    assert(branch == "master",
      s"$projectName is not within master branch, please do smerge first")

    exitWhileFailed(name)(%%.git("push")(projectPath))

    println(s"$projectName push end")
  }

  /**
    * 根据commitId来拉代码
    *
    * @param context
    * @param gids key=serviceName, value=gid
    */
  def spullByCommit(context: Context, gids: Map[String, String]): Unit = {
    val gid = gids(name.replace('-', '_'))

    val workspacePath = Path(context.workspace)
    val projectPath = Path(projectName, workspacePath)

    println(s"check $projectPath")
    exitWhileFailed(name)(%%.git("checkout", gid)(projectPath))
  }

  /**
    * pull source code by branch
    *
    * @param context
    */
  def spull(context: Context): Unit = {
    println(s"checkout $name $gitURL@@$gitBranch...")

    val workspacePath = Path(context.workspace)
    val projectPath = Path(projectName, workspacePath)

    if (projectPath.toIO.exists() && projectPath.isDir) {

      val branch = getGitBranch(projectPath)

      if (branch != gitBranch) {
        // switch
        println(s"switch branch from ${branch} to ${gitBranch}")
        exitWhileFailed(name)(%%.git("fetch")(projectPath))
        exitWhileFailed(name)(%%.git("checkout", ".")(projectPath))
        intercept(%%.git("branch", "-d", gitBranch)(projectPath))
        exitWhileFailed(name)(%%.git("checkout", gitBranch)(projectPath))
      }
      else {
        exitWhileFailed(name)(%%.git("checkout", ".")(projectPath))
        exitWhileFailed(name)(%%.git("pull")(projectPath))
      }
    }
    else {
      // clone the project
      projectPath.toIO.mkdir()
      exitWhileFailed(name)(%%.git("clone", gitURL)(workspacePath))
      if (gitBranch != "master") {
        exitWhileFailed(name)(%%.git("checkout", gitBranch)(projectPath))
        exitWhileFailed(name)(%%.git("pull")(projectPath))
      }
    }
  }


  /**
    * @param context
    * @param gids
    */
  def smake(context: Context, gids: Map[String, String], mvnProfile: String): Unit = {
    println(s"$projectName make begin")
    val projectPath = Path(projectName, Path(context.workspace))

    // support maven project only
    if ((ls ! projectPath |? (_.name == "pom.xml")).nonEmpty) {

      // check if images has been make
      // two forms of images:
      // 1. image:${git.branch}-${xx_gid}  for biz projects which will be change frequently
      // 2. image:master for basic projects which won't be change
      val imagePattern =
        """(.*):\$\{.*\}""".r
      val realImage = try {
        val imagePattern(_imageName) = image
        s"${_imageName}:${gids(name.replace('-', '_'))}"
      } catch {
        case ex: Throwable =>
          image
      }

      val response = intercept(%%.docker("images", realImage)(cwd))
      if (response.exitCode != 0) System.exit(response.exitCode)
      if (response.out.lines.size == 1) {
        val needBuildLocally = if (!Main.skipRemoteCheck) {
          println(s"${realImage} not found locally, now check remote registry")
          val pullResult = intercept(%%.docker("pull", realImage)(cwd))
          pullResult.exitCode != 0
        } else true

        if (needBuildLocally) {
          println(s"${realImage} not found, now begin to build one, please wait...")

          val mvnOpts: List[String] = List("install", "-Dmaven.test.skip=true") ::: (if (mvnProfile.equals("")) List("-Pproduction") else List(mvnProfile))

          buildDepends.filterNot { buildDependService =>
            context.handled.contains(buildDependService.name)
          }.foreach { buildDependService =>
            println(s"${buildDependService.projectName} make begin")
            val _projectPath = Path(buildDependService.projectName, Path(context.workspace))

            // support maven project only
            if ((ls ! _projectPath |? (_.name == "pom.xml")).nonEmpty) {
              val _makeResult: Int = if (Main.isWinOs) %.`mvn.bat`(mvnOpts)(_projectPath)
              else %.mvn(mvnOpts)(_projectPath)

              if (_makeResult != 0) System.exit(_makeResult)
            }
            context.handled += buildDependService.name
          }


          val makeResult: Int = if (Main.isWinOs) %.`mvn.bat`(mvnOpts)(projectPath)
          else %.mvn(mvnOpts)(projectPath)

          if (makeResult != 0) System.exit(makeResult)
        } else println(s"$realImage exist")
      } else println(s"$realImage exist")
    }

    println(s"$projectName make done")
  }

  def sdockerPush(context: Context, gids: Map[String, String]): Unit = {
    println(s"$image push begin")
    val workspacePath = Path(context.workspace)
    val projectPath = Path(projectName, workspacePath)

    val imagePattern =
      """(.*):\$\{.*\}""".r
    val realImage = try {
      val imagePattern(_imageName) = image
      s"${_imageName}:${gids(name.replace('-', '_'))}"
    } catch {
      case ex: Throwable =>
        image
    }
    println(s"imageName:$realImage")
    exitWhileFailed(name)(%%.docker("push", realImage)(projectPath))
    println(s"$image push end")
  }

  def sclean(context: Context): Unit = {
    println(s"$projectName clean begin")
    val projectPath = Path(projectName, Path(context.workspace))
    //support maven project only
    if ((ls ! projectPath |? (_.name == "pom.xml")).nonEmpty) {
      if (Main.isWinOs) exitWhileFailed(name)(%%.`mvn.bat`("clean")(projectPath))
      else exitWhileFailed(name)(%%.mvn("clean")(projectPath))
    }
    println(s"$projectName clean end")
  }

  def sbuild(context: Context, gids: Map[String, String], mvnProfile: String): Unit = {
    println(s"$projectName build begin")
    val beginTimeInMills = System.currentTimeMillis()
    smake(context, gids, mvnProfile)
    println(s"$projectName build end, cost:${(System.currentTimeMillis() - beginTimeInMills) / 1000}")
  }

  def srebuild(context: Context, gids: Map[String, String], mvnProfile: String): Unit = {
    println(s"$projectName rebuild begin")
    val beginTimeInMills = System.currentTimeMillis()
    sclean(context)
    smake(context, gids, mvnProfile)
    println(s"$projectName rebuild end, cost:${(System.currentTimeMillis() - beginTimeInMills) / 1000}")
  }

  def getSpaces(context: Context) = if (context.relatedSources.contains(name))"  " else ""

}
