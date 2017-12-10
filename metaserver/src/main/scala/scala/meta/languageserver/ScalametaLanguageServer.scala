package scala.meta.languageserver

import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream
import java.nio.file.Files
import scala.meta.languageserver.compiler.CompilerConfig
import scala.meta.languageserver.compiler.Cursor
import scala.meta.languageserver.compiler.ScalacProvider
import scala.meta.languageserver.providers._
import scala.meta.languageserver.search.SymbolIndex
import scala.tools.nsc.interactive.Global
import com.typesafe.scalalogging.LazyLogging
import io.github.soc.directories.ProjectDirectories
import langserver.core.LanguageServer
import langserver.messages.CompletionList
import langserver.messages.CompletionOptions
import langserver.messages.DefinitionResult
import langserver.messages.DocumentFormattingResult
import langserver.messages.DocumentHighlightResult
import langserver.messages.DocumentSymbolParams
import langserver.messages.DocumentSymbolResult
import langserver.messages.Hover
import langserver.messages.InitializeParams
import langserver.messages.InitializeResult
import langserver.messages.ReferencesResult
import langserver.messages.ServerCapabilities
import langserver.messages.Shutdown
import langserver.messages.ShutdownResult
import langserver.messages.SignatureHelpOptions
import langserver.messages.SignatureHelpResult
import langserver.messages.TextDocumentCompletionRequest
import langserver.messages.TextDocumentDefinitionRequest
import langserver.messages.TextDocumentDocumentHighlightRequest
import langserver.messages.TextDocumentFormattingRequest
import langserver.messages.TextDocumentHoverRequest
import langserver.messages.TextDocumentReferencesRequest
import langserver.messages.TextDocumentSignatureHelpRequest
import langserver.types._
import monix.eval.Task
import monix.execution.Cancelable
import monix.execution.Scheduler
import monix.reactive.MulticastStrategy
import monix.reactive.Observable
import monix.reactive.Observer
import org.langmeta.internal.io.PathIO
import org.langmeta.internal.semanticdb.schema.Database
import org.langmeta.io.AbsolutePath
import org.langmeta.semanticdb

case class ServerConfig(
    cwd: AbsolutePath,
    setupScalafmt: Boolean = true,
    // TODO(olafur): re-enable indexJDK after https://github.com/scalameta/language-server/issues/43 is fixed
    indexJDK: Boolean = false,
    indexClasspath: Boolean = true
)

