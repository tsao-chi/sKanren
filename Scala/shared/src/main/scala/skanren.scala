package skanren

import scala.collection.immutable.{HashMap, HashSet}
import cats.implicits._
import scala.collection.parallel.CollectionConverters._
import scala.collection.parallel.immutable.ParVector
import scala.collection.parallel.immutable.ParSeq

//import izumi.reflect.macrortti.LightTypeTag
//import izumi.reflect.macrortti.LTT
//import izumi.reflect.Tag
//def typeOf[T](implicit ev: Tag[T]): LightTypeTag = ev.tag

implicit class mapSequenceParVector[T](xs:ParVector[T]) {
  // todo: optimize me
  def mapSequence[U](x:T=>Option[U]):Option[ParVector[U]] = xs.map(x).seq.sequence.map(_.par)
}

final case class UnifyNormalForm(x: Hole, y: Unifiable)

type UnifyContext = HashMap[Hole, Unifiable]

object UnifyContext {
  val Default: UnifyContext = HashMap()
}

implicit class UnifyContextImpl(ctx: UnifyContext) {
  def add(x: UnifyNormalForm): UnifyContext = x match {
    case UnifyNormalForm(x, y) => if (ctx.contains(x)) throw new IllegalArgumentException() else ctx.updated(x, y)
  }

  def add(xs: List[UnifyNormalForm]): UnifyContext = xs.foldLeft(ctx)((x, y) => x.add(y))
}

trait Unifiable {
  def impl_unify(context: UnifyContext, other: Unifiable): UnifyResult

  final def unify(context: UnifyContext, other: Unifiable): UnifyResult = (this, other) match {
    case (self:UnifiableWrapper,other)=>self.unbox.unify(context,other)
    case (self,other:UnifiableWrapper)=>self.unify(context,other.unbox)
    case (self: Hole, other: Hole) => (self.walkOption(context), other.walkOption(context)) match {
      case (Some(self), Some(other)) => self.unify(context, other)
      case (Some(self), None) => self.unify(context, other)
      case (None, Some(other)) => self.unify(context, other)
      case (None, None) => {
        val result = UnifyNormalForm(self, other)
        Some(context.add(result), List(result))
      }
    }
    case (self: Hole, other) => self.walkOption(context) match {
      case Some(self) => other.unify(context, self)
      case None => {
        val result = UnifyNormalForm(self, other)
        Some(context.add(result), List(result))
      }
    }
    case (self, other: Hole) => other.unify(context, self)
    case (self, other) => self.impl_unify(context, other)
  }

  final def unify(_context: UnifyContext, other: Unifiable, normal: UnifyResult): UnifyResult = for {
    (ctx1, xs) <- normal
    (ctx2, ys) <- this.unify(ctx1, other)
  } yield (ctx2, ys ++ xs)

  final def unify(context: UnifyContext, other: Unifiable, x: Unifiable, y: Unifiable): UnifyResult = this.unify(context, other, x.unify(context, y))

  final def unify(other: Unifiable): UnifyResult = this.unify(UnifyContext.Default, other)
}

trait Wrapper {
  def unbox:Any
}
trait UnifiableWrapper extends Wrapper{
  override def unbox:Unifiable
}

trait Readbackable {
  def readback(context: UnifyContext): Any
  final def readback(context:Context):Any = this.readback(Equal.getFromOrDefault(context))
}

trait UnifiableAtom extends Unifiable {
  override def impl_unify(context: UnifyContext, other: Unifiable): UnifyResult = if (this == other) Some((context, Nil)) else None
}

trait ReadbackableAtom extends Readbackable {
  override def readback(context: UnifyContext): Any = this
}

trait Unifitor[T] {
  def impl_unify(self: T, context: UnifyContext, other: Any): UnifyResult

  final def unify(self: T, context: UnifyContext, other: Any): UnifyResult = (self, other) match {
    case (self, other: UnifitorWrapper[_]) => this.unify(self, context, other.get)
    case (self, other: Hole) => other.unify(context, UnifitorWrapper(self)(this))
    case (self, other) => this.impl_unify(self, context, other)
  }

  final def unify(self: T, _context: UnifyContext, other: Any, normal: UnifyResult): UnifyResult = for {
    (ctx1, xs) <- normal
    (ctx2, ys) <- this.unify(self, ctx1, other)
  } yield (ctx2, ys ++ xs)

