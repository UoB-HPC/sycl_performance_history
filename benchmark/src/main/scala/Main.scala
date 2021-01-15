import SC.RichFile
import better.files.File

import java.nio.file.attribute.PosixFilePermission
import java.time.format.DateTimeFormatter
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
      workingDir: File,
      clIncludeDir: File
  ): (() => Int, () => Int) = {

    workingDir.createDirectoryIfNotExists(createParents = true)

    val id = s"${project.name}-${platform.name}-${sycl.key}"

    val buildsDir  = workingDir / "builds"
    val resultsDir = (workingDir / "results").createDirectoryIfNotExists()
    resultsDir.list.filter(_.name.startsWith(id)).foreach(_.delete())

    val repoRoot = buildsDir / id

    val cloneAndCd = Vector(
      s"cd ${workingDir.^?}",
      s"if [[ -d ${(repoRoot / ".git").^?} ]];then",
      s"cd ${repoRoot.^?}",
      "git fetch",
      "else",
      s"""git clone -b "${project.gitRepo._2}" "${project.gitRepo._1}" ${repoRoot.^?} """,
      s"cd ${repoRoot.^?}",
      "fi"
    )

    val RunSpec(prelude, build, run) =
      project.run(Context(wd = repoRoot, clHeaderInclude = clIncludeDir), platform, sycl)

    val jobFile = repoRoot / s"_run.job"
    val logFile = workingDir / "logs" / s"$id.log"
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
      logFile.createFileIfNotExists(createParents = true).clear()
      println(s"`${xs.mkString(" ")}` &> $logFile")
      val logger = ProcessLogger(logFile.toJava)
      val code   = xs ! logger
      logger.flush()
      logger.close()
      println(
        s"Process exited with $code for `${xs.mkString(" ")}` &> $logFile(${logFile.size() / 1024}KB)"
      )
      code
    }

    def prepareProc() = spawn("/bin/bash", s"$script", "prepare")
    def runProc()     = spawn("/bin/bash", s"$script", "run")

    if (platform.hasQueue) {
      (() => {
        val code = prepareProc()
        if (code == 0) {
          runProc()
        } else {
          println(
            s"Preparation terminated with non-zero exit code $code, not submitting, see $logFile"
          )
          code
        }
      }) -> (() => 0)
    } else {
      (() => prepareProc()) -> (() => runProc())
    }

  }

  case class RunSpec(prelude: Vector[String], build: Vector[String], run: Vector[String])

  case class Context(wd: File, clHeaderInclude: File)

  case class Project(
      name: String,
      abbr: String,
      gitRepo: (String, String),
      timeout: Duration,
      extractResult: String => Either[String, String],
      run: PartialFunction[(Context, Platform, Sycl), RunSpec]
  )

  implicit class RichIterator[A](private val xs: Iterator[A]) extends AnyVal {
    def ensureOne(msg: => String): Either[String, A] = {
      val ys = xs.toSeq
      if (ys.size != 1) {
        Left(s"Expected 1 but got ${ys.size}: $msg\nResults: \n${ys.mkString(",")}")
      } else {
        Right(ys.head)
      }
    }
  }

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
    val oclheaders = File("../ocl_headers").createDirectoryIfNotExists()

    def help() = println(s"""
                 |help                     - print this help
                 |prime                    - download/copy compilers and prime them for use
                 |  <pool:dir>               - directory containing ComputeCpp tarballs
                 |list                     - lists all SYCL compilers, platforms,  and projects
                 |bench|tab                  - run benchmarks or tabulate existing results
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
        Sycl.primeCLHeaders(oclheaders, "include")
      case op :: project :: syclGlob :: platformGlob :: outDir :: parN :: Nil =>
        val out           = File(outDir).createDirectoryIfNotExists()
        val syclRegex     = globToRegexLite(syclGlob)
        val platformRegex = globToRegexLite(platformGlob)

        val projects = project.toLowerCase match {
          case "all"  => Projects
          case needle => Projects.filter(_.name == needle)
        }
        val sycls     = Sycl.list(oclcpu, dpcpp, computecpp).filter(s => syclRegex.matches(s.key))
        val platforms = Platforms.filter(p => platformRegex.matches(p.name))

        op match {
          case "tab" =>
            val resultsDir = (out / "results").createDirectoryIfNotExists()
            for {
              project       <- projects
              platform      <- platforms
              (name, sycls) <- sycls.groupBy(_.name)
            } {
              // per project-platform
              // impl, released, value
              val rows = for {
                sycl <- sycls
                result = resultsDir / s"${project.name}-${platform.name}-${sycl.key}.out"
                if result.exists
              } yield List(
                sycl.ver,
                sycl.released.format(DateTimeFormatter.ISO_LOCAL_DATE),
                project.extractResult(result.contentAsString) match {
                  case Left(err) =>
                    println(s"Extraction error in ${result.name}: \n$err")
                    "NaN"
                  case Right(x) => x
                }
              ).mkString(",")
              if (rows.nonEmpty) {
                val csv = resultsDir / s"${project.name}-${platform.name}-$name.csv"
                println(s"$csv = ${rows.size} rows")
                csv.overwrite(rows.mkString("\n"))
              }
            }
          case "bench" =>
            val (preps, runs) =
              (for {
                project  <- projects
                sycl     <- sycls
                platform <- platforms
              } yield runProject(project, platform, sycl, out, oclheaders / "include")).unzip

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

        }

      case "help" :: Nil => help()
      case bad =>
        println(s"Unsupported args: ${bad.mkString(" ")}")
        help()
    }
  }

  def processResults(
      project: Seq[Project],
      platform: Seq[Platform],
      sycls: Seq[Sycl],
      workingDir: File
  ) = {}

  def main(args: Array[String]): Unit =
    run(args.toList)

//    run(
//      "bench" :: "babelstream" :: "computecpp*-oclcpu-202012" :: "amd-local" :: "./test" :: sys.runtime.availableProcessors.toString :: Nil
//    )

//    run("prime" :: "/home/tom/sycl_performance_history/computecpp/" :: Nil)

//    run(args.toList)
//    run(
//      "tab" :: "cloverleaf" :: "*" :: "*" :: "/home/tom/Desktop/res/" :: "1" :: Nil
//    )

  //      "bench" :: "all" :: "dpcpp-2021*-oclcpu-202012" :: "local-amd" :: "./test" :: sys.runtime.availableProcessors.toString :: Nil

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
