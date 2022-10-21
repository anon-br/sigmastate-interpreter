package scalan.util

import debox.cfor

object StringUtil {
  final def quote(x: Any) = "\"" + x + "\""

  /** Uppercase the first character. */
  final def lowerCaseFirst(s: String) = if (s.isEmpty) {
    s
  } else {
    s.substring(0, 1).toLowerCase + s.substring(1)
  }

  /** Emit string representation of `x` into given builder `sb`,
    * recursively descending into Array structure of `x`.
    */
  final def deepAppend(sb: StringBuilder, x: Any): Unit = {
    x match {
      case arr: Array[_] =>
        sb.append("Array(")
        if (arr.length > 0) {
          deepAppend(sb, arr(0))
          cfor(1)(_ < arr.length, _ + 1) { i =>
            sb.append(", ")
            deepAppend(sb, arr(i))
          }
        }
        sb.append(")")
      case s: String =>
        sb.append("\"")
        sb.append(s)
        sb.append("\"")
      case _ => sb.append(x)
    }
  }

  /** Accepts an arbitrary (printable) string and returns a similar string
    * which can be used as a file name. For convenience, replaces spaces with hyphens.
    */
  def cleanFileName(string: String) = string.
      replaceAll("""[ /\\:;<>|?*^]""", "_").
      replaceAll("""['"]""", "")

  /** Compose file name from components. */
  def fileName(file: String, pathComponents: String*): String = {
    val path = pathComponents.mkString("/")
    s"$file/$path"
  }

  implicit class StringUtilExtensions(val str: String) extends AnyVal {
    def isNullOrEmpty = str == null || str.isEmpty

    def lastComponent(sep: Char): String = {
      str.substring(str.lastIndexOf(sep) + 1)
    }

    def prefixBefore(substr: String): String = {
      val pos = str.indexOf(substr)
      val res = if (pos == -1) str else str.substring(0, pos)
      res
    }

    def replaceSuffix(suffix: String, newSuffix: String) = {
      if (str.isNullOrEmpty || suffix.isNullOrEmpty) str
      else {
        val stripped = str.stripSuffix(suffix)
        if (stripped.length == str.length) str
        else
          stripped + (if (newSuffix == null) "" else newSuffix)
      }
    }

    def opt(show: String => String = _.toString, default: String = ""): String =
      if (str.nonEmpty) show(str) else default
  }

}
