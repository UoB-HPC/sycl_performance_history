import better.files.File

import scala.collection.parallel.CollectionConverters._
import scala.util.matching.Regex

//noinspection TypeAnnotation
object Main {

  def runProject(
      project: Project,
      platform: Platform,
      sycl: Sycl,
      workingDir: File
  ): (() => Int, () => Int) = {

    workingDir.createDirectoryIfNotExists(createParents = true)

    val id = s"${project.name}-${platform.name}-${sycl.key}"

    val buildsDir  = workingDir / "builds"
    val resultsDir = (workingDir / "results").createDirectoryIfNotExists()
    resultsDir.list.filter(_.name.startsWith(id)).foreach(_.delete())

    val repoRoot = (buildsDir / id).createDirectoryIfNotExists().clear()

    val cloneAndCd = Vector(
      s"""cd "$workingDir" """,
      s"""git clone -b ${project.gitRepo._2} "${project.gitRepo._1}" "$repoRoot" """,
      s"""cd "$repoRoot" """
    )

    val (compileAndPrepare, runDir, exec) = project.run(repoRoot, platform, sycl)

    val submitJob = platform.submit(
      Platform.JobSpec(
        jobFile = (buildsDir / s"run-$id.job"),
        name = id,
        runDir = runDir,
        commands = exec,
        outPrefix = resultsDir / id
      )
    )

    val scriptContent =
      s"""|#!/bin/bash"
          |set -eu
          |
          |prepare() {
          |echo "[STAGING]$id: preparing..."
          |${(cloneAndCd ++ compileAndPrepare).mkString("\n")}
          |echo "[STAGING]$id: preparation complete"
          |}
          |
          |run() {
          |echo "[STAGING]$id: submitting..."
          |${submitJob.mkString("\n")}
          |echo "[STAGING]$id: submitted"
          |}
          |
          |case $$1 in
          | prepare) "$$@"; exit;;
          | run)     "$$@"; exit;;
          |esac
          |
          |prepare
          |run
          |
          |""".stripMargin

    val script = buildsDir / s"benchmark-$id.sh"
    script.createFileIfNotExists().overwrite(scriptContent)
    import scala.sys.process._

    (
      () => Seq("/bin/bash", s"$script", "prepare").!,
      () => Seq("/bin/bash", s"$script", "run").!
    )

  }

  case class Project(
      name: String,
      gitRepo: (String, String),
      run: PartialFunction[(File, Platform, Sycl), (Vector[String], File, Vector[String])]
  )

  val Projects = Vector(Bude.Def, CloverLeaf.Def, BabelStream.Def)

  def globToRegexLite(glob: String): Regex =
    ("^" + glob.flatMap {
      case '*'  => ".*"
      case '?'  => "."
      case '.'  => "\\."
      case '\\' => "\\\\"
      case c    => c.toString
    } + "$").r

  def run(args: List[String]): Unit = {

    //  def loadOneAPI(path: String) =
    //    Vector("set +u", s"""source "$path" --force || true""", "set -u")
    //  val OneAPIBase      = "/lustre/projects/bristol/modules/intel/oneapi/2021.1"
    //  val OneAPIOpenCLLib = s"$OneAPIBase/compiler/2021.1.1/linux/lib/libOpenCL.so.1"

    val oclcpu     = File("../oclcpu").createDirectoryIfNotExists()
    val dpcpp      = File("../dpcpp").createDirectoryIfNotExists()
    val computecpp = File("../computecpp").createDirectoryIfNotExists()

    def help() = println(s"""
                 |help                     - print this help
                 |prime                    - download/copy compilers and prime them for use
                 |  <pool:dir>               - directory containing ComputeCpp tarballs
                 |list                     - lists all SYCL compilers and projects
                 |bench                    - run benchmarks
                 |  [<project:string>|all]   - the projects to run
                 |  <sycl:glob>              - blob pattern of the sycl config tuple to use
                 |  <out:dir>                - output directory for results and intermediate build files
                 |  <par:bool>               - compile projects in parallel
                 |""".stripMargin)

    def resolve(path: String, name: String) = {
      val f = File(path)
      if (f.notExists) throw new Exception(s"$name $f does not exist")
      f
    }

    args match {
      case "list" :: Nil =>
        val syclImpls = Sycl.list(oclcpu, dpcpp, computecpp)
        println(s"""
                   |projects  :${Projects
          .map(p => s"${p.name} (${p.gitRepo})")
          .mkString("\n\t", "\n\t", "")}
                   |sycl impl :${syclImpls
          .map(s => s"${s.key} (${s.paths.map { case (k, p) => s"$k=$p" }.mkString(", ")})")
          .mkString("\n\t", "\n\t", "")}
                   |""".stripMargin)
      case "prime" :: poolPath :: Nil =>
        val pool = resolve(poolPath, "pool")
        Sycl.primeOclCpu(oclcpu)
        Sycl.primeComputeCpp(Some(pool), computecpp)
        Sycl.primeDPCPP(dpcpp)
      case "bench" :: project :: syclGlob :: outDir :: par :: Nil =>
        val out  = File(outDir).createDirectoryIfNotExists()
        val glob = globToRegexLite(syclGlob)

        val projects = project.toLowerCase match {
          case "all"  => Projects
          case needle => Projects.filter(_.name == needle)
        }
        val sycls = Sycl.list(oclcpu, dpcpp, computecpp).filter(s => glob.matches(s.key))

        val (preps, runs) =
          (for {
            p <- projects
            s <- sycls
          } yield runProject(p, Platform.Local, s, out)).unzip

        val parallel = par.toLowerCase match {
          case "true" | "ON" | "1"   => true
          case "false" | "OFF" | "0" => false
          case bad                   => throw new Exception(s"can't parse ${bad} for boolean")
        }

        if (parallel) preps.par.foreach(_())
        else preps.foreach(_())

        println("Starting benchmarks:")
        runs.foreach(_())
      case "help" :: Nil => help()
      case bad =>
        println(s"Unsupported args: ${bad.mkString(" ")}")
        help()
    }
  }

  def main(args: Array[String]): Unit =
    run(args.toList)
//    run("prime" :: "/home/tom/sycl_performance_history/computecpp/" :: Nil)
//    run("bench" :: "all" :: "dpcpp-*-oclcpu-202006" :: "./test" :: "true" :: Nil)
//    run("bench" :: "all" :: "computecpp-*-oclcpu-202006" :: "./test" :: "true" :: Nil)

}
