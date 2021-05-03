# sttp-openapi-generator
[<img alt="GitHub Workflow" src="https://img.shields.io/github/workflow/status/ghostbuster91/sttp-openapi-generator/CI/master?style=for-the-badge" height="24">](https://github.com/ghostbuster91/sttp-openapi-generator/actions)

_This project is in a very early stage, use it at your own risk!_

## Why?

Why creating another openapi-generator when there is an official one? While the mentioned generator is generally a great project and serves well for many people its scala part has a few flaws in my opinion. There is no proper encoding for discriminators, neither support for other json libraries. The genereted code doesn't feel like native. These, as well as the other things, could (and probably will at some point) be implemented, but the size of the project and underlying templating engine(mustache) don't make it easier. Last but not least it is currently impossible to generate openapi code into src-managed directory (https://github.com/OpenAPITools/openapi-generator/issues/6685). I think that, by extracting and focusing only on a scala related part, it can be done better.

## Goals of the project

- generate code which feels like native
- support popular json libararies from scala ecosystem
- support only sttp but do it well
- [proper integration with sbt and other build tools](#sbt-plugin)
- [support discriminators](#discriminators)
- [support error encoding](#error-encoding)
- [support open products](#open-product)

## Teaser

Given following yaml:

```yaml
openapi: 3.0.3
info:
  title: Entities
  version: "1.0"
paths:
  /:
    get:
      operationId: getRoot
      responses:
        "200":
          description: ""
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/Person"
components:
  schemas:
    Person:
      required:
        - name
        - age
      type: object
      properties:
        name:
          type: string
        age:
          type: integer
          minimum: 11
```

it will be turned into:

```scala
trait CirceCodecs extends AutoDerivation with SttpCirceApi

case class Person(name: String, age: Int)

class DefaultApi(baseUrl: String) extends CirceCodecs {
  def getRoot(): Request[Person, Any] = basicRequest
    .get(uri"$baseUrl")
    .response(
      fromMetadata(
        asJson[Person].getRight,
        ConditionalResponseAs(
          _.code == StatusCode.unsafeApply(200),
          asJson[Person].getRight
        )
      )
    )
}

```

## sbt-plugin

Currently, there is only an integration for sbt, but I hope to add support for mill in the nearest future.
In order to use this project, follow the usual convention, first add it to `project/plugins.sbt`:
```scala
addSbtPlugin("io.github.ghostbuster91.sttp3-openapi3" % "sbt-codegen-plugin" % "<latest-version>")
```

next, enable it for the desired modules in `build.sbt`:
```scala
import SttpOpenApiCodegenPlugin._
enablePlugins(SttpOpenApiCodegenPlugin)
```

Generator will walk through all files in input directory and generate for each one respective code into output directory.
Package name based on directory structure will be preserved.

Code generation can be configured by one of the following options:

`sttpOpenApiOutputPath` - Directory for sources generated by sttp-openapi generator (default: `target/scala-2.12/src_managed/`)
`sttpOpenApiInputPath` - Input resources for sttp-openapi generator (default: `./resources`)
`sttpOpenApiJsonLibrary` - Json library for sttp-openapi generator to use (currently only `Circe`)
`sttpOpenApiHandleErrors` - If true the generator will include error information in types (default: `true`)

## discriminators

In the openapi specification there is a notion of [discriminators](https://swagger.io/docs/specification/data-models/inheritance-and-polymorphism/). 
These objects are used to distinguishing between polymorphic instances of some type based on a given value.

This project takes advantage of them and generates json configs accordingly.

```yaml
components:
  schemas:
    Entity:
      oneOf:
        - $ref: "#/components/schemas/Person"
        - $ref: "#/components/schemas/Organization"
      discriminator:
        propertyName: name
        mapping:
          john: "#/components/schemas/Person"
          sml: "#/components/schemas/Organization"
    Person:
      required:
        - name
        - age
      type: object
      properties:
        name:
          type: string
        age:
          type: integer
    Organization:
      required:
        - name
      type: object
      properties:
        name:
          type: string
```

```scala
sealed trait Entity { def name: String }
case class Organization(name: String) extends Entity()
case class Person(name: String, age: Int) extends Entity()

trait CirceCodecs extends AutoDerivation with SttpCirceApi {
  implicit val entityDecoder: Decoder[Entity] = new Decoder[Entity]() {
    override def apply(c: HCursor): Result[Entity] = c
      .downField("name")
      .as[String]
      .flatMap({
        case "john" => Decoder[Person].apply(c)
        case "sml"  => Decoder[Organization].apply(c)
        case other =>
          Left(DecodingFailure("Unexpected value for coproduct:" + other, Nil))
      })
  }
  implicit val entityEncoder: Encoder[Entity] = new Encoder[Entity]() {
    override def apply(entity: Entity): Json = entity match {
      case person: Person => Encoder[Person].apply(person)
      case organization: Organization =>
        Encoder[Organization].apply(organization)
    }
  }
}
```

## error encoding

In openapi error responses can be represented equally easily as success ones.
That is also the case for the sttp.client. 
If you are not a fun of error handling, you can disable that feature in generator settings.

```yaml
openapi: 3.0.2
info:
  title: Entities
  version: "1.0"
paths:
  /person:
    put:
      summary: Update an existing person
      description: Update an existing person by Id
      operationId: updatePerson
      responses:
        "400":
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/ErrorModel"
        "401":
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/ErrorModel2"
components:
  schemas:
    ErrorModel:
      required:
        - msg
      type: object
      properties:
        msg:
          type: string
    ErrorModel2:
      required:
        - msg
      type: object
      properties:
        msg:
          type: string
```

```scala
sealed trait UpdatePersonGenericError
case class ErrorModel(msg: String) extends UpdatePersonGenericError()
case class ErrorModel2(msg: String) extends UpdatePersonGenericError()

class DefaultApi(baseUrl: String) extends CirceCodecs {
  def updatePerson(): Request[
    Either[ResponseException[UpdatePersonGenericError, CirceError], Unit],
    Any
  ] = basicRequest
    .put(uri"$baseUrl/person")
    .response(
      fromMetadata(
        asJsonEither[UpdatePersonGenericError, Unit],
        ConditionalResponseAs(
          _.code == StatusCode.unsafeApply(400),
          asJsonEither[ErrorModel, Unit]
        ),
        ConditionalResponseAs(
          _.code == StatusCode.unsafeApply(401),
          asJsonEither[ErrorModel2, Unit]
        )
      )
    )
}
```

## open-product

In openapi specifications data models can be extended by [arbitrary properties if needed](https://swagger.io/docs/specification/data-models/dictionaries/). 
To do that one has to specify `additionalProperties` on particular model. At the same time on the call site special codecs need to be provided to support such types.
Luckily, sttp-openapi-generator will handle that as well.

```yaml
openapi: 3.0.3
info:
  title: Entities
  version: "1.0"
paths:
  /:
    get:
      operationId: getRoot
      responses:
        "200":
          description: ""
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/Person"
components:
  schemas:
    Person:
      required:
        - name
        - age
      type: object
      properties:
        name:
          type: string
        age:
          type: integer
      additionalProperties: true
```

```scala
trait CirceCodecs extends AutoDerivation with SttpCirceApi {
  implicit val personDecoder: Decoder[Person] = new Decoder[Person]() {
    override def apply(c: HCursor): Result[Person] =
      for {
        name <- c.downField("name").as[String]
        age <- c.downField("age").as[Int]
        additionalProperties <- c.as[Map[String, Json]]
      } yield Person(
        name,
        age,
        additionalProperties.filterKeys(_ != "name").filterKeys(_ != "age")
      )
  }
  implicit val personEncoder: Encoder[Person] = new Encoder[Person]() {
    override def apply(person: Person): Json = Encoder
      .forProduct2[Person, String, Int]("name", "age")(p => (p.name, p.age))
      .apply(person)
      .deepMerge(
        Encoder[Map[String, Json]].apply(person._additionalProperties)
      )
  }

}

case class Person(
    name: String,
    age: Int,
    _additionalProperties: Map[String, Json]
)
```