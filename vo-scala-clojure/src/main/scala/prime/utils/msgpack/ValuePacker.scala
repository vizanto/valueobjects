package prime.utils.msgpack

import org.msgpack.Packer
import java.lang.Math
import java.io.OutputStream
import org.bson.types.ObjectId
import prime.types.{Conversion, VORef, Ref, RefArray, EmailAddr, EnumValue, RGBA, FileRef, URI, DateTime, Date}
import prime.vo._
import Util._

/**
 * Created by IntelliJ IDEA.
 * User: blue
 * Date: 04-01-11
 * Time: 15:01
 * To change this template use File | Settings | File Templates.
 */

abstract class ValuePacker(out:OutputStream) extends Packer(out)
{
  def pack(vo : ValueObject);
  def pack(vo : mutable.ValueObject);

  final def pack(v : EnumValue) {
    if (v.isInstanceOf[scala.Product]) super.pack(v.toString)
    else                               super.pack(v.value)
  }

  final def pack(ref: VORef[_ <: ValueObject]) {
    if (ref.isDefined) pack(ref.get)
    else               pack(ref._id)
  }

  final def pack(ref: Ref[_ <: ValueObject]) {
    if (ref.vo_! != null) pack(ref.vo_!)
    else ref.ref match {
      case id:ObjectId => pack(id)
      case ref:AnyRef  => pack(ref)
    }
  }

  final def pack(refArray: RefArray[_ <: ValueObject]) {
    pack(refArray.voArray)
  }

  final def pack[V <: ValueObject](voArray:Array[V]) {
    packArray(voArray.length)
    for (item <- voArray) pack(item);
  }


  final def writeByte(v : Int) {
    out.write(v.asInstanceOf[Byte]);
  }

  final def pack(id : ObjectId)
  {
    out.write(0xD7)
    out.write(0x1D)
    out.write(id.toByteArray);
  }

  final def pack(v : IndexedSeq[_]) {
    packArray(v.length)
    for (item <- v) pack(item);
  }

  final def pack(fileRef : FileRef)                 { super.pack(fileRef.toString);       }
  final def pack(uri     : URI)                     { super.pack(Conversion.String(uri)); }
  final def pack(email   : EmailAddr)               { super.pack(email.toString);         }
  final def pack(rgba    : RGBA)                    { super.pack(rgba.rgba);              }

  final def pack(date : org.joda.time.DateTime)     { pack( date.toInstant );       }
  final def pack(date : org.joda.time.DateMidnight) { pack( date.toInstant );       }
  final def pack(date : org.joda.time.Instant)      { super.pack( date.getMillis ); }

  final def pack(fileRefArray: Array[FileRef]) {
    packArray(fileRefArray.length)
    for (item <- fileRefArray) pack(item);
  }

  override final def pack(any : Any) : this.type = { any match {
    case v : String                   => super.pack(v);
    case v : Number                   => super.pack(v);
    case v : ObjectId                 => pack(v);
    case v : DateTime                 => pack(v);
    case v : Date                     => pack(v);
    case v : URI                      => pack(v);
    case v : FileRef                  => pack(v);
    case v : RGBA                     => pack(v);
    case v : EmailAddr                => pack(v);
    case v : ValueObject              => pack(v);
    case v : VORef[_]                 => pack(v);
    case v : Ref[_]                   => pack(v);
    case v : EnumValue                => pack(v);
    case v : mutable.ValueObject      => pack(v);
    case v : IndexedSeq[_]            => pack(v);
    case null                         => packNil();
    case v                            => println("Fallback: " + v.getClass); super.pack(v);
  }; this }

  final def packValueObjectHeader(voType : Int, mixins : Int, fieldFlagBytes : Int)
  {
    val firstByte:Int = mixins << 3 | bytesUsedInInt(fieldFlagBytes)

    // pack ValueObject header
    if (voType <= 255) {
      castBytes(0) = (firstByte | 0x80).byteValue
      castBytes(1) = voType.byteValue
      out.write(castBytes, 0, 2);
      //println("packValueObjectHeader(voType: %1$3s [0x%1$h], mixins: %2$s [0x%2$h], fieldFlagBytes: 0x%3$2h) => %4$2h %5$2h".format(voType, mixins, fieldFlagBytes, castBytes(0) & 0xFF, castBytes(1)))
    } else {
      castBytes(0) = (firstByte | 0xC0).byteValue
      castBytes(1) = (voType >> 8).byteValue
      castBytes(2) = voType.byteValue
      out.write(castBytes, 0, 3);
    }
  }
}

object Util {
  final def bytesUsedInInt (n:Int) : Byte = {
         if ( n         == 0) 0
    else if ((n >>>  8) == 0) 1
    else if ((n >>> 16) == 0) 2
    else if ((n >>> 24) == 0) 3
    else                      4
  }
}