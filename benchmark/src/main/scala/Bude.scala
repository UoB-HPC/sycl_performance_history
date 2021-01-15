import Main._
import SC._
import better.files.File

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

object Bude {

  def setup(wd: File, platform: Platform, cmakeOpts: Vector[(String, String)], exports: String*) = {
    val repo = wd / "sycl"

    RunSpec(
      prelude = platform.setupModules ++ exports.map(e => s"export $e"),
      build = s"cd ${repo.^?} " +:
        cmake(
          target = "bude",
          build = repo / "build",
          cmakeOpts ++ Vector("CMAKE_C_COMPILER" -> "gcc", "CMAKE_CXX_COMPILER" -> "g++"): _*
        ) :+
        s"cp ${(repo / "build" / "bude").^?} ${repo.^?}",
      run = Vector(
        s"cd ${wd.^?}",
        s"${(repo / "bude").^?} -n 65536 -i 8 " +
          s"--deck ${(wd / "data" / "bm1").^?} " +
          s"--wgsize 0 " +
          s"""--device "${platform.deviceSubstring}""""
      )
    )

  }

  val ErrorThresholdPct: Double = 1.0

  val Def = Project(
    name = "bude",
    abbr = "b",
    gitRepo = "https://github.com/UoB-HPC/bude-portability-benchmark.git" -> "master",
    timeout = 1 minute,
    extractResult = out => {
      out.linesIterator
        .collect { case s"Largest difference was ${pct}%${_}" => pct.trim.toDoubleOption }
        .flatten
        .ensureOne("difference")
        .flatMap {
          case x if x > ErrorThresholdPct =>
            Left(s"Error exceeded threshold, got $x, need < $ErrorThresholdPct")
          case _ =>
            out.linesIterator
              .collect { case s"- Kernel time: ${ms} ms" => ms.trim }
              .ensureOne("kernel time")
        }
    },
    run = {
      case (wd, p, computecpp: Sycl.ComputeCpp) =>
        setup(
          wd,
          p,
          Vector(
            "SYCL_RUNTIME"      -> "COMPUTECPP",
            "ComputeCpp_DIR"    -> computecpp.sdk,
            "NUM_TD_PER_THREAD" -> "2"
          ) ++ (p match {
            case Platform.RomeIsambardMACS | Platform.CxlIsambardMACS | Platform.IrisPro580UoBZoo =>
              Vector("OpenCL_LIBRARY" -> (computecpp.oclcpu / "x64" / "libOpenCL.so").^)
            case _ => Vector.empty
          }),
          (if (p.isCPU) computecpp.cpuEnvs else computecpp.gpuEnvs): _*
        )

      case (wd, p, dpcpp: Sycl.DPCPP) =>
        setup(
          wd,
          p,
          Vector(
            "SYCL_RUNTIME"  -> "DPCPP",
            "DPCPP_BIN"     -> dpcpp.`clang++`,
            "DPCPP_INCLUDE" -> dpcpp.include,
            "CXX_EXTRA_FLAGS" -> s"-fsycl -march=${p.march} ${p match {
              case Platform.RomeIsambardMACS | Platform.CxlIsambardMACS | Platform.IrisPro580UoBZoo =>
                s"--gcc-toolchain=$EvalGCCPathExpr"
              case _ => ""
            }}",
            "NUM_TD_PER_THREAD" -> "2"
          ),
          (if (p.isCPU) dpcpp.cpuEnvs else dpcpp.gpuEnvs): _*
        )

      case (wd, p, hipsycl: Sycl.hipSYCL) => ???
    }
  )
}
