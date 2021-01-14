import EvenBetterFiles._
import SC._
import better.files.File

import scala.collection.parallel.CollectionConverters._

sealed trait Sycl {
  def key: String
  def abbr: String
  def paths: Vector[(String, File)]
}
object Sycl {
  case class ComputeCpp(computepp: File, oclcpu: File, tbb: File, key: String, abbr: String)
      extends Sycl {
    def paths = Vector("compute++" -> computepp)

    def cpuEnvs: Vector[String] = Vector(
      prependFileEnvs(
        "LD_LIBRARY_PATH",
        tbb / "lib/intel64/gcc4.8",
        oclcpu / "x64"
      ),
      fileEnvs("OCL_ICD_FILENAMES", oclcpu / "x64" / "libintelocl.so")
    )

    def gpuEnvs = Vector()

    def sdk: String = computepp.!!

  }
  case class DPCPP(dpcpp: File, oclcpu: File, tbb: File, key: String, abbr: String) extends Sycl {
    def paths = Vector("dpcpp" -> dpcpp, "oclcpu" -> oclcpu, "tbb" -> tbb)
    def cpuEnvs: Vector[String] = Vector(
      prependFileEnvs(
        "LD_LIBRARY_PATH",
        dpcpp / "lib",
        tbb / "lib/intel64/gcc4.8",
        oclcpu / "x64"
      ),
      fileEnvs("OCL_ICD_FILENAMES", oclcpu / "x64" / "libintelocl.so")
    )
    def gpuEnvs = Vector(prependFileEnvs("LD_LIBRARY_PATH", dpcpp / "lib"))

    def `clang++` : String = (dpcpp / "bin" / "clang++").!!
    def include: String    = (dpcpp / "include" / "sycl").!!

  }
  case class hipSYCL(path: File, key: String, abbr: String) extends Sycl {
    def paths = Vector("dir" -> path)
  }

  def list(oclcpuDir: File, dpcppDir: File, computecppDir: File): Vector[Sycl] = {

    def listDirs(f: File) = f.list
      .filter(_.isDirectory)
      .to(Vector)
      .sortBy(_.name)

    val oclCpuXs = listDirs(oclcpuDir)
    val tbb      = oclCpuXs.find(_.name.startsWith("tbb.")).get
    val oclcpus = oclCpuXs
      .flatMap { oclcpu =>
        oclcpu.name match {
          case s"oclcpuexp.$yyyy-$mm.${ver}_rel" => Some(s"$yyyy$mm" -> oclcpu)
          case _                                 => None
        }
      }
      .sortBy(_._1)

    val dpcpp = listDirs(dpcppDir)
      .flatMap(dpcpp =>
        dpcpp.name match {
          case s"dpcpp-compiler.$yyyymmdd" =>
            oclcpus.map { case (oclcpuyyyymm, oclcpu) =>
              Sycl.DPCPP(
                dpcpp,
                oclcpu,
                tbb,
                s"dpcpp-$yyyymmdd-oclcpu-$oclcpuyyyymm",
                s"d${yyyymmdd}o$oclcpuyyyymm"
              )
            }
          case _ => Vector()
        }
      )

    val computecpp = listDirs(computecppDir)
      .flatMap { computepp =>
        computepp.name.toLowerCase match {
          case s"computecpp-ce-$ver-$_" =>
            oclcpus.map { case (oclcpuyyyymm, oclcpu) =>
              Sycl.ComputeCpp(
                computepp,
                oclcpu,
                tbb,
                s"computecpp-$ver-oclcpu-$oclcpuyyyymm",
                s"c${ver}o$oclcpuyyyymm"
              )
            }
          case _ => Vector()
        }
      }

    dpcpp ++ computecpp

  }

  private val llvmUrl = "https://github.com/intel/llvm/releases/download"

  private val oclcpu = Vector(
    s"$llvmUrl/2020-12/oclcpuexp-2020.11.11.0.04_rel.tar.gz",
    s"$llvmUrl/2020-09/oclcpuexp-2020.11.8.0.27_rel.tar.gz",
    s"$llvmUrl/2020-08/oclcpuexp-2020.10.7.0.15_rel.tar.gz",
    s"$llvmUrl/2020-06/oclcpuexp-2020.10.6.0.4_rel.tar.gz",
    s"$llvmUrl/2020-03/oclcpuexp-2020.10.4.0.15_rel.tar.gz",
    s"$llvmUrl/2020-02/oclcpuexp-2020.10.3.0.04_rel.tar.gz"
  )
  private val tbb = Vector(
    "https://github.com/oneapi-src/oneTBB/releases/download/v2021.1.1/oneapi-tbb-2021.1.1-lin.tgz"
  )

