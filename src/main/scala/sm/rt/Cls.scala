package sm
package rt

import collection.mutable


import  sm.{vrt, VM, imm}
import sm.imm.{Access, Type}

final class Var(var x: vrt.Val){
  final def apply() = x
  final def update(y: vrt.Val){
    x = y
  }
}

class Cls(val clsData: imm.Cls, val index: Int)(implicit vm: VM){
  import vm._

  val insns = clsData.methods.map(x => mutable.Seq(x.code.insns:_*))
  lazy val obj = new vrt.Cls(Type.Cls(name))
  val statics =
    clsData.fields.map{f =>
      f.name -> new Var(f.desc.default)
    }.toMap


  def method(name: String, desc: Type.Desc): Option[imm.Method] = {
    ancestry.flatMap(_.methods)
            .find(m => m.name == name && m.desc == desc)
  }

  def resolveStatic(owner: Type.Cls, name: String) = {
    ancestry.dropWhile(_.tpe != owner)
      .find(_.fields.exists(_.name == name))
      .get.tpe.statics(name)
  }

  def apply(owner: Type.Cls, name: String) = {
    resolveStatic(owner, name)()
  }

  def update(owner: Type.Cls, name: String, value: vrt.Val) = {
    resolveStatic(owner, name)() = value
  }

  def name = clsData.tpe.name

  val ancestry = {
    def rec(cd: imm.Cls): List[imm.Cls] = {
      cd.superType match{
        case None => List(cd)
        case Some(x) => cd :: rec(x.clsData)
      }
    }
    rec(clsData)
  }

  val fieldList: Seq[imm.Field] = {
    clsData.superType.toSeq.flatMap(_.fieldList) ++ clsData.fields.filter(_.access.&(Access.Static) == 0)
  }


  lazy val methodList: Seq[MethodRef] = {
    val methods =
      mutable.ArrayBuffer(
        clsData.superType
               .toArray
               .flatMap(_.methodList): _*
      )

    clsData.methods
           .zipWithIndex
           .filter(_._1.access.&(Access.Static) == 0)
           .map{ case (m, i) =>

      val index = methods.indexWhere{ mRef => mRef.name == m.name && mRef.desc == m.desc }

      val nIndex = vm.natives.trappedIndex.indexWhere{case ((n, idesc), func) =>
        (n == name + "/" + m.name) && (idesc == m.desc)
      }

      val update =
        if (index == -1) methods.append(_: MethodRef)
        else methods.update(index, _: MethodRef)

      nIndex match {
        case -1 => update(MethodRef.Cls(this.index, i))
        case n => update(MethodRef.Native(n))

      }
    }

    methods
  }


  def checkIsInstanceOf(desc: Type)(implicit vm: VM): Boolean = {
    import vm._

    val res =
      clsData.tpe == desc ||
      clsData.interfaces.exists(_.checkIsInstanceOf(desc)) ||
      clsData.superType
             .map(l => l.checkIsInstanceOf(desc))
             .getOrElse(false)
    res
  }
}
trait MethodRef{
  def name(implicit vm: VM): String
  def desc(implicit vm: VM): imm.Type.Desc
}
object MethodRef{
  case class Native(index: Int) extends MethodRef{
    def name(implicit vm: VM) = vm.natives.trappedIndex(index)._1._1.reverse.takeWhile(_ != '/').reverse
    def desc(implicit vm: VM) = vm.natives.trappedIndex(index)._1._2
  }
  case class Cls(clsIndex: Int, index: Int) extends MethodRef{
    assert(clsIndex >= 0, "clsIndex can't be negative")
    assert(index >= 0, "index can't be negative")
    def name(implicit vm: VM) = vm.Classes.clsIndex(clsIndex).clsData.methods(index).name

    def desc(implicit vm: VM) = vm.Classes.clsIndex(clsIndex).clsData.methods(index).desc
  }
}
