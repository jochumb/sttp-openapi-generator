package io.github.ghostbuster91.sttp.client3.example

import _root_.sttp.client3._
import _root_.sttp.model._
import _root_.io.circe.Decoder
import _root_.io.circe.Encoder
import _root_.io.circe.generic.AutoDerivation
import _root_.sttp.client3.circe.SttpCirceApi

trait CirceCodecs extends AutoDerivation with SttpCirceApi

sealed trait Entity
case class ErrorModel(msg: String) extends Entity()
case class ErrorModel2(msg: String) extends Entity()

class DefaultApi(baseUrl: String) extends CirceCodecs {
  def updatePerson(): Request[
    Either[ResponseException[Entity, io.circe.Error], Unit],
    Any
  ] = basicRequest
    .put(uri"$baseUrl/person")
    .response(
      fromMetadata(
        asJsonEither[Entity, Unit],
        ConditionalResponseAs(_.code == 400, asJsonEither[ErrorModel, Unit]),
        ConditionalResponseAs(_.code == 401, asJsonEither[ErrorModel2, Unit])
      )
    )
}