  def primeOclCpu(root: File): Unit = {
    println(s"Priming oclcpu for $root")
    tbb.par.foreach { url =>
      val ver = url.split('/').toVector match {
        case _ :+ version :+ (s"$_.tar.gz" | s"$_.tgz") => version
        case _                                          => throw new IllegalArgumentException(s"Can't handle url $url")
      }
      val archive   = root / s"tbb.$ver.tar.gz"
      val extracted = root / s"tbb.$ver"
      if (archive.notExists) wget(url, archive)
      if (extracted.notExists) { untarGz(archive, extracted); moveAllChildUpOneLevel(extracted) }
      fixPermissions(extracted)
    }

    oclcpu.par.foreach { url =>
      val (date, ver) = url.split('/').toVector match {
        case _ :+ yyyymm :+ s"oclcpuexp-$ver.tar.gz" => yyyymm -> ver
        case _                                       => throw new IllegalArgumentException(s"Can't handle url $url")
      }
      val archive   = root / s"oclcpuexp.$date.$ver.tar.gz"
      val extracted = root / s"oclcpuexp.$date.$ver"
      if (archive.notExists) wget(url, archive)
      if (extracted.notExists) untarGz(archive, extracted)
      fixPermissions(extracted)
    }

  }

  private val dpcpps = Vector(
    s"$llvmUrl/sycl-nightly/20210106/dpcpp-compiler.tar.gz",
    s"$llvmUrl/sycl-nightly/20201217/dpcpp-compiler.tar.gz",
    s"$llvmUrl/sycl-nightly/20201128/dpcpp-compiler.tar.gz",
    s"$llvmUrl/20201031/dpcpp-compiler.tar.gz",
    s"$llvmUrl/20201016/dpcpp-compiler.tar.gz",
    s"$llvmUrl/20200924/dpcpp-compiler.tar.gz",
    s"$llvmUrl/20200906/dpcpp-compiler.tar.gz",
    s"$llvmUrl/20200816/dpcpp-compiler.tar.gz",
    s"$llvmUrl/20200730/dpcpp-compiler.tar.gz",
    s"$llvmUrl/20200715/dpcpp-compiler.tar.gz",
    s"$llvmUrl/20200628/dpcpp-compiler.tar.gz",
    s"$llvmUrl/20200612/dpcpp-compiler.tar.gz",
    s"$llvmUrl/20200521/dpcpp-compiler.tar.gz",
    s"$llvmUrl/20200430/dpcpp-compiler.tar.gz",
    s"$llvmUrl/20200410/dpcpp-compiler.tar.gz",
    s"$llvmUrl/20200326/dpcpp-compiler.tar.gz"
  )

  def primeDPCPP(root: File): Unit = {
    println(s"Priming dpcpp for $root")
    dpcpps.par.foreach { url =>
      val date = url.split('/').toVector match {
        case _ :+ yyyymmdd :+ "dpcpp-compiler.tar.gz" => yyyymmdd
        case _                                        => throw new IllegalArgumentException(s"Can't handle url $url")
      }
      val archive   = root / s"dpcpp-compiler.$date.tar.gz"
      val extracted = root / s"dpcpp-compiler.$date"
      if (archive.notExists) wget(url, archive)
      if (extracted.notExists) { untarGz(archive, extracted); moveAllChildUpOneLevel(extracted) }
      fixPermissions(extracted)
    }
  }

  def primeComputeCpp(pool: Option[File], root: File): Unit = {
    println(s"Priming computecpp for $root from $pool")
    pool
      .getOrElse(root)
      .list
      .filter(_.isRegularFile)
      .toVector
      .par
      .foreach { tarball =>
        tarball.name match {
          case s"$name.tar.gz" =>
            val dest = root / name.toLowerCase
            if (dest.notExists) {
              untarGz(tarball, dest)
              moveAllChildUpOneLevel(dest)
            }
            fixPermissions(dest)
          case _ => ()
        }
      }
  }

}