  final def unify[U](self: T, context: UnifyContext, other: Any, x: U, y: Any)(implicit u: Unifitor[U]): UnifyResult = this.unify(self, context, other, u.unify(x, context, y))
}

trait Readbacker[T] {
  def readback(self:T,context:UnifyContext):Any
}

trait UnifitorAtom[T] extends Unifitor[T] {
  def impl_unify(self: T, context: UnifyContext, other: Any): UnifyResult = if (self == other) Some((context, Nil)) else None
}

implicit class UnifitorWrapper[T](x: T)(implicit instance: Unifitor[T]) extends Unifiable {
  if (x.isInstanceOf[UnifitorWrapper[_]]) {
    throw new IllegalArgumentException()
  }
  private[skanren] val get = x
  private val getInstance = instance

  override def impl_unify(context: UnifyContext, other: Unifiable): UnifyResult = instance.unify(x, context, other)
}

trait ReadbackerAtom[T] extends Readbacker[T] {
  def readback(self:T,_context:UnifyContext):Any = self
}

implicit class ReadbackerWrapper[T](x:T)(implicit instance: Readbacker[T]) extends Readbackable {
  override def readback(context: UnifyContext): Any = instance.readback(x,context)
}

implicit object SymbolUnifitor extends UnifitorAtom[Symbol]
implicit object SymbolReadbacker extends ReadbackerAtom[Symbol]
implicit object UnitUnifitor extends UnifitorAtom[Unit]
implicit object UnitReadbacker extends ReadbackerAtom[Unit]
implicit object BooleanUnifitor extends UnifitorAtom[Boolean]
implicit object BooleanReadbacker extends ReadbackerAtom[Boolean]

/*
implicit class Tuple2Unifiable[T <: Unifiable, U <: Unifiable](tuple: Tuple2[T, U]) extends Unifiable {
  val get = tuple

  override def impl_unify(context: UnifyContext, other: Unifiable): UnifyResult = other match {
    case other: Tuple2Unifiable[Unifiable, Unifiable] => tuple._1.unify(context, other.get._1, tuple._2, other.get._2)
    case _ => UnifyResultFailure
  }
}
*/
final case class Tuple2Unifitor[T, U]()(implicit t: Unifitor[T], u: Unifitor[U]) extends Unifitor[Tuple2[T, U]] {
  override def impl_unify(self: Tuple2[T, U], context: UnifyContext, other: Any): UnifyResult = other match {
    case other: Tuple2[_, _] => t.unify(self._1, context, other._1, self._2, other._2)
  }
}

final case class Tuple2Readbacker[T,U]()(implicit t:Readbacker[T],u:Readbacker[U]) extends Readbacker[Tuple2[T,U]] {
  override def readback(self:Tuple2[T,U],context:UnifyContext):Any = (t.readback(self._1,context),u.readback(self._2,context))
}

implicit class Tuple2Unifiable[T, U](x: Tuple2[T, U])(implicit t: Unifitor[T], u: Unifitor[U]) extends UnifitorWrapper(x)(Tuple2Unifitor()(t, u))
implicit class Tuple2Readbackable[T,U](x:Tuple2[T,U])(implicit t:Readbacker[T],u:Readbacker[U]) extends ReadbackerWrapper(x)(Tuple2Readbacker()(t,u))

final class UnifiableUnifitor[T <: Unifiable] extends Unifitor[T] {
  override def impl_unify(self: T, context: UnifyContext, other: Any): UnifyResult = other match {
    case other: Unifiable => self.unify(context, other)
    case _ => UnifyResultFailure
  }
}
final class ReadbackableReadbacker[T<:Readbackable] extends Readbacker[T] {
  override def readback(self:T,context:UnifyContext):Any = self.readback(context)
}

implicit val unifiableUnifitor: Unifitor[Unifiable] = UnifiableUnifitor[Unifiable]

type UnifyResult = Option[(UnifyContext, List[UnifyNormalForm])]

val UnifyResultFailure = None

/*
object Hole {
  private var counter: Int = 0

  private def gen: Int = this.synchronized {
    val c = counter
    counter = counter + 1
    c
  }

  def fresh[T](x: Hole => T): T = x(Hole(Symbol("#" + gen)))

  def fresh[T](name: String, x: Hole => T): T = x(Hole(Symbol(name + "#" + gen)))
}
*/

