import Main._
import Platform._
import SC._
import better.files.File

object BabelStream {

  private def setup(
      repo: File,
      platform: Platform,
      makeOpts: Vector[(String, String)],
      runlineEnv: String
  ) = {

    val prelude =
      Vector(s"cd ${repo.^?} ", s"export $runlineEnv") ++
        (platform match {
          case CxlIsambardMACS | RomeIsambardMACS => IsambardMACS.modules
          case Local                              => Vector()
        })

    val build = make(
      makefile = repo / "SYCL.make",
      makeOpts: _*
    )

    val deviceName = platform match {
      case RomeIsambardMACS => "AMD"
      case CxlIsambardMACS  => "Xeon"
      case Local            => s"Ryzen"
    }

    val exe = (repo / "sycl-stream").^?
    (
      prelude ++ build,
      repo,
      Vector(
        s"export $runlineEnv",
        s"""export DEVICE=$$($exe --list | grep "$deviceName" | cut -d ':' -f1)""",
        s"""echo "Using device $$DEVICE which matches substring $deviceName" """,
        s"$exe --device $$DEVICE"
      )
    )
  }

  val Def = Project(
    "babelstream",
    "https://github.com/UoB-HPC/BabelStream.git" -> "computecpp_fix",
    {
      case (repo, platform, Sycl.ComputeCpp(computepp, oclcpu, tbb, _)) =>
        setup(
          repo,
          platform,
          Vector(
            "COMPILER"     -> "COMPUTECPP",
            "TARGET"       -> "CPU",
            "SYCL_SDK_DIR" -> computepp.!!,
            "EXTRA_FLAGS"  -> "-DCL_TARGET_OPENCL_VERSION=220 -D_GLIBCXX_USE_CXX11_ABI=0"
          ),
          LD_LIBRARY_PATH_=(tbb / "lib/intel64/gcc4.8", oclcpu / "x64")
        )

      case (repo, platform, Sycl.DPCPP(dpcpp, oclcpu, tbb, _)) =>
        setup(
          repo,
          platform,
          Vector(
            "COMPILER"           -> "DPCPP",
            "TARGET"             -> "CPU",
            "SYCL_DPCPP_CXX"     -> (dpcpp / "bin" / "clang++").!!,
            "SYCL_DPCPP_INCLUDE" -> s"-I${(dpcpp / "include" / "sycl").!!}",
            "EXTRA_FLAGS"        -> "-DCL_TARGET_OPENCL_VERSION=220 -fsycl"
          ),
          LD_LIBRARY_PATH_=(dpcpp / "lib", tbb / "lib/intel64/gcc4.8", oclcpu / "x64")
        )
      case (wd, p, Sycl.hipSYCL(path, _)) => ???
    }
  )
}