class ScalametaLanguageServer(
    config: ServerConfig,
    lspIn: InputStream,
    lspOut: OutputStream,
    stdout: PrintStream
)(implicit s: Scheduler)
    extends LanguageServer(lspIn, lspOut) {
  implicit val cwd: AbsolutePath = config.cwd
  private val tempSourcesDir: AbsolutePath =
    cwd.resolve("target").resolve("sources")
  val (fileSystemSemanticdbSubscriber, fileSystemSemanticdbsPublisher) =
    ScalametaLanguageServer.fileSystemSemanticdbStream(cwd)
  val (compilerConfigSubscriber, compilerConfigPublisher) =
    ScalametaLanguageServer.compilerConfigStream(cwd)
  val buffers: Buffers = Buffers()
  val scalac: ScalacProvider = new ScalacProvider(config)
  val symbolIndex: SymbolIndex = SymbolIndex(cwd, connection, buffers, config)
  val scalafix: Linter = new Linter(cwd, stdout, connection)
  val scalacErrorReporter: ScalacErrorReporter = new ScalacErrorReporter(
    connection
  )
  val metaSemanticdbs: Observable[semanticdb.Database] =
    Observable.merge(
      fileSystemSemanticdbsPublisher.map(_.toDb(sourcepath = None))
    )
  val scalafmt: Formatter =
    if (config.setupScalafmt) Formatter.classloadScalafmt("1.3.0")
    else Formatter.noop

  // Effects
  val indexedFileSystemSemanticdbs: Observable[Effects.IndexSemanticdb] =
    fileSystemSemanticdbsPublisher.map(symbolIndex.indexDatabase)
  val indexedDependencyClasspath: Observable[Effects.IndexSourcesClasspath] =
    compilerConfigPublisher.map(
      c => symbolIndex.indexDependencyClasspath(c.sourceJars)
    )
  val installedCompilers: Observable[Effects.InstallPresentationCompiler] =
    compilerConfigPublisher.map(scalac.loadNewCompilerGlobals)
  val scalafixNotifications: Observable[Effects.PublishLinterDiagnostics] =
    metaSemanticdbs.map(scalafix.reportLinterMessages)
  val scalacErrors: Observable[Effects.PublishScalacDiagnostics] =
    metaSemanticdbs.map(scalacErrorReporter.reportErrors)
  private var cancelEffects = List.empty[Cancelable]
  val effects: List[Observable[Effects]] = List(
    indexedDependencyClasspath,
    indexedFileSystemSemanticdbs,
    installedCompilers,
    scalafixNotifications,
  )

  private def loadAllRelevantFilesInThisWorkspace(): Unit = {
    Workspace.initialize(cwd) { path =>
      onChangedFile(path)(_ => ())
    }
  }

  override def initialize(
      request: InitializeParams
  ): Task[InitializeResult] = Task {
    logger.info(s"Initialized with $cwd, $request")
    cancelEffects = effects.map(_.subscribe())
    loadAllRelevantFilesInThisWorkspace()
    val capabilities = ServerCapabilities(
      completionProvider = Some(
        CompletionOptions(
          resolveProvider = false,
          triggerCharacters = "." :: Nil
        )
      ),
      signatureHelpProvider = Some(
        SignatureHelpOptions(
          triggerCharacters = "(" :: Nil
        )
      ),
      definitionProvider = true,
      referencesProvider = true,
      documentHighlightProvider = true,
      documentSymbolProvider = true,
      documentFormattingProvider = true,
      hoverProvider = true
    )
    InitializeResult(capabilities)
  }

  override def shutdown(): Task[ShutdownResult] = Task {
    logger.info("Shutting down...")
    cancelEffects.foreach(_.cancel())
    ShutdownResult()
  }

  private def onChangedFile(
      path: AbsolutePath
  )(fallback: AbsolutePath => Unit): Unit = {
    val name = PathIO.extension(path.toNIO)
    logger.info(s"File $path changed, extension=$name")
    name match {
      case "semanticdb" => fileSystemSemanticdbSubscriber.onNext(path)
      case "compilerconfig" => compilerConfigSubscriber.onNext(path)
      case _ => fallback(path)
    }
  }

  override def onChangeWatchedFiles(changes: Seq[FileEvent]): Unit =
    changes.foreach {
      case FileEvent(
          Uri(path),
          FileChangeType.Created | FileChangeType.Changed
          ) =>
        onChangedFile(path) { _ =>
          logger.warn(s"Unknown file extension for path $path")
        }

      case event =>
        logger.warn(s"Unhandled file event: $event")
        ()
    }
  override def completion(
      request: TextDocumentCompletionRequest
  ): Task[CompletionList] = Task {
    logger.info("completion")
    scalac.getCompiler(request.params.textDocument) match {
      case Some(g) =>
        CompletionProvider.completions(
          g,
          toPoint(request.params.textDocument, request.params.position)
        )
      case None => CompletionProvider.empty
    }
  }

  override def definition(
      request: TextDocumentDefinitionRequest
  ): Task[DefinitionResult] = Task {
    DefinitionProvider.definition(
      symbolIndex,
      Uri.toPath(request.params.textDocument.uri).get,
      request.params.position,
      tempSourcesDir
    )
  }

  override def documentHighlight(
      request: TextDocumentDocumentHighlightRequest
  ): Task[DocumentHighlightResult] = Task {
    DocumentHighlightProvider.highlight(
      symbolIndex,
      Uri.toPath(request.params.textDocument.uri).get,
      request.params.position
    )
  }

  override def documentSymbol(
      request: DocumentSymbolParams
  ): Task[DocumentSymbolResult] = Task {
    val path = Uri.toPath(request.textDocument.uri).get
    buffers.source(path) match {
      case Some(source) => DocumentSymbolProvider.documentSymbols(path, source)
      case None => DocumentSymbolProvider.empty
    }
  }

  override def formatting(
      request: TextDocumentFormattingRequest
  ): Task[DocumentFormattingResult] = Task {
    DocumentFormattingProvider.format(request, scalafmt, buffers, cwd)
  }

  override def hover(
      request: TextDocumentHoverRequest
  ): Task[Hover] = Task {
    scalac.getCompiler(request.params.textDocument) match {
      case Some(g) =>
        HoverProvider.hover(
          g,
          toPoint(request.params.textDocument, request.params.position)
        )
      case None => HoverProvider.empty
    }
  }

  override def references(
      request: TextDocumentReferencesRequest
  ): Task[ReferencesResult] = Task {
    ReferencesProvider.references(
      symbolIndex,
      Uri.toPath(request.params.textDocument.uri).get,
      request.params.position,
      request.params.context
    )
  }

  override def signatureHelp(
      request: TextDocumentSignatureHelpRequest
  ): Task[SignatureHelpResult] = Task {
    scalac.getCompiler(request.params.textDocument) match {
      case Some(g) =>
        SignatureHelpProvider.signatureHelp(
          g,
          toPoint(request.params.textDocument, request.params.position)
        )
      case None => SignatureHelpProvider.empty
    }
  }

  override def onOpenTextDocument(td: TextDocumentItem): Unit =
    Uri.toPath(td.uri).foreach(p => buffers.changed(p, td.text))

  override def onChangeTextDocument(
      td: VersionedTextDocumentIdentifier,
      changes: Seq[TextDocumentContentChangeEvent]
  ): Unit = {
    changes.foreach { c =>
      Uri.toPath(td.uri).foreach(p => buffers.changed(p, c.text))
    }
  }

  override def onCloseTextDocument(td: TextDocumentIdentifier): Unit =
    Uri.toPath(td.uri).foreach(buffers.closed)

  private def toPoint(
      td: TextDocumentIdentifier,
      pos: Position
  ): Cursor = {
    val contents = buffers.read(td)
    val offset = Positions.positionToOffset(
      td.uri,
      contents,
      pos.line,
      pos.character
    )
    Cursor(td.uri, contents, offset)
  }

}

