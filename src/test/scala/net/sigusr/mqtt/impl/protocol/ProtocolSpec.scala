/*
 * Copyright 2014 Frédéric Cabestre
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.sigusr.mqtt.impl.protocol

import net.sigusr.mqtt.SpecUtils._
import net.sigusr.mqtt.api._
import net.sigusr.mqtt.impl.frames._
import org.specs2.mutable.Specification
import org.specs2.time.NoTimeConversions
import scodec.bits.ByteVector

import scala.util.Random

object ProtocolSpec extends Specification with Protocol with NoTimeConversions {

  "The transportNotReady() function" should {
    "Define the action to perform when the transport is not ready" in {
      transportNotReady() shouldEqual SendToClient(MQTTNotReady)
    }
  }

  "The transportReady() function" should {
    "Define the action to perform when the transport is ready" in {
      transportReady() shouldEqual SendToClient(MQTTReady)
    }
  }

  "The connectionClosed() function" should {
    "Define the action to perform when the connection is closed" in {
      connectionClosed() shouldEqual SendToClient(MQTTDisconnected)
    }
  }

  "The handleApiMessages() function" should {
    "Define the action to perform to handle a MQTTConnect API message" in {
      val clientId = "client id"
      val keepAlive = 60
      val cleanSession = false
      val topic = Some("topic")
      val message = Some("message")
      val user = Some("user")
      val password = Some("password")
      val input = MQTTConnect(clientId, keepAlive, cleanSession, topic, message, user, password)
      val header = Header(dup = false, AtMostOnce, retain = false)
      val variableHeader = ConnectVariableHeader(user.isDefined, password.isDefined, willRetain = false, AtLeastOnce, willFlag = false, cleanSession, keepAlive)
      val result =Sequence(Seq(
        SetKeepAliveValue(keepAlive * 1000),
        SendToNetwork(ConnectFrame(header, variableHeader, clientId, topic, message, user, password))))
      handleApiMessages(input) should_== result
    }

    "Define the action to perform to handle a MQTTDisconnect API message" in {
      val input = MQTTDisconnect
      val header = Header(dup = false, AtMostOnce, retain = false)
      SendToNetwork(DisconnectFrame(header))
      val result = SendToNetwork(DisconnectFrame(header))
      handleApiMessages(input) should_== result
    }

    "Define the action to perform to handle a MQTTSubscribe API message" in {
      val topics = Vector(("topic0", AtMostOnce), ("topic1", ExactlyOnce), ("topic2", AtLeastOnce))
      val messageId = Random.nextInt(65535)
      val input = MQTTSubscribe(topics, messageId)
      val header = Header(dup = false, AtLeastOnce, retain = false)
      val result = SendToNetwork(SubscribeFrame(header, MessageIdentifier(messageId), topics))
      handleApiMessages(input) should_== result
    }

    "Define the action to perform to handle a MQTTPublish API message with QoS of 'At most once'" in {
      val topic = "topic0"
      val qos = AtMostOnce
      val retain = true
      val payload = makeRandomByteVector(48)
      val messageId = Random.nextInt(65535)
      val input = MQTTPublish(topic, payload, qos, Some(messageId), retain, retain = true)
      val header = Header(dup = true, qos, retain)
      val result = SendToNetwork(PublishFrame(header, topic, MessageIdentifier(messageId), ByteVector(payload)))
      handleApiMessages(input) should_== result
    }

    "Define the action to perform to handle a MQTTPublish API message with QoS of 'at least once' or 'exactly once'" in {
      val topic = "topic0"
      val qos = AtLeastOnce
      val retain = true
      val payload = makeRandomByteVector(32)
      val messageId = Random.nextInt(65535)
      val input = MQTTPublish(topic, payload, qos, Some(messageId), retain)
      val header = Header(dup = false, qos, retain)
      val result = SendToNetwork(PublishFrame(header, topic, MessageIdentifier(messageId), ByteVector(payload)))
      handleApiMessages(input) should_== result
    }

    "Define the action to perform to handle an API message that should not be sent by the user" in {
      val input = MQTTReady
      val result = SendToClient(MQTTWrongClientMessage(MQTTReady))
      handleApiMessages(input) should_== result
    }
  }

  "The timerSignal() function" should {
    "Define the action to perform to handle a SendKeepAlive internal API message while not waiting for a ping response and messages were recently sent" in {
      val result = StartTimer(29500)
      timerSignal(120000500, 30000, 120000000, isPingResponsePending = false) should_== result
    }

    "Define the action to perform to handle a SendKeepAlive internal API message while not waiting for a ping response but no messages were recently sent" in {
      val result = Sequence(Seq(
        SetPendingPingResponse(isPending = true),
        StartTimer(30000),
        SendToNetwork(PingReqFrame(Header(dup = false, AtMostOnce, retain = false)))))
      timerSignal(120029001, 30000, 120000000, isPingResponsePending = false) should_== result
    }

    "Define the action to perform to handle a SendKeepAlive internal API message while waiting for a ping response" in {
      val result = CloseTransport
      timerSignal(120029999, 30000, 120000000, isPingResponsePending = true) should_== result
    }
  }

  "The handleNetworkFrames() function" should {

    "Provide no actions when the frame should not be handled" in {
      val header = Header(dup = false, AtLeastOnce, retain = false)
      val input = PingReqFrame(header)
      val result = Sequence()
      handleNetworkFrames(input, 30000) should_== result
    }

    "Define the actions to perform to handle a ConnackFrame" in {
      val header = Header(dup = false, AtLeastOnce, retain = false)
      val connackVariableHeader = ConnackVariableHeader(ConnectionRefused4)
      val input = ConnackFrame(header, connackVariableHeader)
      val result = Sequence(Seq(StartTimer(30000), SendToClient(MQTTConnected)))
      handleNetworkFrames(input, 30000) should_== result
    }

    "Define the actions to perform to handle a PingRespFrame" in {
      val header = Header(dup = false, AtLeastOnce, retain = false)
      val input = PingRespFrame(header)
      val result = SetPendingPingResponse(isPending = false)
      handleNetworkFrames(input, 30000) should_== result
    }

    "Define the actions to perform to handle a PublishFrame" in {
      val header = Header(dup = false, AtLeastOnce, retain = false)
      val topic = "topic"
      val messageId = Random.nextInt(65534) + 1
      val messageIdentifier = MessageIdentifier(messageId)
      val payload = makeRandomByteVector(64)
      val input = PublishFrame(header, topic, messageIdentifier, ByteVector(payload))
      val result = SendToClient(MQTTMessage(topic, payload))
      handleNetworkFrames(input, 30000) should_== result
    }

    "Define the actions to perform to handle a PubackFrame" in {
      val header = Header(dup = false, AtMostOnce, retain = false)
      val messageId = Random.nextInt(65534) + 1
      val messageIdentifier = MessageIdentifier(messageId)
      val input = PubackFrame(header, messageIdentifier)
      val result = SendToClient(MQTTPublished(messageId))
      handleNetworkFrames(input, 30000) should_== result
    }

    "Define the actions to perform to handle a PubrecFrame" in {
      val header = Header(dup = false, AtMostOnce, retain = false)
      val messageId = Random.nextInt(65534) + 1
      val messageIdentifier = MessageIdentifier(messageId)
      val input = PubrecFrame(header, messageIdentifier)
      val result = SendToNetwork(PubrelFrame(header, messageIdentifier))
      handleNetworkFrames(input, 30000) should_== result
    }

    "Define the actions to perform to handle a PubcompFrame" in {
      val header = Header(dup = false, AtMostOnce, retain = false)
      val messageId = Random.nextInt(65534) + 1
      val messageIdentifier = MessageIdentifier(messageId)
      val input = PubcompFrame(header, messageIdentifier)
      val result = SendToClient(MQTTPublished(messageId))
      handleNetworkFrames(input, 30000) should_== result
    }

    "Define the actions to perform to handle a SubackFrame" in {
      val header = Header(dup = false, AtMostOnce, retain = false)
      val identifier: Int = Random.nextInt(65534) + 1
      val messageIdentifier = MessageIdentifier(identifier)
      val qos = Vector[QualityOfService](AtLeastOnce, ExactlyOnce)
      val input = SubackFrame(header, messageIdentifier, qos)
      val result = SendToClient(MQTTSubscribed(identifier, qos))
      handleNetworkFrames(input, 30000) should_== result
    }
  }
}
