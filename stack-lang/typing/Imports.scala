package typing

import ast.{ Trees => Ast }
import ast.Positions.*

import sast.*
import sast.Symbols.*
import sast.Types.*

import reporting.Reporter

import scala.collection.mutable

object Imports:

  def checkValidContainer
      (sym: Symbol, path: Ast.Word, allowBranch: Boolean)
      (using rp: Reporter, so: Source, ip: InfoProvider)
  : Option[NameTable] =

    if sym.isContainer then
      if !allowBranch && sym.isAllOf(Flags.NSpace | Flags.Branch) then
        rp.error("Only concrete namespaces or sections allowed", path.pos)
        None
      else
        Some(sym.nameTable)

    else
      if ip.info(sym) != ErrorType then
        rp.error("Only a namespace or section can be selected", path.pos)

      None

  def resolveContainer
      (qualid: Ast.RefTree, scope: Scope, rootNameTable: NameTable, allowBranch: Boolean)
      (using rp: Reporter, so: Source, ip: InfoProvider)
  : Option[NameTable] =

    qualid match
      case Ast.Select(qual, name) =>
        val prefix = qual.asInstanceOf[Ast.RefTree]
        val nameTableOpt = resolveContainer(prefix, scope, rootNameTable, allowBranch = true)

        nameTableOpt match
          case Some(nameTable) =>
            nameTable.resolveContainer(name) match
              case Some(sym) =>
                Checker.checkAccess(sym, scope.owner, qualid.span)
                checkValidContainer(sym, qualid, allowBranch)

              case _ =>
                rp.error(s"`$name` is not a member of ${prefix.name}", qualid.pos)
                None

          case _ => None
        end match

      case Ast.Ident(name) =>
        // path needs to be fully qualified
        rootNameTable.resolveContainer(name) match
          case Some(sym) => checkValidContainer(sym, qualid, allowBranch)
          case None =>
            rp.error(s"`$name` is not found", qualid.pos)
            None
        end match

  def doImport
      (qualid: Ast.RefTree, importScope: Scope, rootNameTable: NameTable)
      (using rp: Reporter, so: Source, ip: InfoProvider)
  : List[Symbol] =

    val imports = new mutable.ArrayBuffer[Symbol]
    val alisedMembers = new mutable.ArrayBuffer[Symbol]

    def createAlias(name: String, sym: Symbol): Unit =
      val alias =
        if sym.isTerm then
          val link = TermSymbol.create(name, sym.flags | Flags.Alias, Visibility.Default, importScope.owner, qualid.pos)
          ip.add(link, StaticRef(sym))
          link
        else if sym.isType then
          val link = TypeSymbol.create(sym.asTypeSymbol.kind, name, sym.flags | Flags.Alias, Visibility.Default, importScope.owner, qualid.pos)
          ip.add(link, StaticRef(sym))
          link
        else if sym.isPattern then
          val link = PatternSymbol.create(name, sym.flags | Flags.Alias, Visibility.Default, importScope.owner, qualid.pos)
          ip.add(link, StaticRef(sym))
          link
        else
          ContainerSymbol.create(name, sym.nameTable, sym.flags, Visibility.Default, importScope.owner, qualid.pos)

      imports += alias
      importScope.define(alias)

    def importSymbol(name: String, sym: Symbol): Unit =
      if sym.isAllOf(Flags.NSpace | Flags.Branch) then
        // Do the import anyway to avoid cascading errors
        rp.error("Only concrete namespaces or sections can be imported/aliased", qualid.pos)

      createAlias(name, sym)

    def importName(name: String, nameTable: NameTable): Unit =
      val syms = nameTable.resolve(name)
      for sym <- syms do
        Checker.checkAccess(sym, importScope.owner, qualid.span)
        importSymbol(name, sym)

      if imports.isEmpty && alisedMembers.isEmpty then
          rp.error(s"`$name` cannot be found", qualid.pos)

    def importAll(nameTable: NameTable): Unit =
      def qualify(sym: Symbol) = !sym.isSynthetic & sym.visibleIn(importScope.owner)

      for sym <- nameTable.terms if qualify(sym) do importSymbol(sym.name, sym)

      for sym <- nameTable.patterns if qualify(sym) do importSymbol(sym.name, sym)

      for sym <- nameTable.types if qualify(sym) do importSymbol(sym.name, sym)

      for sym <- nameTable.containers if qualify(sym) do importSymbol(sym.name, sym)

    qualid match
      case Ast.Select(qual, name) =>
        val isStar = name == "*"
        resolveContainer(qual.asInstanceOf[Ast.RefTree], importScope, rootNameTable, allowBranch = !isStar) match
          case Some(nameTable) =>
            if isStar then
              importAll(nameTable)

            else
              importName(name, nameTable)

          case None =>
        end match

      case _ =>
        importName(qualid.name, rootNameTable)
    end match
    imports.toList