object ScalametaLanguageServer extends LazyLogging {
  lazy val cacheDirectory: AbsolutePath = {
    val path = AbsolutePath(
      ProjectDirectories.fromProjectName("metaserver").projectCacheDir
    )
    Files.createDirectories(path.toNIO)
    path
  }

  def compilerConfigStream(cwd: AbsolutePath)(
      implicit scheduler: Scheduler
  ): (Observer.Sync[AbsolutePath], Observable[CompilerConfig]) = {
    val (subscriber, publisher) = multicast[AbsolutePath]
    val compilerConfigPublished = publisher
      .map(path => CompilerConfig.fromPath(path))
    subscriber -> compilerConfigPublished
  }

  def fileSystemSemanticdbStream(cwd: AbsolutePath)(
      implicit scheduler: Scheduler
  ): (Observer.Sync[AbsolutePath], Observable[Database]) = {
    val (subscriber, publisher) = multicast[AbsolutePath]
    val semanticdbPublisher = publisher
      .map(path => Semanticdbs.loadFromFile(semanticdbPath = path, cwd))
    subscriber -> semanticdbPublisher
  }

  private def multicast[T](implicit s: Scheduler) = {
    val (sub, pub) = Observable.multicast[T](MulticastStrategy.Publish)
    (sub, pub.doOnError(onError))
  }

  private def onError(e: Throwable): Unit = {
    logger.error(e.getMessage, e)
  }
}