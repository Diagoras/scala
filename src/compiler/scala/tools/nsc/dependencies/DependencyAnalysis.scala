package scala.tools.nsc
package dependencies;
import util.SourceFile;
import io.AbstractFile
import collection._
import symtab.Flags

trait DependencyAnalysis extends SubComponent with Files {
  import global._

  val phaseName = "dependencyAnalysis";

  def off = settings.make.value == "all"

  def newPhase(prev : Phase) = new AnalysisPhase(prev)

  lazy val maxDepth = settings.make.value match {
    case "changed" => 0
    case "transitive" | "transitivenocp" => Int.MaxValue
    case "immediate" => 1
  }

  def shouldCheckClasspath = settings.make.value != "transitivenocp"

  // todo: order insensible checking and, also checking timestamp?
  def validateClasspath(cp1: String, cp2: String): Boolean = cp1 == cp2

  def nameToFile(src: AbstractFile, name : String) =
    settings.outputDirs.outputDirFor(src)
      .lookupPathUnchecked(name.toString.replace(".", java.io.File.separator) + ".class", false)

  private var depFile: Option[AbstractFile] = None

  def dependenciesFile_=(file: AbstractFile) {
    assert(file ne null)
    depFile = Some(file)
  }

  def dependenciesFile: Option[AbstractFile] = depFile

  def classpath = settings.classpath.value
  def newDeps = new FileDependencies(classpath);

  var dependencies = newDeps

  def managedFiles = dependencies.dependencies.keySet

  /** Top level definitions per source file. */
  val definitions: mutable.Map[AbstractFile, List[Symbol]] =
    new mutable.HashMap[AbstractFile, List[Symbol]] {
      override def default(f : AbstractFile) = Nil
  }

  /** External references used by source file. */
  val references: mutable.Map[AbstractFile, immutable.Set[String]] =
    new mutable.HashMap[AbstractFile, immutable.Set[String]] {
      override def default(f : AbstractFile) = immutable.Set()
    }

  /** External references for inherited members used in the source file */
  val inherited: mutable.Map[AbstractFile, immutable.Set[Inherited]] =
    new mutable.HashMap[AbstractFile, immutable.Set[Inherited]] {
      override def default(f : AbstractFile) = immutable.Set()
    }

  /** Write dependencies to the current file. */
  def saveDependencies(fromFile: AbstractFile => String) =
    if(dependenciesFile.isDefined)
      dependencies.writeTo(dependenciesFile.get, fromFile)

  /** Load dependencies from the given file and save the file reference for
   *  future saves.
   */
  def loadFrom(f: AbstractFile, toFile: String => AbstractFile) : Boolean = {
    dependenciesFile = f
    FileDependencies.readFrom(f, toFile) match {
      case Some(fd) =>
        val success = if (shouldCheckClasspath) validateClasspath(fd.classpath, classpath) else true
        dependencies = if (success) fd else {
          if (settings.debug.value) {
            println("Classpath has changed. Nuking dependencies");
          }
          newDeps
        }

        success
      case None => false
    }
  }

  def filter(files : List[SourceFile]) : List[SourceFile] =
    if (off) files
    else if (dependencies.isEmpty){
      if(settings.debug.value){
        println("No known dependencies. Compiling everything");
      }
      files
    }
    else {
      val (direct, indirect) = dependencies.invalidatedFiles(maxDepth);
      val filtered = files.filter(x => {
        val f = x.file.absolute
        direct(f) || indirect(f) || !dependencies.containsFile(f);
      })
      filtered match {
        case Nil => println("No changes to recompile");
        case x => println("Recompiling " + (
          if(settings.debug.value) x.mkString(", ")
          else x.length + " files")
        )
      }
      filtered
    }

  case class Inherited(q: String, name: Name)

  class AnalysisPhase(prev : Phase) extends StdPhase(prev){
    def apply(unit : global.CompilationUnit) {
      val f = unit.source.file.file;
      // When we're passed strings by the interpreter
      // they  have no source file. We simply ignore this case
      // as irrelevant to dependency analysis.
      if (f != null){
        val source: AbstractFile = unit.source.file;
        for (d <- unit.icode){
          val name = d.toString
          d.symbol match {
            case s : ModuleClassSymbol =>
              val isTopLevelModule =
                  atPhase (currentRun.picklerPhase.next) {
                    !s.isImplClass && !s.isNestedClass
                  }
              if (isTopLevelModule && (s.linkedModuleOfClass != NoSymbol)) {
                dependencies.emits(source, nameToFile(unit.source.file, name))
              }
              dependencies.emits(source, nameToFile(unit.source.file, name + "$"))
            case _ =>
              dependencies.emits(source, nameToFile(unit.source.file, name))
          }
        }

        for (d <- unit.depends; if (d.sourceFile != null)){
          dependencies.depends(source, d.sourceFile);
        }
      }

      // find all external references in this compilation unit
      val file = unit.source.file
      references += file -> immutable.Set.empty[String]
      inherited += file -> immutable.Set.empty[Inherited]

      val buf = new mutable.ListBuffer[Symbol]

      (new Traverser {
        override def traverse(tree: Tree) {
          if ((tree.symbol ne null)
              && (tree.symbol != NoSymbol)
              && (!tree.symbol.isPackage)
              && (!tree.symbol.hasFlag(Flags.JAVA))
              && ((tree.symbol.sourceFile eq null)
                  || (tree.symbol.sourceFile.path != file.path))
              && (!tree.symbol.isClassConstructor)) {
            updateReferences(tree.symbol.fullNameString)
          }

          tree match {
            case cdef: ClassDef if !cdef.symbol.hasFlag(Flags.PACKAGE) =>
              buf += cdef.symbol
              atPhase(currentRun.erasurePhase.prev) {
                for (s <- cdef.symbol.info.decls)
                  s match {
                    case ts: TypeSymbol if !ts.isClass =>
                      checkType(s.tpe)
                    case _ =>
                  }
              }
              super.traverse(tree)

            case ddef: DefDef =>
              atPhase(currentRun.typerPhase.prev) {
                checkType(ddef.symbol.tpe)
              }
              super.traverse(tree)
            case a @ Select(q, n) if (q.symbol != null) => // #2556
              if (!a.symbol.isConstructor && !a.symbol.owner.isPackage) {
                val tpe1 = q.symbol.tpe match {
                  case MethodType(_, t) => t // Constructor
                  case t                => t
                }
                if (!isSameType(tpe1, a.symbol.owner.tpe))
                  inherited += file -> (inherited(file) + Inherited(tpe1.safeToString, n))
              }
              super.traverse(tree)
            case _            =>
              super.traverse(tree)
          }
        }

        def checkType(tpe: Type): Unit =
          tpe match {
            case t: MethodType =>
              checkType(t.resultType)
              for (s <- t.params) checkType(s.tpe)

            case t: TypeRef    =>
              updateReferences(t.typeSymbol.fullNameString)
              for (tp <- t.args) checkType(tp)

            case t             =>
              updateReferences(t.typeSymbol.fullNameString)
          }

        def updateReferences(s: String): Unit =
          references += file -> (references(file) + s)

      }).apply(unit.body)

      definitions(unit.source.file) = buf.toList
    }
  }
}
