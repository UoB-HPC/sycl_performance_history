import Main._
import Platform._
import SC._
import better.files.File

object CloverLeaf {

  private def setup(
      repo: File,
      platform: Platform,
      cmakeOpts: Vector[(String, String)],
      runlineEnv: String
  ) = {

    val mpiPath = platform match {
      case RomeIsambardMACS | CxlIsambardMACS => Platform.IsambardMACS.oneapiMPIPath
      case l: Local                           => l.oneapiMPIPath
    }

    val deviceName = platform match {
      case RomeIsambardMACS => "AMD"
      case CxlIsambardMACS  => "Xeon"
      case l: Local         => l.deviceSubstring
    }

    RunSpec(
      s"export $runlineEnv" +: (platform match {
        case CxlIsambardMACS | RomeIsambardMACS => IsambardMACS.setupModules
        case _                                  => Vector()
      }),
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
        s"$runlineEnv ${(repo / "cloverleaf").^?} --file ${(repo / "InputDecks" / "clover_bm16_short.in").^?} --device $deviceName"
      )
    )

  }

  val Def = Project(
    "cloverleaf",
    "c",
    "https://github.com/UoB-HPC/cloverleaf_sycl.git" -> "sycl_history",
    {
      case (repo, platform, Sycl.ComputeCpp(computepp, oclcpu, tbb, _, _)) =>
        setup(
          repo,
          platform,
          Vector(
            "SYCL_RUNTIME"       -> "COMPUTECPP",
            "ComputeCpp_DIR"     -> computepp.^,
            "CMAKE_C_COMPILER"   -> "gcc",
            "CMAKE_CXX_COMPILER" -> "g++"
          ),
          LD_LIBRARY_PATH_=(tbb / "lib/intel64/gcc4.8", oclcpu / "x64")
        )

      case (repo, platform, Sycl.DPCPP(dpcpp, oclcpu, tbb, _, _)) =>
        val toolchainFlag = platform match {
          case Platform.RomeIsambardMACS | Platform.CxlIsambardMACS =>
            s"--gcc-toolchain=$EvalGCCPathExpr"
          case _ => ""
        }
        setup(
          repo,
          platform,
          Vector(
            "SYCL_RUNTIME"    -> "DPCPP",
            "DPCPP_BIN"       -> (dpcpp / "bin" / "clang++").!!,
            "DPCPP_INCLUDE"   -> (dpcpp / "include" / "sycl").!!,
            "CXX_EXTRA_FLAGS" -> s"-fsycl $toolchainFlag"
          ),
          LD_LIBRARY_PATH_=(dpcpp / "lib", tbb / "lib/intel64/gcc4.8", oclcpu / "x64")
        )
      case (wd, p, Sycl.hipSYCL(path, _, _)) => ???
    }
  )
}
