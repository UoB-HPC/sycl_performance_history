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

    val (mpiEnvs, mpiPath) = platform match {
      case RomeIsambardMACS | CxlIsambardMACS =>
        Vector(
          prependFileEnvs("LD_LIBRARY_PATH", IsambardMACS.oneapiLibFabricPath / "lib"),
          prependFileEnvs("FI_PROVIDER_PATH", IsambardMACS.oneapiLibFabricPath / "lib" / "prov")
        ) -> IsambardMACS.oneapiMPIPath
      case IrisPro580UoBZoo =>
        Vector(
          prependFileEnvs("LD_LIBRARY_PATH", UoBZoo.oneapiLibFabricPath / "lib"),
          prependFileEnvs("FI_PROVIDER_PATH", UoBZoo.oneapiLibFabricPath / "lib" / "prov")
        ) -> UoBZoo.oneapiMPIPath
      case l: Local => Vector() -> l.oneapiMPIPath
    }

    RunSpec(
      platform.setupModules ++ (exports ++ mpiEnvs).map(e => s"export $e"),
      s"cd ${repo.^?}" +: cmake(
        target = "cloverleaf",
        build = repo / "build",
        cmakeOpts ++ Vector(
          "MPI_AS_LIBRARY"    -> "ON",
          "MPI_C_LIB_DIR"     -> (mpiPath / "lib").!!,
          "MPI_C_INCLUDE_DIR" -> (mpiPath / "include").!!,
          "MPI_C_LIB"         -> (mpiPath / "lib" / "release" / "libmpi.so").!!
        ): _*
      ) :+ s"cp ${(repo / "build" / "cloverleaf").^?} ${repo.^?}",
      Vector(
        s"cd ${repo.^?}",
        s"${(repo / "cloverleaf").^?} " +
          s"--file ${(repo / "InputDecks" / "clover_bm16.in").^?} " +
          s"""--device "${platform.deviceSubstring}""""
      )
    )

  }

  val Def = Project(
    name = "cloverleaf",
    abbr = "c",
    gitRepo = "https://github.com/UoB-HPC/cloverleaf_sycl.git" -> "sycl_history",
    timeout = 4000 seconds,
    run = {
      case (repo, p, computecpp @ Sycl.ComputeCpp(_, oclcpu, _, _, _)) =>
        setup(
          repo,
          p,
          Vector(
            "SYCL_RUNTIME"       -> "COMPUTECPP",
            "ComputeCpp_DIR"     -> computecpp.sdk,
            "CMAKE_C_COMPILER"   -> "gcc",
            "CMAKE_CXX_COMPILER" -> "g++"
          ) ++ (p match {
            case Platform.RomeIsambardMACS | Platform.CxlIsambardMACS =>
              Vector("OpenCL_LIBRARY" -> (oclcpu / "x64" / "libOpenCL.so").^)
            case _ => Vector.empty
          }),
          (if (p.isCPU) computecpp.cpuEnvs else computecpp.gpuEnvs): _*
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
              case Platform.RomeIsambardMACS | Platform.CxlIsambardMACS | Platform.IrisPro580UoBZoo =>
                s"--gcc-toolchain=$EvalGCCPathExpr"
              case _ => ""
            }}"
          ),
          (if (p.isCPU) dpcpp.cpuEnvs else dpcpp.gpuEnvs): _*
        )
      case (wd, p, Sycl.hipSYCL(path, _, _)) => ???
    }
  )
}
