/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 *  http://www.apache.org/licenses/LICENSE-2.0                  *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/
package org.apache.james.jmap.routes

import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.Callable
import java.util.function.Consumer
import java.util.stream
import java.util.stream.Stream

import com.google.common.base.CharMatcher
import eu.timepit.refined.numeric.NonNegative
import eu.timepit.refined.refineV
import io.netty.buffer.Unpooled
import io.netty.handler.codec.http.HttpHeaderNames.{CONTENT_LENGTH, CONTENT_TYPE}
import io.netty.handler.codec.http.HttpResponseStatus._
import io.netty.handler.codec.http.{HttpHeaderNames, HttpHeaderValidationUtil, HttpMethod, QueryStringDecoder}
import jakarta.inject.{Inject, Named}
import org.apache.commons.io.output.UnsynchronizedByteArrayOutputStream
import org.apache.james.jmap.HttpConstants.JSON_CONTENT_TYPE
import org.apache.james.jmap.api.model.Size.{Size, sanitizeSize}
import org.apache.james.jmap.api.model.{Upload, UploadId, UploadNotFoundException}
import org.apache.james.jmap.api.upload.UploadService
import org.apache.james.jmap.core.Id.Id
import org.apache.james.jmap.core.{AccountId, Id, ProblemDetails, SessionTranslator}
import org.apache.james.jmap.exceptions.{UnauthorizedException, UserNotFoundException}
import org.apache.james.jmap.http.Authenticator
import org.apache.james.jmap.http.rfc8621.InjectionKeys
import org.apache.james.jmap.json.ResponseSerializer
import org.apache.james.jmap.mail.{BlobId, MinimalEmailBodyPart}
import org.apache.james.jmap.method.{AccountNotFoundException, ZoneIdProvider}
import org.apache.james.jmap.routes.DownloadRoutes.{BUFFER_SIZE, LOGGER}
import org.apache.james.jmap.{Endpoint, JMAPRoute, JMAPRoutes}
import org.apache.james.mailbox.exception.AttachmentNotFoundException
import org.apache.james.mailbox.model.ContentType.{MediaType, MimeType, SubType}
import org.apache.james.mailbox.model._
import org.apache.james.mailbox.{AttachmentIdFactory, AttachmentManager, MailboxSession, MessageIdManager}
import org.apache.james.metrics.api.{Metric, MetricFactory}
import org.apache.james.mime4j.codec.EncoderUtil
import org.apache.james.mime4j.codec.EncoderUtil.Usage
import org.apache.james.mime4j.dom.SingleBody
import org.apache.james.mime4j.message.DefaultMessageWriter
import org.apache.james.util.ReactorUtils
import org.reactivestreams.Publisher
import org.slf4j.{Logger, LoggerFactory}
import play.api.libs.json.Json
import reactor.core.publisher.Mono
import reactor.core.scala.publisher.{SFlux, SMono}
import reactor.core.scheduler.Schedulers
import reactor.netty.http.server.{HttpServerRequest, HttpServerResponse}

import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}

object DownloadRoutes {
  val LOGGER: Logger = LoggerFactory.getLogger(classOf[DownloadRoutes])

  val BUFFER_SIZE: Int = 16 * 1024
}

sealed trait BlobResolutionResult {
  def asOption: Option[SMono[Blob]]
}
case object NonApplicable extends BlobResolutionResult {
  override def asOption: Option[SMono[Blob]] = None
}
case class Applicable(blob: SMono[Blob]) extends BlobResolutionResult {
  override def asOption: Option[SMono[Blob]] = Some(blob)
}

trait BlobResolver {
  def resolve(blobId: BlobId, mailboxSession: MailboxSession): Publisher[BlobResolutionResult]
}

trait Blob {
  def blobId: BlobId
  def contentType: ContentType
  def size: Try[Size]
  def content: InputStream
}

case class BlobNotFoundException(blobId: BlobId) extends RuntimeException
case class ForbiddenException() extends RuntimeException

case class MessageBlob(blobId: BlobId, message: MessageResult) extends Blob {
  override def contentType: ContentType = ContentType.of(MimeType.of(MediaType.of("message"), SubType.of("rfc822")))

  override def size: Try[Size] = refineV[NonNegative](message.getSize) match {
    case Left(e) => Failure(new IllegalArgumentException(e))
    case Right(size) => Success(size)
  }

  override def content: InputStream = message.getFullContent.getInputStream
}

case class UploadedBlob(blobId: BlobId, upload: Upload) extends Blob {
  override def contentType: ContentType = upload.contentType

  override def size: Try[Size] = Success(upload.size)

  override def content: InputStream = upload.content()
}

