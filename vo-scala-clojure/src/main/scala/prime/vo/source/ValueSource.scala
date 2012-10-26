package prime.vo.source;
 import prime.types.Conversion._

//
// Interfaces
//

trait ValueSourceable {
  def as_source : ValueSource;
}
// Geimplementeerd door objecten welke state moet bijhouden
/*
(defprotocol ValueSourceable (as-source [this target-VO-ID]))
*/

trait ValueSource extends ValueSourceable {
  /** Returns baseTypeID, or if known: the type-id this ValueSource represents. */
  def typeID  (baseTypeID : Int)      : Int     = baseTypeID;;

  def contains(name:String, orIdx:Int): Boolean;
  /**
    Tries to lookup the value for the given name. 
    If that fails: tries to lookup the value by index.
    
    VOTypeMask   = 0xFFFF0000
    VOIndexMask  = 0x0000FF00
    BitIndexMask = 0x000000FF

    Returns 'notFound if both name and index are empty/missing/unknown/undefined.
  */
  def anyAt   (name: String, idx: Int, notFound: Any     ): Any;
  def boolAt  (name: String, idx: Int, notFound: Boolean ): Boolean;
  def doubleAt(name: String, idx: Int, notFound: Double  ): Double;
  def intAt   (name: String, idx: Int, notFound: Int     ): Int;

  def anyAt   (name: String, idx: Int): Any     =    anyAt(name, idx, null);
  def boolAt  (name: String, idx: Int): Boolean =   boolAt(name, idx, false);
  def doubleAt(name: String, idx: Int): Double  = doubleAt(name, idx, Double.NaN);
  def intAt   (name: String, idx: Int): Int     =    intAt(name, idx, Int.MinValue);

  final def as_source = this;
}

trait Booleans {
  this : ValueSource =>

  final override def anyAt   (name: String, idx: Int, notFound: Any   ): Any      = if (contains(name,idx)) boolAt(name, idx) else notFound;
  final override def intAt   (name: String, idx: Int, notFound: Int   ): Int      = if (contains(name,idx)) (if (boolAt(name, idx)) 1   else 0)   else notFound;
  final override def doubleAt(name: String, idx: Int, notFound: Double): Double   = if (contains(name,idx)) (if (boolAt(name, idx)) 1.0 else 0.0) else notFound;
}

trait Integers {
  this : ValueSource =>

  final override def anyAt   (name: String, idx: Int, notFound: Any    ): Any     = if (contains(name,idx)) intAt(name, idx) else notFound;
  final override def boolAt  (name: String, idx: Int, notFound: Boolean): Boolean = 0 < intAt(name, idx, if (notFound) 1 else 0);
  final override def doubleAt(name: String, idx: Int, notFound: Double ): Double  = intAt(name, idx, notFound.toInt) toDouble
}

trait Doubles {
  this : ValueSource =>

  final override def anyAt   (name: String, idx: Int, notFound: Any    ): Any     = if (contains(name,idx)) doubleAt(name, idx) else notFound;
  final override def boolAt  (name: String, idx: Int, notFound: Boolean): Boolean = 0.0 < doubleAt(name, idx, if (notFound) 1.0 else 0.0);
  final override def intAt   (name: String, idx: Int, notFound: Int    ): Int     = doubleAt(name, idx, notFound.toDouble) toInt
}

trait NoPrimitives {
  this : ValueSource =>

  final override def boolAt  (name: String, idx: Int, notFound: Boolean): Boolean = if (contains(name,idx)) Boolean(anyAt(name,idx)) else notFound;
  final override def intAt   (name: String, idx: Int, notFound: Int    ): Int     = if (contains(name,idx)) Integer(anyAt(name,idx)) else notFound;
  final override def doubleAt(name: String, idx: Int, notFound: Double ): Double  = if (contains(name,idx)) Decimal(anyAt(name,idx)) else notFound;
}