object Hole {
  def fresh[T](x: Hole => T): T = x(Hole(Symbol("#" + x.hashCode)))

  def fresh[T](name: String, x: Hole => T): T = x(Hole(Symbol(name + "#" + x.hashCode)))
}
implicit val holeUnifitor: Unifitor[Hole] = UnifiableUnifitor[Hole]
implicit val holeReadbacker: Readbacker[Hole] = ReadbackableReadbacker[Hole]
final case class Hole(identifier: Symbol) extends Unifiable with Readbackable {
  override def readback(context:UnifyContext):Any = context.getOrElse(this, this)
  def walkOption(context: UnifyContext): Option[Unifiable] = context.get(this) match {
    case Some(next: Hole) => Some(next.walk(context))
    case Some(next) => Some(next)
    case None => None
  }

  def walk(context: UnifyContext): Unifiable = context.get(this) match {
    case Some(next: Hole) => next.walk(context)
    case Some(next) => next
    case None => this
  }

  override def impl_unify(context: UnifyContext, other: Unifiable): UnifyResult = throw new IllegalStateException()
}

trait Constraint {
  val t: ConstraintT

  //val r: ConstraintT

  def reverse: Constraint
}

trait ConstraintOf[T <: ConstraintT] extends Constraint {
  override val t: T
  //override val r: R
  //import ConstraintOf.this.r.ev.a
  //import ConstraintOf.this.r.ev.b
  //override def reverse(context:Context): ConstraintOf.this.r.AConstraint
}

type ReduceResult = Option[Iterable[Constraint]]

trait ConstraintT {
  final def getFromOption(ctx: Context): Option[AConstraintsInContext] = ctx.constraints.get(this).asInstanceOf[Option[AConstraintsInContext]]

  final def getFromOrDefault(ctx: Context): AConstraintsInContext = getFromOption(ctx).getOrElse(default)

  final def setIn(ctx:Context, x: AConstraintsInContext): Context = ctx match {
    case Context(constraints, goals) => Context(constraints.updated(this, x), goals)
  }

  type ReverseT
  val reverse: ReverseT
  type AConstraint
  type AConstraintsInContext
  val default: AConstraintsInContext

  def incl(ctx: Context, x: AConstraint): Option[AConstraintsInContext] = this.incls(ctx, List(x))

  def incls(ctx: Context, xs: List[AConstraint]): Option[AConstraintsInContext] = xs match {
    case Nil => Some(getFromOrDefault(ctx))
    case x :: Nil => this.incl(ctx, x)
    case x :: xs => this.incl(ctx, x).flatMap(a=>this.incls(setIn(ctx,a), xs))
  }

  def normalForm(ctx: Context): Option[AConstraintsInContext] = Some(getFromOrDefault(ctx))

  protected final class Ev(implicit a: AConstraint <:< ConstraintOf[this.type], b: ReverseT <:< ConstraintT) // c: reverse.ReverseT =:= this.type

  val ev: Ev

  import ConstraintT.this.ev.a

  import ConstraintT.this.ev.b

}

trait ConstraintTSet extends ConstraintT {
  override type AConstraintsInContext = Set[AConstraint]

  override def incl(ctx: Context, x: AConstraint): Option[AConstraintsInContext] = Some(getFromOrDefault(ctx).incl(x))
}

// ctx: HashMap[(a: ConstraintT, a.AConstraintsInContext)]
final case class Context(constraints: HashMap[ConstraintT, Any], goals: List[Goal]) {
  def addConstraint(x: Constraint): Option[Context] = for {
    newT <- x.t.incl(this,x.asInstanceOf[x.t.AConstraint])
  } yield x.t.setIn(this, newT)

  def addGoal(x: Goal): Option[Context] = addGoals(List(x))

  def addGoals(xs: List[Goal]): Option[Context] = {
    val (newConstraints0, newGoals) = xs.partition(_.isInstanceOf[GoalConstraint])
    val newConstraints = newConstraints0.map(_.asInstanceOf[GoalConstraint].x)
    val newcs = Context.listABToMapAListB(newConstraints.map(x=>(x.t, x)), HashMap()).toList
    Context.doConstraintss(Context(this.constraints,goals++newGoals),newcs)
  }
  //def toNormalIfIs: Option[ContextNormalForm] = if (goals.isEmpty) Some(ContextNormalForm(constraints)) else None
  def caseNormal: Either[Context, ContextNormalForm] =if (goals.isEmpty) Right(ContextNormalForm(constraints)) else Left(this)
}
object Context {
  val Empty = Context(HashMap(),List())
private def listABToMapAListB[A,B](xs:List[(A,B)], base: HashMap[A,List[B]]):HashMap[A,List[B]] = xs match {
  case (k,v)::xs=> listABToMapAListB(xs, base.updated(k,v::base.getOrElse(k,Nil)))
  case Nil => base
}

