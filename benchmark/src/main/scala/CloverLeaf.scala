import Main._
import Platform._
import SC._
import better.files.File

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps
object CloverLeaf {

  private def setup(
      repo: File,
      platform: Platform,
      cmakeOpts: Vector[(String, String)],
      exports: String*
  ) = {

    val mpiPath = platform match {
      case RomeIsambardMACS | CxlIsambardMACS => Platform.IsambardMACS.oneapiMPIPath
      case l: Local                           => l.oneapiMPIPath
    }

    RunSpec(
      (platform match {
        case CxlIsambardMACS | RomeIsambardMACS => IsambardMACS.setupModules
        case _                                  => Vector()
      }) ++ exports.map(e => s"export $e"),
      s"cd ${repo.^?}" +: cmake(
        target = "cloverleaf",
        build = repo / "build",
        cmakeOpts ++ Vector(
          "MPI_AS_LIBRARY"    -> "ON",
          "MPI_C_LIB_DIR"     -> s"$mpiPath/lib",
          "MPI_C_INCLUDE_DIR" -> s"$mpiPath/include",
          "MPI_C_LIB"         -> s"$mpiPath/lib/release/libmpi.so"
        ): _*
      ) :+ s"cp ${(repo / "build" / "cloverleaf").^?} ${repo.^?}",
      Vector(
        s"cd ${repo.^?}",
        s"${(repo / "cloverleaf").^?} --file ${(repo / "InputDecks" / "clover_bm16_short.in").^?} --device ${platform.deviceSubstring}"
      )
    )

  }

  val Def = Project(
    name = "cloverleaf",
    abbr = "c",
    gitRepo = "https://github.com/UoB-HPC/cloverleaf_sycl.git" -> "sycl_history",
    timeout = 2 minutes,
    run = {
      case (repo, platform, computecpp @ Sycl.ComputeCpp(_, _, _, _, _)) =>
        setup(
          repo,
          platform,
          Vector(
            "SYCL_RUNTIME"       -> "COMPUTECPP",
            "ComputeCpp_DIR"     -> computecpp.sdk,
            "CMAKE_C_COMPILER"   -> "gcc",
            "CMAKE_CXX_COMPILER" -> "g++"
          ),
          computecpp.envs: _*
        )

      case (repo, p, dpcpp @ Sycl.DPCPP(_, _, _, _, _)) =>
        setup(
          repo,
          p,
          Vector(
            "SYCL_RUNTIME"  -> "DPCPP",
            "DPCPP_BIN"     -> dpcpp.`clang++`,
            "DPCPP_INCLUDE" -> dpcpp.include,
            "CXX_EXTRA_FLAGS" -> s"-fsycl -march=${p.march} ${p match {
              case Platform.RomeIsambardMACS | Platform.CxlIsambardMACS =>
                s"--gcc-toolchain=$EvalGCCPathExpr"
              case _ => ""
            }}"
          ),
          dpcpp.envs: _*
        )
      case (wd, p, Sycl.hipSYCL(path, _, _)) => ???
    }
  )
}
