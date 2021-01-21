import Platform.JobSpec
import SC.{RichFile, prependFileEnvs}
import better.files.File

import java.nio.file.Paths
import scala.concurrent.duration.Duration

sealed abstract class Platform(
    val name: String,
    val abbr: String,
    val march: String,
    val deviceSubstring: String,
    val hasQueue: Boolean,
    val isCPU: Boolean,
    val setup: File => Vector[String],
    val streamArraySize: Option[Long],
    val submit: JobSpec => (String, File => Vector[String]),
    val prime: Option[(File, Platform) => Unit] = None
)
object Platform {

  case class JobSpec(
      name: String,
      timeout: Duration,
      commands: Vector[String],
      outPrefix: File
  )

  val PbsProNameLengthLimit = 15

  def genericPBS(
      queue: String,
      mapPath: File => File,
      pbsOpts: String*
  ): JobSpec => (String, File => Vector[String]) = { spec =>
    val d      = java.time.Duration.ofNanos(spec.timeout.toNanos)
    val prefix = mapPath(spec.outPrefix)
    s"""|#!/bin/bash
        |#PBS -q $queue
        |${pbsOpts.map(opt => s"#PBS $opt").mkString("\n")}
        |#PBS -l walltime=${d.toHoursPart}:${d.toMinutesPart}:${d.toSecondsPart}
        |#PBS -joe
        |set -eu
        |date
        |${spec.commands.mkString("\n")}
        |""".stripMargin -> { f =>
      Vector(
        s"""qsub -o "$prefix.out" -e "$prefix.err" -N "${spec.name.take(
          PbsProNameLengthLimit
        )}" -V ${mapPath(f).^?} """
      )
    }
  }

  def lustreNCpu(queue: String, cpus: Int): JobSpec => (String, File => Vector[String]) =
    genericPBS(
      queue,
      { f =>
        val absoluteHome  = Paths.get("/lustre/home")
        val symlinkedHome = Paths.get("/home")
        if (f.path.startsWith(absoluteHome)) {
          val g = symlinkedHome.resolve(absoluteHome.relativize(f.path))
          println(s"Translating PBS path: $f -> $g")
          File(g)
        } else f
      },
      s"-l select=1:ncpus=$cpus",
      "-l place=excl"
    )

  object IsambardMACS {
    val oneapiMPIPath: File =
      File("/lustre/projects/bristol/modules/intel/oneapi/2021.1/mpi/2021.1.1")
    val oneapiLibFabricPath: File = oneapiMPIPath / "libfabric"
    val setupModules = Vector(
      "module purge",
      "module use /lustre/projects/bristol/modules/modulefiles",
      "module load pbspro/19.2.4.20190830141245",
      "module load cmake/3.18.3",
      "module load gcc/8.2.0"
    )
  }

  case object RomeIsambardMACS
      extends Platform(
        name = "rome-isambard",
        abbr = "r",
        march = "znver2",
        deviceSubstring = "AMD",
        hasQueue = true,
        isCPU = true,
        setup = _ => IsambardMACS.setupModules,
        streamArraySize = Some(math.pow(2, 29).toLong),
        submit = lustreNCpu("romeq", 128)
      )
  case object CxlIsambardMACS
      extends Platform(
        name = "cxl-isambard",
        abbr = "c",
        march = "skylake-avx512",
        deviceSubstring = "Xeon",
        hasQueue = true,
        isCPU = true,
        setup = _ => IsambardMACS.setupModules,
        streamArraySize = Some(math.pow(2, 29).toLong),
        submit = lustreNCpu("clxq", 40)
      )

    case object V100IsambardMACS // sm_70
        extends Platform(
            name = "v100-isambard",
            abbr = "v100",
            march = "skylake-avx512",
            deviceSubstring = "OpenMP",
            hasQueue = true,
            isCPU = false,
            setup = _ => IsambardMACS.setupModules,
            streamArraySize = Some(math.pow(2, 29).toLong),
            submit = lustreNCpu("clxq", 40)
        )

  case object UoBZoo {
    val oneapiMPIPath: File =
      File("/nfs/software/x86_64/intel/oneapi/2021.1/mpi/2021.1.1")
    val oneapiLibFabricPath: File = oneapiMPIPath / "libfabric"
    val setupModules = Vector(
      "module purge",
      "module load cmake/3.19.1",
      "module load gcc/8.3.0"
    )
  }

