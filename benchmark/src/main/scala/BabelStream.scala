import Main._
import Platform._
import SC._
import better.files.File

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

object BabelStream {

  private def setup(
      repo: File,
      platform: Platform,
      makeOpts: Vector[(String, String)],
      exports: String*
  ) = {

    val deviceName = platform match {
      case RomeIsambardMACS => "AMD"
      case CxlIsambardMACS  => "Xeon"
      case l: Local         => l.deviceSubstring
    }

    val exe = (repo / "sycl-stream").^?

    RunSpec(
      prelude = (platform match {
        case CxlIsambardMACS | RomeIsambardMACS => IsambardMACS.setupModules
        case _                                  => Vector()
      }) ++ exports.map(e => s"export $e"),
      build = s"cd ${repo.^?} " +:
        make(
          makefile = repo / "SYCL.make",
          makeOpts: _*
        ),
      run = Vector(
        s"cd ${repo.^?}",
        s"""export DEVICE=$$($exe --list | grep "$deviceName" | cut -d ':' -f1)""",
        s"""echo "Using device $$DEVICE which matches substring $deviceName" """,
        s"$exe --device $$DEVICE"
      )
    )

  }

  val Def = Project(
    name = "babelstream",
    abbr = "s",
    gitRepo = "https://github.com/UoB-HPC/BabelStream.git" -> "computecpp_fix",
    timeout = 1 minute,
    {
      case (repo, platform, computecpp @ Sycl.ComputeCpp(_, _, _, _, _)) =>
        setup(
          repo,
          platform,
          Vector(
            "COMPILER"     -> "COMPUTECPP",
            "TARGET"       -> "CPU",
            "SYCL_SDK_DIR" -> computecpp.sdk,
            "EXTRA_FLAGS"  -> "-DCL_TARGET_OPENCL_VERSION=220 -D_GLIBCXX_USE_CXX11_ABI=0"
          ),
          computecpp.envs: _*
        )

      case (repo, p, dpcpp @ Sycl.DPCPP(_, _, _, _, _)) =>
        setup(
          repo,
          p,
          Vector(
            "COMPILER"           -> "DPCPP",
            "TARGET"             -> "CPU",
            "SYCL_DPCPP_CXX"     -> dpcpp.`clang++`,
            "SYCL_DPCPP_INCLUDE" -> s"-I${dpcpp.include}",
            "EXTRA_FLAGS" -> s"-DCL_TARGET_OPENCL_VERSION=220 -fsycl -march=${p.march} ${p match {
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
