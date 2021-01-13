import SC.RichFile
import better.files.File

import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.ForkJoinPool
import scala.collection.parallel.CollectionConverters._
import scala.collection.parallel.ForkJoinTaskSupport
import scala.concurrent.duration.Duration
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

    val repoRoot = buildsDir / id

    val cloneAndCd = Vector(
      s"cd ${workingDir.^?}",
      s"if [[ -d ${repoRoot.^?} ]];then",
      s"cd ${repoRoot.^?}",
      "git fetch",
      "else",
      s"""git clone -b "${project.gitRepo._2}" "${project.gitRepo._1}" ${repoRoot.^?} """,
      s"cd ${repoRoot.^?}",
      "fi"
    )

    val RunSpec(prelude, build, run) = project.run(repoRoot, platform, sycl)

    val jobFile = repoRoot / s"run.job"
    val (jobScript, submit) = platform.submit(
      Platform.JobSpec(
        name = s"${project.abbr}-${platform.abbr}-${sycl.abbr}",
        timeout = project.timeout,
        commands = run,
        outPrefix = resultsDir / id
      )
    )

    val scriptContent =
      s"""|#!/bin/bash
          |set -eu
          |
		  |${prelude.mkString("\n")}
		  |
          |prepare() {
          |echo "[STAGING]$id: preparing..."
          |${(cloneAndCd ++ build).mkString("\n")}
          |echo "[STAGING]$id: preparation complete"
          |}
          |
          |run() {
          |echo "[STAGING]$id: submitting..."
		  |
		  |cat > $$${jobFile.^?} <<- "EOM"
          |$jobScript
          |EOM
		  |chmod +x ${jobFile.^?}
          |${submit(jobFile).mkString("\n")}
          |echo "[STAGING]$id: submitted"
          |}
          |
          |case $${1:-} in
          | prepare) "$$@"; exit;;
          | run)     "$$@"; exit;;
		  | *)       prepare;run;;
          |esac
          |
          |""".stripMargin

    val script = workingDir / s"benchmark-$id.sh"
    script.overwrite(scriptContent)
    script.addPermission(PosixFilePermission.OWNER_EXECUTE)

    def spawn(xs: String*) = {
      import scala.sys.process._
      val code = xs.!
      println(s"Process exited with $code for `${xs.mkString(" ")}`")
      code
    }

    def prepareProc = spawn("/bin/bash", s"$script", "prepare")
    def runProc     = spawn("/bin/bash", s"$script", "run")

    if (platform.hasQueue) {
      (() => { prepareProc; runProc }) -> (() => 0)
    } else {
      (() => prepareProc) -> (() => runProc)
    }

  }

  case class RunSpec(prelude: Vector[String], build: Vector[String], run: Vector[String])

  case class Project(
      name: String,
      abbr: String,
      gitRepo: (String, String),
      timeout: Duration,
      run: PartialFunction[(File, Platform, Sycl), RunSpec]
  )

  val Projects = Vector(Bude.Def, CloverLeaf.Def, BabelStream.Def)
  val Platforms = Vector(
    Platform.RomeIsambardMACS,
    Platform.CxlIsambardMACS,
    Platform.IrisPro580UoBZoo,
    Platform.LocalAMDCPU,
    Platform.LocalIntelCPU,
    Platform.LocalIntelGPU
  )

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
                 |list                     - lists all SYCL compilers, platforms,  and projects
                 |bench                    - run benchmarks
                 |  [<project:string>|all]   - the projects to run, see `list`
                 |  <sycl:glob>              - blob pattern of the sycl config tuple to use, see `list`
                 |  <platform:glob>          - the platforms to run on, see `list` 
                 |  <out:dir>                - output directory for results and intermediate build files
                 |  <par:int>                - compile projects with the specified parallelism
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
                   |Sycl impl. :${syclImpls
          .map(s =>
            s"${s.key} (${s.abbr}, ${s.paths.map { case (k, p) => s"$k=$p" }.mkString(", ")})"
          )
          .mkString("\n\t", "\n\t", "")}
                   |
				   |Projects   :${Projects
          .map(p => s"${p.name} (${p.gitRepo})")
          .mkString("\n\t", "\n\t", "")}
				   |
				   |Platforms  :${Platforms
          .map(p => s"${p.name} (${p.abbr})")
          .mkString("\n\t", "\n\t", "")} 
				   |
                   |""".stripMargin)
      case "prime" :: poolPath :: Nil =>
        val pool = resolve(poolPath, "pool")
        Sycl.primeOclCpu(oclcpu)
        Sycl.primeComputeCpp(Some(pool), computecpp)
        Sycl.primeDPCPP(dpcpp)
      case "bench" :: project :: syclGlob :: platformGlob :: outDir :: parN :: Nil =>
        val out           = File(outDir).createDirectoryIfNotExists()
        val syclRegex     = globToRegexLite(syclGlob)
        val platformRegex = globToRegexLite(platformGlob)

        val (preps, runs) =
          (for {
            proj <- project.toLowerCase match {
              case "all"  => Projects
              case needle => Projects.filter(_.name == needle)
            }
            sycl <- Sycl.list(oclcpu, dpcpp, computecpp).filter(s => syclRegex.matches(s.key))

            platform <- Platforms.filter(p => platformRegex.matches(p.name))

          } yield runProject(proj, platform, sycl, out)).unzip

        val parallel = parN.toLowerCase match {
          case "false" | "OFF" | "0" => 1
          case n =>
            n.toIntOption match {
              case Some(x) => x
              case None    => throw new Exception(s"can't parse $n for parN")
            }
        }

        if (parallel > 1) {
          val parxs = preps.par
          parxs.tasksupport = new ForkJoinTaskSupport(new ForkJoinPool(parallel))
          parxs.foreach(_())
        } else preps.foreach(_())

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
  //    run("list" :: Nil)
//    run(
//      "bench" :: "all" :: "dpcpp-2021*-oclcpu-202012" :: "local-amd" :: "./test" :: sys.runtime.availableProcessors.toString :: Nil
//      "bench" :: "bude" :: "dpcpp-2021*-oclcpu-*" :: "local-amd" :: "./test" :: sys.runtime.availableProcessors.toString :: Nil
//    )
//    run("bench" :: "cloverleaf" :: "computecpp*-oclcpu-*" :: "local-amd" :: "./test" :: "4" :: Nil)
//      run("prime" :: "/home/tom/sycl_performance_history/computecpp/" :: Nil)
  //    run("bench" :: "all" :: "dpcpp-*-oclcpu-202006" :: "./test" :: "true" :: Nil)
  //    run("bench" :: "all" :: "computecpp-*-oclcpu-202006" :: "./test" :: "true" :: Nil)

}