  private def doConstraints(ctx:Context, t: ConstraintT, cs: List[Constraint]): Option[Context] = for {
    newT <- t.incls(ctx, cs.map(_.asInstanceOf[t.AConstraint]))
  } yield t.setIn(ctx,newT)
  private def doConstraintss(ctx:Context,xs:List[(ConstraintT,List[Constraint])]):Option[Context] = xs match {
    case (t,cs)::xs=>for {
      a <- doConstraints(ctx,t,cs)
      result <- doConstraintss(a, xs)
    } yield result
    case Nil => Some(ctx)
  }
}

final case class ContextNormalForm(constraints: HashMap[ConstraintT, Any])

type State = ParSeq[Context]
object State {
  val Empty = ParSeq(Context.Empty)
}

implicit class StateImpl(x: State) {
  def addUnrolledGoal(goal: UnrolledGoal): State = (for {
    adds <- goal.par
    ctx <- x
  } yield ctx.addGoals(adds)).flatten
  def addGoal(goal: Goal): State = addUnrolledGoal(UnrolledGoal.unrollN(goal))

  def reduce: Option[State] = if (x.isEmpty) None else Some(x.flatMap({ case Context(constraints, goals) =>
    val ctx0 = Context(constraints, List())
    (for {
      goals <- UnrolledGoal.unrollN(goals).par
    } yield ctx0.addGoals(goals)).flatten
  }))

  def run1: Option[(Seq[ContextNormalForm], State)] = this.reduce.flatMap({xs =>
    xs.map(_.caseNormal).partition(_.isRight) match {
      case (lefts,rights) =>{
        val ctxs = lefts.map(_.left.get)
        val normals = rights.map(_.right.get)
        if (normals.isEmpty) ctxs.run1 else Some(normals.seq, ctxs)
      }
    }
  })

  def runAll: Seq[ContextNormalForm] = this.run1 match {
    case None => Nil
    case Some((x, s)) => x ++ s.runAll
  }
}

// todo
type UnrolledGoal = List[List[Goal]]

object UnrolledGoal {
  val Succeed: UnrolledGoal = List(List())

  def andUnrolledGoal(x: UnrolledGoal, y: UnrolledGoal): UnrolledGoal = for {
    a <- x
    b <- y
  } yield a ++ b

  def andUnrolledGoals(xs: List[UnrolledGoal]): UnrolledGoal = xs match {
    case Nil => Succeed
    case x :: xs => andUnrolledGoal(andUnrolledGoals(xs), x)
  }

  def orUnrolledGoal(x: UnrolledGoal, y: UnrolledGoal): UnrolledGoal = x ++ y

  def orUnrolledGoals(xs: List[UnrolledGoal]): UnrolledGoal = xs.flatten

  def unrollUnrolled(x: UnrolledGoal): UnrolledGoal = orUnrolledGoals(x.map(universe => andUnrolledGoals(universe.map(_.unroll))))

  def unrollN(x: Goal): UnrolledGoal = UnrolledGoal.unrollUnrolled(UnrolledGoal.unrollUnrolled(UnrolledGoal.unrollUnrolled(UnrolledGoal.unrollUnrolled(x.unroll))))

  def unrollN(x: List[Goal]): UnrolledGoal = UnrolledGoal.orUnrolledGoals(x.map(UnrolledGoal.unrollN(_)))
  //def unrollN(x:Iterable[Goal]): UnrolledGoal = UnrolledGoal.unrollN(x.toList)
}

sealed trait Goal {
  def reverse: Goal

  def unroll: UnrolledGoal

  final def runAll: Seq[ContextNormalForm] = State.Empty.addGoal(this).runAll

