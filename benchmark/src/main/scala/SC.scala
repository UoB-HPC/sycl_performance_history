import better.files.File

object SC {

  implicit class RichFile(private val f: File) extends AnyVal {
    def !! : String = f.ensuring(_.exists, s"$f does not exist").pathAsString
    def ^ : String  = s""""${f.ensuring(_.exists, s"$f does not exist for quoting").pathAsString}""""
    def ^? : String = s""""${f.pathAsString}""""
  }

  def LD_LIBRARY_PATH_=(xs: File*) =
    s"""LD_LIBRARY_PATH=${xs
      .tapEach(_.ensuring(_.exists))
      .map(_.pathAsString)
      .mkString("\"", ":", "\"")} """

  def cmake(target: String, build: File, cmakeOpts: (String, String)*) = Vector(
    s"""cmake -B${build.^?} -H. -DCMAKE_BUILD_TYPE=Release ${cmakeOpts
      .map { case (k, v) => s"""-D$k="$v"""" }
      .mkString(" ")}""",
    s"""cmake --build ${build.^?} --target "$target" --config Release -j ${sys.runtime.availableProcessors}"""
  )

  def make(makefile: File, makeOpts: (String, String)*) = Vector(
    s"make -f $makefile ${makeOpts
      .map { case (k, v) => s"""$k="$v"""" }
      .mkString(" ")} -j ${sys.runtime.availableProcessors}"
  )

  val EvalGCCPathExpr: String = """$(realpath "$(dirname "$(which gcc)")"/..)"""

}