trait IndexedValueSource [FieldDataType] {
  def    contains(idx: Int): Boolean;
  def       apply(idx: Int, notFound: FieldDataType): FieldDataType;
  final def apply(idx: Int): FieldDataType = apply(idx, null.asInstanceOf[FieldDataType]);
}

trait NamedValueSource [FieldDataType] {
  def    contains(name: String): Boolean;
  def       apply(name: String, notFound: FieldDataType): FieldDataType;
  final def apply(name: String): FieldDataType = apply(name, null.asInstanceOf[FieldDataType]);
}

// 
// More specific ValueSources
// 

trait ImmutableValueSource extends ValueSource {

}

trait Streamable {
  this : ValueSource =>
}

trait Updateable {
  this : ValueSource =>
}

//
// Helpers and micro-implementations
//

object ValueSource
{
  import clojure.lang._
  RT.loadResourceScript("prime/vo/source.clj");
  protected[prime] val asValueSource : IFn = RT.`var`("prime.vo.source", "as-source");

  def   apply(any : Any, kind : Any) : ValueSource = any match {
    case null => null
    case v : ValueSource => v

    case any /* Try Clojure protocols */ =>
      asValueSource.invoke(any, kind).asInstanceOf[ValueSource]
  }

  def   apply(any : Any) = any match {
    case null => null
    case v : ValueSource => v

    case any /* Try Clojure protocols */ =>
      asValueSource.invoke(any).asInstanceOf[ValueSource]
  }

  def unapply(any : Any) : Option[ValueSource] = try Option(apply(any)) catch { case _ => None }

  object empty extends ValueSource {
    def contains (name: String, i: Int)                               = false;
    def anyAt    (name: String, i: Int, notFound: Any)     : Any      = notFound;
    def intAt    (name: String, i: Int, notFound: Int)     : Int      = notFound;
    def doubleAt (name: String, i: Int, notFound: Double)  : Double   = notFound;
    def boolAt   (name: String, i: Int, notFound: Boolean) : Boolean  = notFound;
  }
}

class AnonymousValueSource(val values : Any*) extends ValueSource {
  def contains (index: Int): Boolean                                  = (index & 0xFF) < values.length;
  def contains (ignored: String, i: Int): Boolean                     = contains(i);
  def anyAt    (ignored: String, i: Int, notFound: Any)     : Any     = { val idx = (i & 0xFF); if (idx < values.length)         values(idx)  else notFound; }
  def intAt    (ignored: String, i: Int, notFound: Int)     : Int     = { val idx = (i & 0xFF); if (idx < values.length) Integer(values(idx)) else notFound; }
  def doubleAt (ignored: String, i: Int, notFound: Double)  : Double  = { val idx = (i & 0xFF); if (idx < values.length) Decimal(values(idx)) else notFound; }
  def boolAt   (ignored: String, i: Int, notFound: Boolean) : Boolean = { val idx = (i & 0xFF); if (idx < values.length) Boolean(values(idx)) else notFound; }

  override def toString(): String = getClass.getName +"("+ values.mkString(",") +")";
}

case class SingleValueSource[@specialized(Int,Double) T](key : String, value : T) extends ValueSource with NamedValueSource[T] {
  def apply    (name: String, notFound: T) : T = if (key == name) value else notFound;
  def contains (name: String): Boolean         = key == name;

  def contains (name: String, i: Int)                    : Boolean = contains(name);
  def anyAt    (name: String, i: Int, notFound: Any)     : Any     = if (name == key) value          else notFound;
  def intAt    (name: String, i: Int, notFound: Int)     : Int     = if (name == key) Integer(value) else notFound;
  def doubleAt (name: String, i: Int, notFound: Double)  : Double  = if (name == key) Decimal(value) else notFound;
  def boolAt   (name: String, i: Int, notFound: Boolean) : Boolean = if (name == key) Boolean(value) else notFound;
}