  final def &&(other:Goal):Goal=GoalAnd(this,other)
  final def ||(other:Goal):Goal=GoalOr(this,other)
  final def unary_! :Goal=GoalNot(this)
}

object Goal {
  def apply(x: =>Goal):Goal = GoalDelay({x})
  def exists(x: Hole=>Goal): Goal = Hole.fresh(x)
  def exists(name:String,x: Hole=>Goal):Goal=Hole.fresh(name,x)
  def implies(a:Goal, b:Goal): Goal = GoalOr(GoalNot(a),b)
  def forall(x:Hole=>(Goal, Goal)):Goal = Goal.exists( hole => {
    val g = x(hole)
    Goal.implies(g._1,g._2)
  })
  def forall(name:String,x:Hole=>(Goal, Goal)):Goal = Goal.exists(name, hole => {
    val g = x(hole)
    Goal.implies(g._1,g._2)
  })
}

final case class GoalConstraint(x: Constraint) extends Goal {
  override def reverse: Goal = GoalConstraint(x.reverse)

  override def unroll: UnrolledGoal = List(List(this))
}

final case class GoalSuccess() extends Goal {
  override def reverse: Goal = GoalFailure()
  override def unroll: UnrolledGoal = List(List())
}
final case class GoalFailure() extends Goal {
  override def reverse: Goal = GoalSuccess()
  override def unroll: UnrolledGoal = List()
}

final case class GoalOr(x: Goal, y: Goal) extends Goal {
  override def reverse: Goal = GoalAnd(x.reverse, y.reverse)

  override def unroll: UnrolledGoal = List(List(x), List(y))
}

final case class GoalAnd(x: Goal, y: Goal) extends Goal {
  override def reverse: Goal = GoalOr(x.reverse, y.reverse)

  override def unroll: UnrolledGoal = List(List(x, y))
}

final case class GoalNot(x: Goal) extends Goal {
  lazy val get = x.reverse

  override def reverse: Goal = x

  override def unroll: UnrolledGoal = List(List(this.get))
}

final class GoalDelay(generate: => Goal) extends Goal {
  lazy val get = generate

  override def reverse: Goal = GoalDelay(this.get.reverse)

  override def unroll: UnrolledGoal = List(List(this.get))
}

final case class Unify(x: Unifiable, y: Unifiable) extends ConstraintOf[Equal.type] {
  override val t = Equal

  override def reverse: NegativeUnify = NegativeUnify(x, y)

  def apply(context: UnifyContext): UnifyResult = x.unify(context, y)
}

object Equal extends ConstraintT {
  override type AConstraintsInContext = UnifyContext
  override type AConstraint = Unify
  override type ReverseT = NotEqual.type
  override val reverse = NotEqual
  override val default = UnifyContext.Default

  override def incl(ctx: Context, x: AConstraint): Option[AConstraintsInContext] = x(getFromOrDefault(ctx)) match {
    case Some(newctx, _adds) => Some(newctx)
    case None => None
  }

  override val ev = Ev()
}

final case class NegativeUnify(x: Unifiable, y: Unifiable) extends ConstraintOf[NotEqual.type] {
  override val t = NotEqual

  override def reverse: Unify = Unify(x, y)
}

object NotEqual extends ConstraintT {
  override type AConstraintsInContext = ParVector[UnifyNormalForm]
  override type AConstraint = NegativeUnify
  override type ReverseT = Equal.type
  override val reverse = Equal
  override val default = ParVector()

  private def toNegativeUnify(x: UnifyNormalForm): NegativeUnify = x match {
    case UnifyNormalForm(a, b) => NegativeUnify(a, b)
  }

  override def incls(ctx: Context, xs: List[AConstraint]): Option[AConstraintsInContext] = for {
    adds <- norms(Equal.getFromOrDefault(ctx), xs)
  } yield getFromOrDefault(ctx)++adds

  private def norms(equalCtx: UnifyContext, xs: List[NegativeUnify]): Option[List[UnifyNormalForm]] = xs.map(norm(equalCtx, _)).sequence.map(_.flatten)
  private def norm(equalCtx: UnifyContext, x: NegativeUnify): Option[List[UnifyNormalForm]] = x match {
    case NegativeUnify(a, b) => a.unify(equalCtx, b) match {
      case None => Some(List())
      case Some(ctx, Nil) => None
      case Some(ctx, adds@(_ :: _)) => Some(adds)
    }
  }

