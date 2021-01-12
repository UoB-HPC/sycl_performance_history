import Platform.JobSpec
import better.files.File

import java.nio.file.Paths

sealed abstract class Platform(
    val name: String,
    val abbr: String,
    val submit: JobSpec => Vector[String]
)
object Platform {

  case class JobSpec(
      jobFile: File,
      name: String,
      commands: Vector[String],
      outPrefix: File
  )

  val PbsProNameLengthLimit = 15

  def pbsCpu(queue: String, cpus: Int): JobSpec => Vector[String] = { spec =>
    val job = spec.jobFile.createFileIfNotExists()
    job.overwrite(s"""|#!/bin/bash
                      |#PBS -q $queue
                      |#PBS -l select=1:ncpus=$cpus
                      |#PBS -l walltime=00:10:00
                      |#PBS -joe
                      |set -eu
                      |date
                      |${spec.commands.mkString("\n")}
                      |""".stripMargin)

    // PBS can't handle path with lustre included for some reason
    def normalisePbsPath(f: File) = {
      val absoluteHome  = Paths.get("/lustre/home")
      val symlinkedHome = Paths.get("/home")
      if (f.path.startsWith(absoluteHome)) {
        val g = symlinkedHome.resolve(absoluteHome.relativize(f.path))
        println(s"Translating PBS path: $f -> $g")
        File(g)
      } else f
    }
    val prefix = normalisePbsPath(spec.outPrefix)
    Vector(
      s"""qsub -o "$prefix.out" -e "$prefix.err" -N "${spec.name.take(
        PbsProNameLengthLimit
      )}" -V "${normalisePbsPath(job)}" """
    )
  }

  object IsambardMACS {
    val oneapiMPIPath = "/lustre/projects/bristol/modules/intel/oneapi/2021.1/mpi/2021.1.1"
    val setupModules = Vector(
      "module purge",
      "module use /lustre/projects/bristol/modules/modulefiles",
      "module load pbspro/19.2.4.20190830141245",
      "module load cmake/3.18.3",
      "module load gcc/8.2.0"
    )
  }

  case object RomeIsambardMACS extends Platform("rome-isambard", "r", pbsCpu("romeq", 128))
  case object CxlIsambardMACS  extends Platform("cxl-isambard", "c", pbsCpu("clxq", 40))

  sealed abstract class Local(name: String, val deviceSubstring: String)
      extends Platform(
        s"local-$name",
        s"l-$name",
        spec => {
          val job = spec.jobFile.createFileIfNotExists()
          job.overwrite(s"""|#!/bin/bash
                            |date
                            |${spec.commands.mkString("\n")}
                            |""".stripMargin)
          Vector(s"chmod +x $job", s"""timeout 20 bash $job &> "${spec.outPrefix}.out" """)
        }
      ) {
    val oneapiMPIPath = "/opt/intel/oneapi/mpi/2021.1.1"
  }

  case object LocalAMDCPU   extends Local("amd", "AMD")
  case object LocalIntelGPU extends Local("intel", "Intel")
  case object LocalIntelCPU extends Local("intel", "Intel")

}
