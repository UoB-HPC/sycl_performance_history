import Main._
import Platform.Local
import SC._
import better.files.File

object Bude {

  def setup(wd: File, p: Platform, cmakeOpts: Vector[(String, String)], exports: String*) = {
    val repo = wd / "sycl"

    val deviceName = p match {
      case Platform.RomeIsambardMACS => "AMD"
      case Platform.CxlIsambardMACS  => "Xeon"
      case l: Local                  => l.deviceSubstring
    }

    RunSpec(
      prelude = (p match {
        case Platform.CxlIsambardMACS | Platform.RomeIsambardMACS =>
          Platform.IsambardMACS.setupModules
        case _ => Vector()
      }) ++ exports.map(e => s"export $e"),
      build = s"cd ${repo.^?} " +:
        cmake(
          target = "bude",
          build = repo / "build",
          cmakeOpts ++ Vector("CMAKE_C_COMPILER" -> "gcc", "CMAKE_CXX_COMPILER" -> "g++"): _*
        ) :+
        s"cp ${(repo / "build" / "bude").^?} ${repo.^?}",
      run = Vector(
        s"${(repo / "bude").^?} -n 65536 -i 8 --deck ${(wd / "data" / "bm1").^?} --wgsize 0 --device $deviceName"
      )
    )

  }

  val Def = Project(
    "bude",
    "b",
    "https://github.com/UoB-HPC/bude-portability-benchmark.git" -> "master",
    {
      case (wd, p, Sycl.ComputeCpp(computepp, oclcpu, tbb, _, _)) =>
        setup(
          wd,
          p,
          Vector(
            "SYCL_RUNTIME"      -> "COMPUTECPP",
            "ComputeCpp_DIR"    -> computepp.^,
            "NUM_TD_PER_THREAD" -> "2"
          ) ++ (p match {
            case Platform.RomeIsambardMACS | Platform.CxlIsambardMACS =>
              Vector("OpenCL_LIBRARY" -> (oclcpu / "x64" / "libOpenCL.so").^)
            case _ => Vector.empty
          }),
          LD_LIBRARY_PATH_=(tbb / "lib/intel64/gcc4.8", oclcpu / "x64")
        )

      case (wd, p, Sycl.DPCPP(dpcpp, oclcpu, tbb, _, _)) =>
        val toolchainFlag = p match {
          case Platform.RomeIsambardMACS | Platform.CxlIsambardMACS =>
            s"--gcc-toolchain=$EvalGCCPathExpr"
          case _ => ""
        }

        setup(
          wd,
          p,
          Vector(
            "SYCL_RUNTIME"      -> "DPCPP",
            "DPCPP_BIN"         -> (dpcpp / "bin" / "clang++").!!,
            "DPCPP_INCLUDE"     -> (dpcpp / "include" / "sycl").!!,
            "CXX_EXTRA_FLAGS"   -> s"-fsycl $toolchainFlag",
            "NUM_TD_PER_THREAD" -> "2"
          ),
          prependFileEnvs(
            "LD_LIBRARY_PATH",
            dpcpp / "lib",
            tbb / "lib/intel64/gcc4.8",
            oclcpu / "x64"
          ),
          fileEnvs("OCL_ICD_FILENAMES", oclcpu / "x64" / "libintelocl.so")
        )

      case (wd, p, Sycl.hipSYCL(path, _, _)) => ???
    }
  )
}
