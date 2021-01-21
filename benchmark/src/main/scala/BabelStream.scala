import Main._
import Platform._
import SC._

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

object BabelStream {

  private def setup(
      ctx: Context,
      platform: Platform,
      makeOpts: Vector[(String, String)],
      extraModules: Vector[String],
      deviceSubstring: Option[String],
      exports: String*
  ) = {

    val exe = (ctx.wd / "sycl-stream").^?

    val modules = platform match {
      case IrisPro580UoBZoo =>
        platform.setup.andThen(_ ++ Vector("module load khronos/opencl/icd-loader"))
      case _ => platform.setup
    }

    RunSpec(
      prelude = modules(ctx.platformBinDir) ++ extraModules ++ exports.map(e => s"export $e"),
      build = s"cd ${ctx.wd.^?} " +:
        make(
          makefile = ctx.wd / "SYCL.make",
          makeOpts: _*
        ),
      run = Vector(
        s"cd ${ctx.wd.^?}",
        s"$exe --list"
      ) ++ (deviceSubstring match {
        case Some(substring) =>
          Vector(
            s"""export DEVICE=$$($exe --list | grep -a "$substring" | cut -d ':' -f1 | head -n 1)""",
            s"""echo "Using device $$DEVICE which matches substring $substring" """
          )
        case None =>
          Vector("export DEVICE=0")
      }) ++ Vector(
        s"$exe --device $$DEVICE ${platform.streamArraySize.fold("")(n => s"--arraysize $n")}"
      )
    )

  }

  val Def = Project(
    name = "babelstream",
    abbr = "s",
    gitRepo = "https://github.com/UoB-HPC/BabelStream.git" -> "computecpp_fix",
    timeout = 30 minute,
    extractResult = out => {
      if (out.contains("Validation failed on")) Left("Validation failed")
      else
        out.linesIterator
          .flatMap { line =>
            line.split("\\s+").toList match {
              case "Dot" :: peakMBytesPerSec :: minSec :: maxSec :: avgSec :: Nil =>
                Some(peakMBytesPerSec)
              case xs => None
            }
          }
          .ensureOne("Dot line")
    },
    run = {
      case (ctx, p, computecpp: Sycl.ComputeCpp) =>
        setup(
          ctx = ctx,
          platform = p,
          makeOpts = Vector(
            "COMPILER"     -> "COMPUTECPP",
            "TARGET"       -> "CPU",
            "SYCL_SDK_DIR" -> computecpp.sdk,
            "EXTRA_FLAGS" -> Vector(
              s"-DCL_TARGET_OPENCL_VERSION=220",
              "-DNDEBUG", // eliminate assertion, see https://en.cppreference.com/w/cpp/error/assert
              s"-march=${p.march}",
              "-D_GLIBCXX_USE_CXX11_ABI=0",
              s"-I${ctx.clHeaderIncludeDir.^}",
              s"-L${(computecpp.oclcpu / "x64").^}",
              p match {
                case RomeIsambardMACS | CxlIsambardMACS | IrisPro580UoBZoo =>
                  s"--gcc-toolchain=$EvalGCCPathExpr"
                case _ => ""
              }
            ).mkString(" ")
          ),
          extraModules = Vector.empty,
          deviceSubstring = Some(p.deviceSubstring),
          (if (p.isCPU) computecpp.cpuEnvs else computecpp.gpuEnvs): _*
        )

      case (ctx, p, dpcpp: Sycl.DPCPP) =>
        setup(
          ctx = ctx,
          platform = p,
          makeOpts = Vector(
            "COMPILER"           -> "DPCPP",
            "TARGET"             -> "CPU",
            "SYCL_DPCPP_CXX"     -> dpcpp.`clang++`,
            "SYCL_DPCPP_INCLUDE" -> s"-I${dpcpp.include}",
            "EXTRA_FLAGS" -> Vector(
              s"-DCL_TARGET_OPENCL_VERSION=220",
              "-DNDEBUG", // eliminate assertion, see https://en.cppreference.com/w/cpp/error/assert
              "-fsycl",
              s"-march=${p.march}",
              s"-I${(dpcpp.dpcpp / "include" / "sycl" / "CL").^}",
              s"${p match {
                case RomeIsambardMACS | CxlIsambardMACS | IrisPro580UoBZoo =>
                  s"--gcc-toolchain=$EvalGCCPathExpr"
                case _ => ""
              }}"
            ).mkString(" ")
          ),
          extraModules = Vector.empty,
          deviceSubstring = Some(p.deviceSubstring),
          (if (p.isCPU) dpcpp.cpuEnvs else dpcpp.gpuEnvs): _*
        )
      case (ctx, p, hipsycl: Sycl.hipSYCL) =>
        setup(
          ctx = ctx,
          platform = p,
          makeOpts = Vector(
            "COMPILER"     -> "HIPSYCL",
            "SYCL_SDK_DIR" -> """$(dirname "$(which syclcc)")/..""",
            "EXTRA_FLAGS" -> Vector(
              s"-march=${p.march}",
              s"${p match {
                case RomeIsambardMACS | CxlIsambardMACS | V100IsambardMACS =>
                  s"--gcc-toolchain=$EvalGCCPathExpr"
                case _ => ""
              }}"
            ).mkString(" ")
          ),
          extraModules = Vector(
            "module load llvm/10.0",
            s"module load hipsycl/${hipsycl.commit}/gcc-10.2.0"
          ),
          deviceSubstring = None
        )
    }
  )
}
