import Main._
import Platform._
import SC._

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps
object CloverLeaf {

  private def setup(
      ctx: Context,
      platform: Platform,
      cmakeOpts: Vector[(String, String)],
      extraModules: Vector[String],
      device: String,
      exports: String*
  ) = {

    val (mpiEnvs, mpiPath) = platform match {
      case RomeIsambardMACS | CxlIsambardMACS | V100IsambardMACS =>
        Vector(
          prependFileEnvs("LD_LIBRARY_PATH", IsambardMACS.oneapiLibFabricPath / "lib"),
          prependFileEnvs("FI_PROVIDER_PATH", IsambardMACS.oneapiLibFabricPath / "lib" / "prov")
        ) -> IsambardMACS.oneapiMPIPath
      case IrisPro580UoBZoo =>
        Vector(
          prependFileEnvs("LD_LIBRARY_PATH", UoBZoo.oneapiLibFabricPath / "lib"),
          prependFileEnvs("FI_PROVIDER_PATH", UoBZoo.oneapiLibFabricPath / "lib" / "prov")
        ) -> UoBZoo.oneapiMPIPath
      case IrisXeMAXDevCloud | UHDP630DevCloud =>
        Vector(
          prependFileEnvs("LD_LIBRARY_PATH", DevCloud.oneapiLibFabricPath / "lib"),
          prependFileEnvs("FI_PROVIDER_PATH", DevCloud.oneapiLibFabricPath / "lib" / "prov")
        ) -> DevCloud.oneapiMPIPath
      case l: Local => Vector() -> l.oneapiMPIPath
    }

    RunSpec(
      platform.setup(ctx.platformBinDir) ++ extraModules ++ (exports ++ mpiEnvs).map(e =>
        s"export $e"
      ),
      s"cd ${ctx.wd.^?}" +: cmake(
        target = "cloverleaf",
        build = ctx.wd / "build",
        cmakeOpts ++ Vector(
          "MPI_AS_LIBRARY"    -> "ON",
          "MPI_C_LIB_DIR"     -> (mpiPath / "lib").!!,
          "MPI_C_INCLUDE_DIR" -> (mpiPath / "include").!!,
          "MPI_C_LIB"         -> (mpiPath / "lib" / "release" / "libmpi.so").!!,
          "CMAKE_C_COMPILER"   -> "gcc",
          "CMAKE_CXX_COMPILER" -> "g++"
        ): _*
      ) :+ s"cp ${(ctx.wd / "build" / "cloverleaf").^?} ${ctx.wd.^?}",
      Vector(
        s"cd ${ctx.wd.^?}",
        s"${(ctx.wd / "cloverleaf").^?} " +
          s"--file ${(ctx.wd / "InputDecks" / "clover_bm16.in").^?} " +
          s"""--device "$device""""
      )
    )

  }

  val Def = Project(
    name = "cloverleaf",
    abbr = "c",
    gitRepo = "https://github.com/UoB-HPC/cloverleaf_sycl.git" -> "sycl_history",
    timeout = 9000 seconds, // 3000 steps, 3 seconds per step max
    extractResult = out => {
      val xs = out.linesIterator.map(_.trim).toVector
      xs.slice(
        xs.indexWhere(_.startsWith(s"Test problem")),
        xs.indexWhere(_.startsWith("Done"))
      ).toList match {
        case s"Test problem ${_} is within ${_} of the expected solution" ::
            "This test is considered PASSED" ::
            s"Wall clock ${wallclockSec}" ::
            s"First step overhead ${_}" :: Nil =>
          Right(wallclockSec)
        case ys => Left(s"Output format invalid: \n${ys.mkString("\n")}")
      }
    },
    run = {
      case (ctx, p, computecpp: Sycl.ComputeCpp) =>
        setup(
          ctx = ctx,
          platform = p,
          cmakeOpts = Vector(
            "SYCL_RUNTIME"       -> "COMPUTECPP",
            "ComputeCpp_DIR"     -> computecpp.sdk,
            "CMAKE_C_COMPILER"   -> "gcc",
            "CMAKE_CXX_COMPILER" -> "g++",
            "CXX_EXTRA_FLAGS"    -> s"-march=${p.march}"
          ) ++ (p match {
            case RomeIsambardMACS | CxlIsambardMACS | IrisPro580UoBZoo |
                IrisXeMAXDevCloud | UHDP630DevCloud =>
              Vector("OpenCL_LIBRARY" -> (computecpp.oclcpu / "x64" / "libOpenCL.so").^)
            case _ => Vector.empty
          }),
          extraModules = Vector.empty,
          device = p.deviceSubstring,
          (if (p.isCPU) computecpp.cpuEnvs else computecpp.gpuEnvs): _*
        )

      case (ctx, p, dpcpp: Sycl.DPCPP) =>
        setup(
          ctx = ctx,
          platform = p,
          cmakeOpts = Vector(
            "SYCL_RUNTIME"  -> "DPCPP",
            "DPCPP_BIN"     -> dpcpp.`clang++`,
            "DPCPP_INCLUDE" -> dpcpp.include,
            "CXX_EXTRA_FLAGS" -> s"-fsycl -march=${p.march} ${p match {
              case RomeIsambardMACS | CxlIsambardMACS | IrisPro580UoBZoo =>
                s"--gcc-toolchain=$EvalGCCPathExpr"
              case _ => ""
            }}"
          ),
          extraModules = Vector.empty,
          device = p.deviceSubstring,
          (if (p.isCPU) dpcpp.cpuEnvs else dpcpp.gpuEnvs): _*
        )
      case (ctx, p, hipsycl: Sycl.hipSYCL) =>
        setup(
          ctx = ctx,
          platform = p,
          cmakeOpts = Vector(
            "SYCL_RUNTIME"        -> "HIPSYCL",
            "HIPSYCL_INSTALL_DIR" -> """$(dirname "$(which syclcc)")/..""",
            "HIPSYCL_PLATFORM"    -> (if (p.isCPU) "cpu" else "gpu"),
            "CXX_EXTRA_FLAGS" -> s"-march=${p.march} ${p match {
              case RomeIsambardMACS | CxlIsambardMACS | V100IsambardMACS =>
                s"--gcc-toolchain=$EvalGCCPathExpr"
              case _ => ""
            }}",
          ) ++ (if (p.isCPU)
                  Vector(
                    "HIPSYCL_PLATFORM" -> "cpu"
                  )
                else
                  Vector(
                    "HIPSYCL_PLATFORM" -> "cuda",
                    "HIPSYCL_GPU_ARCH" -> (p match {
                      case V100IsambardMACS => "sm_70"
                      case bad              => throw new Exception(s"Unsupported platform for this config: $bad")
                    })
                  )),
          extraModules = Vector(
            "module load llvm/10.0",
            s"module load hipsycl/${hipsycl.commit}/gcc-10.2.0"
          ),
          device = "0" // pick the first and presumably only one
        )
    }
  )
}
