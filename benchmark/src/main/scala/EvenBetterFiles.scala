import better.files._
import org.apache.commons.compress.archivers.tar.{TarArchiveEntry, TarArchiveInputStream}
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient.{Redirect, Version}
import java.net.http.HttpResponse.BodyHandlers
import java.net.http.{HttpClient, HttpRequest}
import java.nio.file.attribute.{FileTime, PosixFilePermission}
import java.nio.file.{Files, Paths}
import java.time.Duration

object EvenBetterFiles {

  private val Client: HttpClient = HttpClient
    .newBuilder()
    .version(Version.HTTP_2)
    .followRedirects(Redirect.NORMAL)
    .connectTimeout(Duration.ofSeconds(20))
    .build()

  def wget(url: String, file: File): Unit = {
    println(s"Downloading ${url} to ${file}")
    Client.send(
      HttpRequest
        .newBuilder()
        .uri(URI.create(url))
        .build(),
      BodyHandlers.ofFile(file.path)
    )
    println(s"Xfered ${file.size / 1024 / 1024}MiB from $url to $file")
  }

  def untarGz(archive: File, destination: File): Unit = {
    println(s"Extracting $archive to $destination...")
    (for {
      is    <- archive.inputStream
      gzis  <- new GzipCompressorInputStream(is).autoClosed
      taris <- new TarArchiveInputStream(gzis).autoClosed
    } yield Vector.unfold[Unit, TarArchiveEntry](taris.getNextTarEntry) {
      case null => None
      case entry =>
        if (!taris.canReadEntryData(entry)) throw new IOException(s"Can't read $entry")
        val file = destination / entry.getName
        def touch() =
          Files.setLastModifiedTime(file.path, FileTime.from(entry.getLastModifiedDate.toInstant))
        if (entry.isDirectory) {
          file.createDirectoryIfNotExists()
          touch()
        } else if (entry.isSymbolicLink) {
          val parent = file.parent.createDirectoryIfNotExists(createParents = true)
          val target = parent / entry.getLinkName
          target.parent.createDirectoryIfNotExists(createParents = true)
          Files.createSymbolicLink(file.path, Paths.get(entry.getLinkName))
        } else if (entry.isFile) {
          Files.copy(taris, file.path)
          touch()
        } else throw new IOException(s"Can't handle entry ${entry.getName}")
        Some(() -> taris.getNextTarEntry)
    }).get()
    ()
  }

  def moveAllChildUpOneLevel(file: File): Unit =
    file.list.foreach(_.list.foreach(_.moveToDirectory(file)))

  def fixPermissions(root: File): Unit = {
    def addPermissions(f: File)(ps: PosixFilePermission*): Unit = ps.foreach(f.addPermission(_))
    root.listRecursively
      .filter(_.isDirectory)
      .foreach(
        addPermissions(_)(PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.GROUP_EXECUTE)
      )
    root.listRecursively.foreach(
      addPermissions(_)(PosixFilePermission.OWNER_READ, PosixFilePermission.GROUP_READ)
    )
    root.listRecursively
      .filterNot(_.hasExtension)
      .foreach(_.addPermission(PosixFilePermission.OWNER_EXECUTE))
  }

}
