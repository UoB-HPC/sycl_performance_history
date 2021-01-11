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
    val prelude =
      Vector(s"cd ${repo.^?} ", s"export $runlineEnv") ++
        (platform match {
          case CxlIsambardMACS | RomeIsambardMACS => IsambardMACS.modules
          case Local                              => Vector()
        })

    val mpiPath = platform match {
      case RomeIsambardMACS | CxlIsambardMACS => Platform.IsambardMACS.oneapiMPIPath
      case _                                  => Platform.Local.oneapiMPIPath
    }

    val build = cmake(
      target = "cloverleaf",
      build = repo / "build",
      cmakeOpts ++ Vector(
        "MPI_AS_LIBRARY"    -> "ON",
        "MPI_C_LIB_DIR"     -> s"$mpiPath/lib",
        "MPI_C_INCLUDE_DIR" -> s"$mpiPath/include",
        "MPI_C_LIB"         -> s"$mpiPath/lib/release/libmpi.so"
      ): _*
    )
    val conclude = Vector(s"cp ${(repo / "build" / "cloverleaf").^?} ${repo.^?}")

    val deviceName = platform match {
      case RomeIsambardMACS => "AMD"
      case CxlIsambardMACS  => "Xeon"
      case Local            => s"Ryzen"
    }

    (
      prelude ++ build ++ conclude,
      repo,
      Vector(
        s"$runlineEnv ${(repo / "cloverleaf").^?} --file ${(repo / "InputDecks" / "clover_bm16_short.in").^?} --device $deviceName"
      )
    )
  }

  val Def = Project(
    "cloverleaf",
    "https://github.com/UoB-HPC/cloverleaf_sycl.git" -> "sycl_history",
    {
      case (repo, platform, Sycl.ComputeCpp(computepp, oclcpu, tbb, _)) =>
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

      case (repo, platform, Sycl.DPCPP(dpcpp, oclcpu, tbb, _)) =>
        setup(
          repo,
          platform,
          Vector(
            "SYCL_RUNTIME"    -> "DPCPP",
            "DPCPP_BIN"       -> (dpcpp / "bin" / "clang++").!!,
            "DPCPP_INCLUDE"   -> (dpcpp / "include" / "sycl").!!,
            "CXX_EXTRA_FLAGS" -> s"-fsycl"
          ),
          LD_LIBRARY_PATH_=(dpcpp / "lib", tbb / "lib/intel64/gcc4.8", oclcpu / "x64")
        )
      case (wd, p, Sycl.hipSYCL(path, _)) => ???
    }
  )
}