  case object IrisPro580UoBZoo
      extends Platform(
        name = "irispro580-zoo",
        abbr = "i",
        march = "skylake", //NUC is i7-6770HQ
        deviceSubstring = "Intel(R) Graphics",
        hasQueue = true,
        isCPU = false,
        setup = _ => UoBZoo.setupModules ++ Vector("module load intel/neo/20.49.18626"),
        streamArraySize = None,
        submit = genericPBS("workq", identity, "-l select=1:ngpus=1:gputype=irispro580")
      )

  object DevCloud {
    // devcloud LSB is Ubuntu 20.04.1 LTS
    // queues:
    // - extended  168:00:0
    // - batch     24:00:00

    val oneapiMPIPath: File       = File("/glob/development-tools/oneapi/oneapi/mpi/2021.1.1")
    val oneapiLibFabricPath: File = oneapiMPIPath / "libfabric"

    def prime(wd: File) = {
      val libtinfo = wd / "lib" / "x86_64-linux-gnu" / "libtinfo.so.5"
      if (libtinfo.notExists) {
        // dpcpp needs this legacy ncurses thing
        val deb = wd / "libtinfo5.deb"
        val out = wd.createFileIfNotExists()
        EvenBetterFiles.wget(
          "http://mirrors.edge.kernel.org/ubuntu/pool/universe/n/ncurses/libtinfo5_6.2-0ubuntu2_amd64.deb",
          deb
        )
        println(s"Preparing to install ${deb.^?} to ${wd.^?}")
        import scala.sys.process._
        val dpkgExtractCode = Seq("dpkg-deb", "-xv", deb.!!, out.!!).!
        if (dpkgExtractCode != 0) {
          throw new Exception(s"$deb failed to install, got code $dpkgExtractCode")
        }
      }
    }
    def setup(wd: File) = {
      val libs = wd / "lib" / "x86_64-linux-gnu"
      Vector(s"export ${prependFileEnvs("LD_LIBRARY_PATH", libs)}")
    }
  }

  object UHDP630DevCloud
      extends Platform(
        name = "uhdp630-devcloud",
        abbr = "u630",
        march = "skylake", //P630 node is Xeon E-2176G, skylake
        deviceSubstring = "Intel(R) Graphics",
        hasQueue = true,
        isCPU = false,
        setup = DevCloud.setup(_),
        streamArraySize = None,
        submit = genericPBS("batch", identity, "-l nodes=1:gen9:ppn=2"),
        prime = Some({ case (wd, _) => DevCloud.prime(wd) })
      )

  object IrisXeMAXDevCloud
      extends Platform(
        name = "irisxemax-devcloud",
        abbr = "ixm",
        march = "cascadelake", //Xe MAX node is i9-10920X, cascadelake
        deviceSubstring = "Intel(R) Graphics",
        hasQueue = true,
        isCPU = false,
        setup = DevCloud.setup(_),
        streamArraySize = None,
        submit = genericPBS("batch", identity, "-l nodes=1:iris_xe_max:ppn=2"),
        prime = Some({ case (wd, _) => DevCloud.prime(wd) })
      )

  sealed abstract class Local(
      name: String,
      march: String,
      deviceSubstring: String,
      setupModules: Vector[String],
      streamArraySize: Option[Long] = None,
      isCPU: Boolean
  ) extends Platform(
        name = s"$name-local",
        abbr = s"$name-l",
        march = march,
        deviceSubstring = deviceSubstring,
        hasQueue = false,
        isCPU = isCPU,
        setup = _ => setupModules,
        streamArraySize = streamArraySize,
        submit = spec => {
          s"""|#!/bin/bash
              |date
              |${spec.commands.mkString("\n")}
              |""".stripMargin -> { f =>
            Vector(s"""timeout ${spec.timeout.toSeconds} bash ${f.^?} &> "${spec.outPrefix}.out"""")
          }
        }
      ) {
    val oneapiMPIPath: File = File("/opt/intel/oneapi/mpi/2021.1.1")
  }

  case object LocalAMDCPU   extends Local("amd", "native", "AMD", Vector(), isCPU = true)
  case object LocalIntelCPU extends Local("intel", "native", "Intel", Vector(), isCPU = true)
  case object LocalIntelGPU extends Local("intel", "native", "Intel", Vector(), isCPU = false)

}
