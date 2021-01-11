import Platform.JobSpec
import better.files.File

sealed abstract class Platform(val name: String, val submit: JobSpec => Vector[String])
object Platform {

  case class JobSpec(
      jobFile: File,
      name: String,
      runDir: File,
      commands: Vector[String],
      outPrefix: File
  )

  def pbsCpu(queue: String, cpus: Int): JobSpec => Vector[String] = { spec =>
    val job = spec.jobFile.createFileIfNotExists()
    job.overwrite(s"""
		                 |#!/bin/bash
		                 |#PBS -q $queue
		                 |#PBS -l select=1:ncpus=$cpus
		                 |#PBS -l walltime=00:10:00
		                 |#PBS -joe
		                 |set -eu
		                 |cd "${spec.runDir}" || exit 2
		                 |date
		                 |${spec.commands.mkString("\n")}
		                 |""".stripMargin)
    Vector(
      s"""qsub -o "${spec.outPrefix}.out" -e "${spec.outPrefix}.err" -N "${spec.name}" -V "$job" """
    )
  }

  object IsambardMACS {
    val oneapiMPIPath   = "/lustre/projects/bristol/modules/intel/oneapi/2021.1/mpi/2021.1.1"
    val moduleLoadCMake = "module load cmake/3.18.3"
    val moduleLoadGcc8  = "module load gcc/8.2.0"
    val modules = Vector(
      "module use /lustre/projects/bristol/modules/modulefiles",
      moduleLoadCMake,
      moduleLoadGcc8
    )
  }

  case object RomeIsambardMACS extends Platform("rome-isambard", pbsCpu("romeq", 128))
  case object CxlIsambardMACS  extends Platform("cxl-isambard", pbsCpu("clxq", 40))
  case object Local
      extends Platform(
        "local",
        spec => {
          val job = spec.jobFile.createFileIfNotExists()
          job.overwrite(s"""
				                 |#!/bin/bash
				                 |cd "${spec.runDir}" || exit 2
				                 |date
				                 |${spec.commands.mkString("\n")}
				                 |""".stripMargin)
          Vector(s"chmod +x $job", s"""timeout 20 bash $job &> "${spec.outPrefix}.out" """)
        }
      ) {
    val oneapiMPIPath = "/opt/intel/oneapi/mpi/2021.1.1"

  }

}
