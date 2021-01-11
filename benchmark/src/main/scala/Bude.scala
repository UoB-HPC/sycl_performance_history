import Main._
import SC._
import better.files.File

object Bude {

  def setup(wd: File, p: Platform, cmakeOpts: Vector[(String, String)], runlineEnv: String) = {
    val repo = wd / "sycl"

    val prelude =
      Vector(s"cd ${repo.^?} ", s"export $runlineEnv") ++
        (p match {
          case Platform.CxlIsambardMACS | Platform.RomeIsambardMACS => Platform.IsambardMACS.modules
          case Platform.Local                                       => Vector()
        })

    val build = cmake(
      target = "bude",
      build = repo / "build",
      cmakeOpts: _*
    )

    val conclude = Vector(s"cp ${(repo / "build" / "bude").^?} ${repo.^?}")

    val deviceName = p match {
      case Platform.RomeIsambardMACS => "AMD"
      case Platform.CxlIsambardMACS  => "Xeon"
      case Platform.Local            => s"Ryzen"
    }

    (
      prelude ++ build ++ conclude,
      repo,
      Vector(
        s"$runlineEnv ${(repo / "bude").^?} -n 65536 -i 8 --deck ${(wd / "data" / "bm1").^?} --wgsize 0 --device $deviceName"
      )
    )
  }

  val Def = Project(
    "bude",
    "https://github.com/UoB-HPC/bude-portability-benchmark.git" -> "master",
    {
      case (wd, p, Sycl.ComputeCpp(computepp, oclcpu, tbb, _)) =>
        setup(
          wd,
          p,
          Vector(
            "SYCL_RUNTIME"       -> "COMPUTECPP",
            "ComputeCpp_DIR"     -> computepp.^,
            "CMAKE_C_COMPILER"   -> "gcc",
            "CMAKE_CXX_COMPILER" -> "g++",
            "NUM_TD_PER_THREAD"  -> "2"
          ) ++ (p match {
            case Platform.RomeIsambardMACS | Platform.CxlIsambardMACS =>
              Vector("OpenCL_LIBRARY" -> (oclcpu / "x64" / "libOpenCL.so").^)
            case _ => Vector.empty
          }),
          LD_LIBRARY_PATH_=(tbb / "lib/intel64/gcc4.8", oclcpu / "x64")
        )

      case (wd, p, Sycl.DPCPP(dpcpp, oclcpu, tbb, _)) =>
        setup(
          wd,
          p,
          Vector(
            "SYCL_RUNTIME"      -> "DPCPP",
            "DPCPP_BIN"         -> (dpcpp / "bin" / "clang++").!!,
            "DPCPP_INCLUDE"     -> (dpcpp / "include" / "sycl").!!,
            "CXX_EXTRA_FLAGS"   -> s"-fsycl",
            "NUM_TD_PER_THREAD" -> "2"
          ) ++ (p match {
            case Platform.RomeIsambardMACS | Platform.CxlIsambardMACS =>
              Vector("OpenCL_LIBRARY" -> (oclcpu / "x64" / "libOpenCL.so").^)
            case _ => Vector.empty
          }),
          LD_LIBRARY_PATH_=(dpcpp / "lib", tbb / "lib/intel64/gcc4.8", oclcpu / "x64")
        )

      case (wd, p, Sycl.hipSYCL(path, _)) => ???
    }
  )
}