case class AttachmentBlob(attachmentMetadata: AttachmentMetadata, fileContent: InputStream) extends Blob {
  override def size: Try[Size] = Success(sanitizeSize(attachmentMetadata.getSize))

  override def contentType: ContentType = attachmentMetadata.getType

  override def content: InputStream = fileContent

  override def blobId: BlobId = BlobId.of(attachmentMetadata.getAttachmentId.getId).get
}

case class EmailBodyPartBlob(blobId: BlobId, part: MinimalEmailBodyPart) extends Blob {
  override def size: Try[Size] = part.size

  override def contentType: ContentType = ContentType.of(part.`type`.value)

  override def content: InputStream = part.entity.getBody match {
    case body: SingleBody => body.getInputStream
    case body =>
      val writer = new DefaultMessageWriter
      val outputStream = new UnsynchronizedByteArrayOutputStream()
      writer.writeBody(body, outputStream)
      outputStream.toInputStream
  }
}

class MessageBlobResolver @Inject()(val messageIdFactory: MessageId.Factory,
                                    val messageIdManager: MessageIdManager) extends BlobResolver {
  override def resolve(blobId: BlobId, mailboxSession: MailboxSession): SMono[BlobResolutionResult] = {
    Try(messageIdFactory.fromString(blobId.value.value)) match {
      case Failure(_) => SMono.just(NonApplicable)
      case Success(messageId) => SMono.fromPublisher(messageIdManager.getMessagesReactive(List(messageId).asJava, FetchGroup.FULL_CONTENT, mailboxSession))
        .map(message => Applicable(SMono.just(MessageBlob(blobId, message))))
        .switchIfEmpty(SMono.just(NonApplicable))
    }
  }
}

class UploadResolver @Inject()(val uploadService: UploadService) extends BlobResolver {
  private val prefix = "uploads-"

  override def resolve(blobId: BlobId, mailboxSession: MailboxSession): SMono[BlobResolutionResult] = {
    if (!blobId.value.value.startsWith(prefix)) {
      SMono.just(NonApplicable)
    } else {
      val uploadIdAsString = blobId.value.value.substring(prefix.length)
      Try(UploadId.from(uploadIdAsString)) match {
        case Failure(_) => SMono.just(NonApplicable)
        case Success(uploadId) => SMono.fromPublisher(uploadService.retrieve(uploadId, mailboxSession.getUser))
            .map(upload => Applicable(SMono.just(UploadedBlob(blobId, upload))))
            .onErrorResume {
              case _: UploadNotFoundException => SMono.just(NonApplicable)
              case e => SMono.error[BlobResolutionResult](e)
            }
      }
    }
  }
}

class AttachmentBlobResolver @Inject()(val attachmentManager: AttachmentManager, val attachmentIdFactory: AttachmentIdFactory) extends BlobResolver {
  override def resolve(blobId: BlobId, mailboxSession: MailboxSession): SMono[BlobResolutionResult] =
    Try(attachmentIdFactory.from(blobId.value.value)) match {
      case Success(attachmentId) =>
        SMono(attachmentManager.getAttachmentReactive(attachmentId, mailboxSession))
          .map(attachmentMetadata => Applicable(SMono(attachmentManager.loadReactive(attachmentMetadata, mailboxSession))
            .map(content => AttachmentBlob(attachmentMetadata, content))))
          .onErrorResume {
            case e: AttachmentNotFoundException =>  SMono.just(NonApplicable.asInstanceOf[BlobResolutionResult])
            case e => SMono.error[BlobResolutionResult](e)
          }
      case _ => SMono.just(NonApplicable)
    }
}