  private def normal(equalCtx: UnifyContext, notEqCtx: AConstraintsInContext): Option[AConstraintsInContext] =notEqCtx.mapSequence(x=>norm(equalCtx,toNegativeUnify(x))).map(_.flatten)
  override def normalForm(ctx: Context): Option[AConstraintsInContext] = normal(Equal.getFromOrDefault(ctx), getFromOrDefault(ctx))

  override val ev = Ev()
}

// todo
trait Generator[T] {
  val generate: LazyList[T]
}

def mergeLazyList[T, U](xs: LazyList[T], ys: LazyList[U]): LazyList[T | U] = xs match {
  case head #:: tail => head #:: mergeLazyList(ys, tail)
  case _ => ys
}

final case class SimpleGenerator[T](override val generate: LazyList[T]) extends Generator[T]

trait FiniteGenerator[T] extends Generator[T] {
  override val generate: LazyList[T] = LazyList.empty.concat(generateFinite)
  lazy val generateFinite: List[T]
}

implicit class GeneratorImpl[T](x: Generator[T]) {
  def or[U](y: Generator[U]): Generator[T | U] = SimpleGenerator(mergeLazyList(x.generate, y.generate))
}

object generators {
}


def exists(x: Hole=>Goal) = Goal(Goal.exists(x))
def exists(name:String,x: Hole=>Goal) = Goal(Goal.exists(name,x))
def begin(xs: =>Goal*): Goal = Goal(xs.foldLeft(GoalSuccess():Goal)(GoalAnd(_,_)))
def conde(xs: =>Goal*):Goal = Goal(xs.foldLeft(GoalFailure():Goal)(GoalOr(_,_)))
implicit class UnifiableOps[T](x:T)(implicit ev: T <:< Unifiable) {
  def ===[U<:Unifiable](other:U) = GoalConstraint(Unify(x,other))
  def =/=[U<:Unifiable](other:U) = GoalConstraint(NegativeUnify(x,other))
}
implicit class UnifitorOps[T](x:T)(implicit ev: Unifitor[T]) {
  def ===[U<:Unifiable](other:U) = GoalConstraint(Unify(x,other))
  def =/=[U<:Unifiable](other:U) = GoalConstraint(NegativeUnify(x,other))
}
object sexp {
implicit val SExpUnifitor: Unifitor[SExp] = UnifiableUnifitor[SExp]
implicit val SExpReadbacker: Readbacker[SExp] = ReadbackableReadbacker[SExp]
  sealed trait SExp extends Unifiable with Readbackable
  case object Empty extends SExp with UnifiableAtom with ReadbackableAtom
  final case class Pair(x:SExp,y:SExp) extends SExp with Unifiable with Readbackable {
    override def impl_unify(context: UnifyContext, other: Unifiable): UnifyResult = other match {
      case Pair(x1,y1)=>x.unify(context,x1,y,y1)
      case _ => UnifyResultFailure
    }
    // todo
    override def readback(context:UnifyContext):Any = (x.readback(context),y.readback(context))
  }
  def cons(x:SExp,y:SExp) = Pair(x,y)
  private def seq2SExp(xs:Seq[SExp]):SExp= xs match {
    case head +: tail => cons(head, seq2SExp(tail))
    case Seq() => Empty
  }
  def list(xs:SExp*) = seq2SExp(xs)
  final case class Sym(x:Symbol) extends SExp with UnifiableAtom with ReadbackableAtom
  object Sym {
    def apply(x:String):Sym=Sym(Symbol(x))
    def apply(x:Symbol):Sym=new Sym(x)
  }
  sealed trait Bool extends SExp with UnifiableAtom with ReadbackableAtom
  case object True extends Bool
  case object False extends Bool
  final case class SExpHole(x:Hole) extends SExp with Unifiable with Readbackable with UnifiableWrapper {
    override def unbox: Unifiable = x
    override def impl_unify(context: UnifyContext, other: Unifiable): UnifyResult = throw new UnsupportedOperationException()
    override def readback(context:UnifyContext):Any = x.readback(context)
  }
  implicit def hole2sexp(x:Hole):SExp = SExpHole(x)
}
