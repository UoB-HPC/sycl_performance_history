import better.files.File

object SC {

  implicit class RichFile(private val f: File) extends AnyVal {
    def !! : String = f.ensuring(_.exists, s"$f does not exist").pathAsString
    def ^ : String  = s""""${f.ensuring(_.exists, s"$f does not exist for quoting").pathAsString}""""
    def ^? : String = s""""${f.pathAsString}""""
  }

  def prependFileEnvs(name: String, xs: File*): String =
    fileEnvs_(name, appendExisting = true, xs: _*)
  def fileEnvs(name: String, xs: File*): String = fileEnvs_(name, appendExisting = false, xs: _*)

  private def fileEnvs_(name: String, appendExisting: Boolean, xs: File*) = {
    val existing = if (appendExisting) s":$${$name:-}" else ""
    val kvs = xs
      .tapEach(p =>p.ensuring(_.exists,s"path $p does not exist"))
      .map(_.pathAsString)
      .mkString("\"", ":", s"""$existing"""")
    s"$name=$kvs"
  }

  def cmake(target: String, build: File, cmakeOpts: (String, String)*): Vector[String] = Vector(
    s"rm -rf ${build.^?}",
    s"""cmake -B${build.^?} -H. -DCMAKE_BUILD_TYPE=Release ${cmakeOpts
      .map { case (k, v) => s"""-D$k="$v"""" }
      .mkString(" ")}""",
    s"""cmake --build ${build.^?} --target "$target" --config Release -j ${sys.runtime.availableProcessors}"""
  )

  def make(makefile: File, makeOpts: (String, String)*): Vector[String] = {
    val makeCmd = s"make -f $makefile ${makeOpts
      .map { case (k, v) => s"""$k="$v"""" }
      .mkString(" ")} -j ${sys.runtime.availableProcessors}"
    Vector(
      s"$makeCmd clean",
      s"$makeCmd"
    )
  }

  val EvalGCCPathExpr: String = """$(realpath "$(dirname "$(which gcc)")"/..)"""

}
