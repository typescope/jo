package typing

import ast.Ast
import ast.Positions.*

import sast.*
import sast.Symbols.*
import sast.Types.*

import common.OutOfBand
import reporting.Reporter

import scala.collection.mutable

object Imports:
  def doImport
      (qualid: Ast.RefTree, importScope: Scope, rootNameTable: NameTable, isAlias: Boolean)
      (using rp: Reporter, so: Source, ip: InfoProvider)
  : List[Symbol] =

    def checkValidContainer(sym: Symbol, path: Ast.Word, allowBranch: Boolean): Option[NameTable] =
      if sym.isContainer && (!sym.isAlias || !isAlias) then
        if !allowBranch && sym.isAllOf(Flags.NSpace | Flags.Branch) then
          rp.error("Only concrete namespaces or sections allowed", path.pos)
          None
        else
          Some(ip.dealiasedInfo(sym).as[ContainerInfo].nameTable)

      else
        if sym.isContainer then
          rp.error("Aliasing an alias is forbidden", path.pos)

        else if ip.info(sym) != ErrorType then
          rp.error("Only a namespace or section can be selected", path.pos)

        None


    def resolveContainer(qualid: Ast.RefTree, allowBranch: Boolean): Option[NameTable] =
      qualid match
        case Ast.Select(qual, name) =>
          val prefix = qual.asInstanceOf[Ast.RefTree]
          val nameTableOpt = resolveContainer(prefix, allowBranch = true)

          nameTableOpt match
            case Some(nameTable) =>
              nameTable.resolveTerm(name) match
                case Some(sym) => checkValidContainer(sym, qualid, allowBranch)

                case _ =>
                  rp.error(s"`$name` is not a member of ${prefix.name}", qualid.pos)
                  None

            case _ => None
          end match

        case Ast.Ident(name) =>

          def tryRootNameTable(): Option[NameTable] =
            rootNameTable.resolveTerm(name) match
              case Some(sym) => checkValidContainer(sym, qualid, allowBranch)
              case None =>
                rp.error(s"`$name` is not found", qualid.pos)
                None
            end match

          if isAlias then
            given OutOfBand = new OutOfBand
            // aliases can be not fully qualified and it prefers local defined symbols over global symbols
            importScope.resolveTerm(name) match
              case Some(sym) => checkValidContainer(sym, qualid, allowBranch)
              case None => tryRootNameTable()
            end match
          else
            // Imports needs to be fully qualified
            tryRootNameTable()

    val imports = new mutable.ArrayBuffer[Symbol]
    val alisedMembers = new mutable.ArrayBuffer[Symbol]

    def createAlias(name: String, sym: Symbol): Unit =
      val alias =
        if sym.isType then
          TypeSymbol.create(sym.asTypeSymbol.kind, name, StaticRef(sym), sym.flags | Flags.Alias, importScope.owner, qualid.pos)
        else
          val link = Symbol.createSymbol(name, sym.flags | Flags.Alias, qualid.pos)
          ip.add(link, importScope.owner, StaticRef(sym))
          link

      imports += alias
      importScope.define(alias)

    def importSymbol(name: String, sym: Symbol): Unit =
        if sym.isAllOf(Flags.NSpace | Flags.Branch) then
          // Do the import anyway to avoid cascading errors
          rp.error("Only concrete namespaces or sections can be imported/aliased", qualid.pos)

        if sym.isAlias && isAlias then
          rp.error(s"Aliasing an alias is disallowed: `$name`", qualid.pos)
          alisedMembers += sym

        else
          createAlias(name, sym)

    def importName(name: String, nameTable: NameTable): Unit =
      val syms = nameTable.resolve(name)
      for sym <- syms do importSymbol(name, sym)

      if imports.isEmpty && alisedMembers.isEmpty then
          rp.error(s"`$name` cannot be found", qualid.pos)

    /** For aliasing, aliased members are ignored */
    def importAll(nameTable: NameTable): Unit =
      def qualify(sym: Symbol) = !sym.isSynthetic && (!sym.isAlias || !isAlias)

      for sym <- nameTable.terms if qualify(sym) do importSymbol(sym.name, sym)

      for sym <- nameTable.patterns if qualify(sym) do importSymbol(sym.name, sym)

      for sym <- nameTable.types if qualify(sym) do importSymbol(sym.name, sym)

    qualid match
      case Ast.Select(qual, name) =>
        val isStar = name == "*"
        resolveContainer(qual.asInstanceOf[Ast.RefTree], allowBranch = !isStar) match
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
