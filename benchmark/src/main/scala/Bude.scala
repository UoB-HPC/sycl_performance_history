import Main._
import SC._
import better.files.File

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

object Bude {

  def setup(wd: File, p: Platform, cmakeOpts: Vector[(String, String)], exports: String*) = {
    val repo = wd / "sycl"

    RunSpec(
      prelude = (p match {
        case Platform.CxlIsambardMACS | Platform.RomeIsambardMACS =>
          Platform.IsambardMACS.setupModules
        case Platform.IrisPro580UoBZoo => Platform.UoBZoo.setupModules
        case _                         => Vector()
      }) ++ exports.map(e => s"export $e"),
      build = s"cd ${repo.^?} " +:
        cmake(
          target = "bude",
          build = repo / "build",
          cmakeOpts ++ Vector("CMAKE_C_COMPILER" -> "gcc", "CMAKE_CXX_COMPILER" -> "g++"): _*
        ) :+
        s"cp ${(repo / "build" / "bude").^?} ${repo.^?}",
      run = Vector(
        s"cd ${wd.^?}",
        s"${(repo / "bude").^?} -n 65536 -i 8 --deck ${(wd / "data" / "bm1").^?} --wgsize 0 --device ${p.deviceSubstring}"
      )
    )

  }

  val Def = Project(
    name = "bude",
    abbr = "b",
    gitRepo = "https://github.com/UoB-HPC/bude-portability-benchmark.git" -> "master",
    timeout = 1 minute,
    run = {
      case (wd, p, computecpp @ Sycl.ComputeCpp(_, oclcpu, _, _, _)) =>
        setup(
          wd,
          p,
          Vector(
            "SYCL_RUNTIME"      -> "COMPUTECPP",
            "ComputeCpp_DIR"    -> computecpp.sdk,
            "NUM_TD_PER_THREAD" -> "2"
          ) ++ (p match {
            case Platform.RomeIsambardMACS | Platform.CxlIsambardMACS =>
              Vector("OpenCL_LIBRARY" -> (oclcpu / "x64" / "libOpenCL.so").^)
            case _ => Vector.empty
          }),
          computecpp.envs: _*
        )

      case (wd, p, dpcpp @ Sycl.DPCPP(_, _, _, _, _)) =>
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
          dpcpp.envs: _*
        )

      case (wd, p, Sycl.hipSYCL(path, _, _)) => ???
    }
  )
}
