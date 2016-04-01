package com.github.dmexe.finagle.consul.client

import java.util.Base64

import com.fasterxml.jackson.annotation.JsonProperty
import com.github.dmexe.finagle.consul.client.HttpErrors.KeyNotFoundError
import com.github.dmexe.finagle.consul.common.Json
import com.twitter.finagle.{http, Service => HttpService}
import com.twitter.util.Future

class KeyService(val client: HttpService[http.Request, http.Response]) extends HttpRequests with HttpResponses {

  import KeyService._

  def get(path: String): Future[Option[Key]] = {
    val key = keyName(path)
    val res = httpGet(key) flatMap okResponse(200, key) flatMap getKeys map (_.headOption)
    res rescue {
      case e: KeyNotFoundError => Future.value(None)
    }
  }

  def getAll[T](path: String): Future[Seq[Key]] = {
    val key = keyName(path, recurse = true)
    val res = httpGet(key) flatMap okResponse(200, key) flatMap getKeys
    res rescue {
      case e: KeyNotFoundError => Future.value(Seq.empty)
    }
  }

  def put(path: String, body: String): Future[Option[Key]] = {
    val key = keyName(path)
    httpPut(key, body) flatMap okResponse(200, key) flatMap { _ => get(path) }
  }

  def acquire(path: String, sessionId: String, body: String): Future[Boolean] = {
    val key = keyName(path, acquire = Some(sessionId))
    httpPut(key, body) flatMap okResponse(200, key) flatMap getBoolean
  }

  def release(path: String, session: String): Future[Boolean] = {
    val key = keyName(path, release = Some(session))
    httpPut(key) flatMap okResponse(200, key) flatMap getBoolean
  }

  def delete(path: String): Future[Unit] = {
    val key = keyName(path)
    httpDelete(key) flatMap okResponse(200, key) map { _ => () }
  }

  private def keyName(path: String, recurse: Boolean = false, acquire: Option[String] = None, release: Option[String] = None): String = {
    val key  = s"/v1/kv/$path"
    var opts = List.empty[String]

    if (recurse) {
      opts = opts ++ List("recurse")
    }

    acquire match {
      case Some(id) =>
        opts = opts ++ List(s"acquire=$id")
      case None =>
    }

    release match {
      case Some(id) =>
        opts = opts ++ List(s"release=$id")
      case None =>
    }

    if(opts.isEmpty) {
      key
    } else {
      s"$key?${opts.mkString("&")}"
    }
  }

  private def decodeAndCopyKeyValue(key: Key): Key = {
    key.value match {
      case Some(value) =>
        val newValue = new String(Base64.getDecoder.decode(value), "UTF-8")
        key.copy(value = Some(newValue))
      case None => key
    }
  }

  private def getKeys(resp: http.Response): Future[Seq[Key]] = {
    Json.decode[Seq[Key]](resp.contentString) map { keys =>
      keys.map(decodeAndCopyKeyValue)
    }
  }

  private def getBoolean(resp: http.Response): Future[Boolean] = {
    Json.decode[Boolean](resp.contentString)
  }
}

object KeyService {

  final case class Key(
    @JsonProperty("Session")
    session:     Option[String],
    @JsonProperty("CreateIndex")
    createIndex: Int,
    @JsonProperty("ModifyIndex")
    modifyIndex: Int,
    @JsonProperty("LockIndex")
    lockIndex:   Int,
    @JsonProperty("Key")
    key:         String,
    @JsonProperty("Flags")
    flags:       Int,
    @JsonProperty("Value")
    value:       Option[String]
  )

  def apply(client: HttpService[http.Request, http.Response]) = new KeyService(client)
}