class MessagePartBlobResolver @Inject()(val messageIdFactory: MessageId.Factory,
                                        val messageIdManager: MessageIdManager,
                                        val zoneIdSupplier: ZoneIdProvider) extends BlobResolver {
  private def asMessageAndPartIds(blobId: BlobId): Try[(MessageId, List[BlobId])] = {
    blobId.value.value.split('_').toList match {
      case messageIdString :: tail if tail.nonEmpty => for {
        messageId <- Try(messageIdFactory.fromString(messageIdString))
      } yield {
        (messageId, partsToListOfBlobIds(messageIdString, tail))
      }
      case _ => Failure(BlobNotFoundException(blobId))
    }
  }

  private def partsToListOfBlobIds(messageIdString: String, parts: List[String]): List[BlobId] = parts.foldLeft[List[String]](List(messageIdString)) {
    case (acc, idPart) => acc.headOption.map(prefix => prefix + "_" + idPart).getOrElse(idPart) :: acc
  }.flatMap(s => BlobId.of(s).toOption).take(parts.size).reverse

  override def resolve(blobId: BlobId, mailboxSession: MailboxSession): SMono[BlobResolutionResult] = {
    asMessageAndPartIds(blobId) match {
      case Failure(_) => SMono.just(NonApplicable)
      case Success((messageId, blobIds)) =>
        SMono.fromPublisher(
          messageIdManager.getMessagesReactive(List(messageId).asJava, FetchGroup.FULL_CONTENT, mailboxSession))
          .handle[MinimalEmailBodyPart] {
            case (message, sink) => MinimalEmailBodyPart.ofMessage(None, zoneIdSupplier.get(), BlobId.of(messageId).get, message)
              .fold(sink.error, sink.next)
          }
          .handle[MinimalEmailBodyPart] {
            case (bodyStructure, sink) =>
              blobIds.foldLeft[Option[MinimalEmailBodyPart]](Some(bodyStructure)) {
                case (None, _) => None
                case (Some(nestedBodyStructure), blobId) => nestedBodyStructure.partWithBlobId(blobId)
                    .orElse(nestedBodyStructure.nested(zoneIdSupplier.get()).flatMap(_.partWithBlobId(blobId)))
              }
                .fold(sink.error(BlobNotFoundException(blobId)))(part => sink.next(part))
          }
          .map(blob => Applicable(SMono.just(EmailBodyPartBlob(blobId, blob))))
          .switchIfEmpty(SMono.just(NonApplicable))
    }
  }
}

class BlobResolvers(blobResolvers: Set[BlobResolver]) {

  @Inject
  def this(blobResolvers: java.util.Set[BlobResolver]) = {
    this(blobResolvers.asScala.toSet)
  }

  def resolve(blobId: BlobId, mailboxSession: MailboxSession): SMono[Blob] =
    SFlux.fromIterable(blobResolvers)
      .concatMap(resolver => resolver.resolve(blobId, mailboxSession))
      .filter {
        case NonApplicable => false
        case _: Applicable => true
      }
      .concatMap(result => result.asOption.getOrElse(SMono.error(BlobNotFoundException(blobId))))
      .next().switchIfEmpty(SMono.error(BlobNotFoundException(blobId)))
}

