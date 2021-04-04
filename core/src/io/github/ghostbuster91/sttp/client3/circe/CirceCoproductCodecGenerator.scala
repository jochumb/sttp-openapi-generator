package io.github.ghostbuster91.sttp.client3.circe

import io.github.ghostbuster91.sttp.client3._
import scala.meta._

class CirceCoproductCodecGenerator(ir: ImportRegistry) {

  def encoder(coproduct: Coproduct) =
    coproduct.discriminator.flatMap { discriminator =>
      if (discriminator.mapping.nonEmpty) {
        ir.registerImport(q"import _root_.io.circe.HCursor")
        ir.registerImport(q"import _root_.io.circe.Json")
        ir.registerImport(q"import _root_.io.circe.DecodingFailure")
        ir.registerImport(q"import _root_.io.circe.Decoder.Result")
        val coproductType = Type.Name(coproduct.name)
        val encoderName =
          Pat.Var(Term.Name(s"${coproduct.uncapitalizedName}Encoder"))
        val coproductVarName = Term.Name(coproduct.uncapitalizedName)
        val cases = encoderCases(discriminator)
        Some(
          q"""implicit val $encoderName: Encoder[$coproductType] = new Encoder[$coproductType] {
            override def apply($coproductVarName: $coproductType): Json = 
              $coproductVarName match {
                ..case $cases
            }
          }
          """
        )
      } else {
        None
      }
    }

  def decoder(coproduct: Coproduct) =
    coproduct.discriminator.flatMap { discriminator =>
      if (discriminator.mapping.nonEmpty) {
        val cases = decoderCases(discriminator)
        val coproductType = Type.Name(coproduct.name)
        val decoderName =
          Pat.Var(Term.Name(s"${coproduct.uncapitalizedName}Decoder"))
        val dscType = discriminator match {
          case _: Discriminator.StringDsc        => t"String"
          case _: Discriminator.IntDsc           => t"Int"
          case Discriminator.EnumDsc(_, enum, _) => Type.Name(enum.name)
        }

        Some(
          q"""implicit val $decoderName: Decoder[$coproductType] = new Decoder[$coproductType] {
            override def apply(c: HCursor): Result[$coproductType] = 
              c.downField(${discriminator.fieldName}).as[$dscType].flatMap {
                ..case $cases
            }
          }"""
        )
      } else {
        None
      }
    }

  private def encoderCases(
      discriminator: Discriminator[_]
  ): List[Case] = {
    val encoderCasesForTypes = (discriminator match {
      case Discriminator.StringDsc(_, mapping) =>
        mapping.values.map(encoderCaseForString)
      case Discriminator.IntDsc(_, mapping) =>
        mapping.values.map(encoderCaseForInt)
      case Discriminator.EnumDsc(_, _, mapping) =>
        mapping.values.map(encoderCaseForEnum)
    })
    encoderCasesForTypes.map { case EncoderCase(when, child) =>
      p"case $when => Encoder[${child.typeName}].apply(${child.toVar})"
    }.toList
  }

  private def encoderCaseForString(child: CoproductChild) = {
    val patVarChild = Pat.Var(child.toVar)
    val typedPatVar = p"$patVarChild: ${child.typeName}"
    EncoderCase(typedPatVar, child)

  }

  private def encoderCaseForInt(child: CoproductChild) = {
    val patVarChild = Pat.Var(child.toVar)
    val typedPatVar = p"$patVarChild: ${child.typeName}"
    EncoderCase(typedPatVar, child)
  }

  private def encoderCaseForEnum(child: CoproductChild) = {
    val patVarChild = Pat.Var(child.toVar)
    val typedPatVar = p"$patVarChild: ${child.typeName}"
    EncoderCase(
      typedPatVar,
      child
    )
  }

  private def decoderCases(discriminator: Discriminator[_]): List[Case] = {
    val mappedCases = discriminator match {
      case Discriminator.StringDsc(_, mapping) =>
        mapping.map(decoderCaseForString.tupled).toList
      case Discriminator.IntDsc(_, mapping) =>
        mapping.map(decoderCaseForInt.tupled).toList
      case Discriminator.EnumDsc(_, enum, mapping) =>
        mapping.map(decoderCaseForEnum(enum).tupled).toList
    }
    mappedCases :+ decoderOtherwiseCase()
  }

  private def decoderCaseForString(discValue: String, child: CoproductChild) = {
    val discDecoderType = child.typeName
    p"case $discValue => Decoder[$discDecoderType].apply(c)"
  }

  private def decoderCaseForInt(discValue: Int, child: CoproductChild) = {
    val discDecoderType = child.typeName
    p"case $discValue => Decoder[$discDecoderType].apply(c)"
  }

  private def decoderCaseForEnum(
      enum: Enum
  )(discValue: EnumValue, child: CoproductChild) = {
    val discDecoderType = child.typeName
    val evPatVar = p"${Term.Name(enum.name)}.${discValue.simpleName}"
    p"case $evPatVar => Decoder[$discDecoderType].apply(c)"
  }

  private def decoderOtherwiseCase() = {
    val otherTerm = Term.Name("other")
    val otherTermBind = Pat.Var(otherTerm)
    p"""case $otherTermBind => Left(DecodingFailure("Unexpected value for coproduct:" + $otherTerm, Nil))"""
  }
}

case class Coproduct(
    name: String,
    discriminator: Option[Discriminator[_]]
) {

  def uncapitalizedName: String = name.take(1).toLowerCase() + name.drop(1)
}

case class CoproductChild(name: String) {
  def toVar: Term.Name =
    Term.Name(name.take(1).toLowerCase() + name.drop(1))
  def toFqnType(coproduct: Coproduct): Type =
    t"${Term.Name(coproduct.name)}.${Type.Name(name)}"
  def typeName: Type.Name = Type.Name(name)
}

sealed trait Discriminator[T] {
  def fieldName: String
  def mapping: Map[T, CoproductChild]
}
object Discriminator {
  case class StringDsc(
      fieldName: String,
      mapping: Map[String, CoproductChild]
  ) extends Discriminator[String]
  case class IntDsc(
      fieldName: String,
      mapping: Map[Int, CoproductChild]
  ) extends Discriminator[Int]
  case class EnumDsc(
      fieldName: String,
      enum: Enum,
      mapping: Map[EnumValue, CoproductChild]
  ) extends Discriminator[EnumValue]
}

case class EncoderCase(
    when: Pat,
    child: CoproductChild
)

case class DecoderCase(
    when: Pat,
    discDecoderType: Type,
    discValue: Term,
    child: CoproductChild
)