class DownloadRoutes @Inject()(@Named(InjectionKeys.RFC_8621) val authenticator: Authenticator,
                               val blobResolvers: BlobResolvers,
                               val sessionTranslator: SessionTranslator,
                               val metricFactory: MetricFactory) extends JMAPRoutes {

  private val accountIdParam: String = "accountId"
  private val blobIdParam: String = "blobId"
  private val nameParam: String = "name"
  private val contentTypeParam: String = "type"
  private val downloadUri = s"/jmap/download/{$accountIdParam}/{$blobIdParam}"
  private val pendingDownloadMetric: Metric = metricFactory.generate("jmap_pending_downloads")

  override def routes(): stream.Stream[JMAPRoute] = Stream.of(
    JMAPRoute.builder
      .endpoint(new Endpoint(HttpMethod.GET, downloadUri))
      .action(this.get)
      .corsHeaders,
    JMAPRoute.builder
      .endpoint(new Endpoint(HttpMethod.OPTIONS, downloadUri))
      .action(JMAPRoutes.CORS_CONTROL)
      .noCorsHeaders)

  private def get(request: HttpServerRequest, response: HttpServerResponse): Mono[Void] =
    SMono(authenticator.authenticate(request))
      .flatMap(mailboxSession => getIfOwner(request, response, mailboxSession))
      .onErrorResume {
        case _: ForbiddenException | _: AccountNotFoundException =>
          respondDetails(response, ProblemDetails(status = FORBIDDEN, detail = "You cannot download in others accounts"))
        case e: UserNotFoundException =>
          LOGGER.warn("User not found", e)
          respondDetails(e.addHeaders(response), ProblemDetails(status = NOT_FOUND, detail = e.getMessage))
        case e: UnauthorizedException =>
          LOGGER.warn("Unauthorized", e)
          respondDetails(e.addHeaders(response), ProblemDetails(status = UNAUTHORIZED, detail = e.getMessage))
        case _: BlobNotFoundException =>
          respondDetails(response, ProblemDetails(status = NOT_FOUND, detail = "The resource could not be found"))
        case e =>
          LOGGER.error("Unexpected error upon download {}", request.uri(), e)
          respondDetails(response, ProblemDetails(status = INTERNAL_SERVER_ERROR, detail = e.getMessage))
      }
      .subscribeOn(ReactorUtils.BLOCKING_CALL_WRAPPER)
      .asJava()
      .`then`

  private def get(request: HttpServerRequest, response: HttpServerResponse, mailboxSession: MailboxSession): SMono[Unit] =
    BlobId.of(request.param(blobIdParam))
      .fold(e => SMono.error(e),
        blobResolvers.resolve(_, mailboxSession)
          .doOnSubscribe(_ => pendingDownloadMetric.increment()))
      .flatMap(blob => downloadBlob(
        optionalName = queryParam(request, nameParam),
        response = response,
        blobContentType = queryParam(request, contentTypeParam)
          .map(ContentType.of)
          .getOrElse(blob.contentType),
        blob = blob)
        .`then`())
      .doOnSuccess(_ => pendingDownloadMetric.decrement())

  private def getIfOwner(request: HttpServerRequest, response: HttpServerResponse, mailboxSession: MailboxSession): SMono[Unit] =
    Id.validate(request.param(accountIdParam)) match {
      case Right(id: Id) => sessionTranslator.delegateIfNeeded(mailboxSession, AccountId(id))
          .flatMap(session => get(request, response, session))
      case Left(throwable: Throwable) => SMono.error(throwable)
    }

  private def downloadBlob(optionalName: Option[String],
                           response: HttpServerResponse,
                           blobContentType: ContentType,
                           blob: Blob): SMono[Unit] = {
    val resourceSupplier: Callable[InputStream] = () => blob.content
    val sourceSupplier: java.util.function.Function[InputStream, Mono[Void]] = stream => SMono(addContentDispositionHeader(optionalName)
      .compose(addContentLengthHeader(blob.size))
      .compose(addCacheControlHeader())
      .apply(response)
      .header(CONTENT_TYPE, sanitizeHeaderValue(blobContentType.asString))
      .status(OK)
      .send(ReactorUtils.toChunks(stream, BUFFER_SIZE)
        .map(Unpooled.wrappedBuffer(_))
        .subscribeOn(Schedulers.boundedElastic()))).asJava()
    val resourceRelease: Consumer[InputStream] = (stream: InputStream) => stream.close()

    SMono.fromPublisher(Mono.using(
        resourceSupplier,
        sourceSupplier,
        resourceRelease))
      .`then`
  }

  private def addContentDispositionHeader(optionalName: Option[String]): HttpServerResponse => HttpServerResponse =
    resp => optionalName.map(addContentDispositionHeaderRegardingEncoding(_, resp))
      .getOrElse(resp)

  private def sanitizeHeaderValue(s: String): String =
    if (HttpHeaderValidationUtil.validateValidHeaderValue(s) == -1) {
      s
    } else {
      "application/octet-stream"
    }

  private def addContentLengthHeader(sizeTry: Try[Size]): HttpServerResponse => HttpServerResponse =
    resp => sizeTry
      .map(size => resp.header("Content-Length", size.value.toString))
      .getOrElse(resp)

  private def addCacheControlHeader(): HttpServerResponse => HttpServerResponse =
    resp => resp.header(HttpHeaderNames.CACHE_CONTROL, "private, immutable, max-age=31536000")

  private def addContentDispositionHeaderRegardingEncoding(name: String, resp: HttpServerResponse): HttpServerResponse =
    if (CharMatcher.ascii.matchesAllOf(name)) {
      Try(resp.header("Content-Disposition", "attachment; filename=\"" + name + "\""))
        // Can fail if the file name contains valid ascii character that are invalid in a contentDisposition header
        .getOrElse(resp.header("Content-Disposition", encodedFileName(name)))
    } else {
      resp.header("Content-Disposition", encodedFileName(name))
    }

  private def encodedFileName(name: String) = "attachment; filename*=\"" + EncoderUtil.encodeEncodedWord(name, Usage.TEXT_TOKEN) + "\""

  private def queryParam(httpRequest: HttpServerRequest, parameterName: String): Option[String] =
    queryParam(parameterName, httpRequest.uri)

  private def queryParam(parameterName: String, uri: String): Option[String] =
    Option(new QueryStringDecoder(uri).parameters.get(parameterName))
      .toList
      .flatMap(_.asScala)
      .headOption

  private def respondDetails(httpServerResponse: HttpServerResponse, details: ProblemDetails): SMono[Unit] =
    SMono.fromCallable(() => ResponseSerializer.serialize(details))
      .map(Json.stringify)
      .map(_.getBytes(StandardCharsets.UTF_8))
      .flatMap(bytes =>
        SMono.fromPublisher(httpServerResponse.status(details.status)
          .header(CONTENT_TYPE, JSON_CONTENT_TYPE)
          .header(CONTENT_LENGTH, Integer.toString(bytes.length))
          .sendByteArray(SMono.just(bytes))
          .`then`).`then`)
}
